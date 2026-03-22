package com.shortsblock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.shortsblock.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val CORRECT_PIN          = "1407"
    private val PREFS_NAME           = "shorts_blocker_prefs"
    private val KEY_BLOCKING_ENABLED = "blocking_enabled"
    private val KEY_VPN_ENABLED      = "vpn_enabled"
    private val KEY_BLOCKED_DOMAINS  = "blocked_domains"
    private val VPN_REQUEST_CODE     = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupPinScreen()
        setupControlScreen()
    }

    override fun onResume() {
        super.onResume()
        if (binding.controlScreen.visibility == View.VISIBLE) updateServiceStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                DnsVpnService.start(this)
                saveVpnEnabled(true)
            } else {
                // User denied — revert toggle
                binding.vpnToggle.isChecked = false
            }
        }
    }

    // ── PIN ───────────────────────────────────────────────────────────────────

    private fun setupPinScreen() {
        binding.pinInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.pinError.visibility = View.GONE
                if (s?.length == 4) checkPin(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.btnUnlock.setOnClickListener { checkPin(binding.pinInput.text.toString()) }
    }

    private fun checkPin(entered: String) {
        if (entered == CORRECT_PIN) {
            hideKeyboard()
            showControlScreen()
        } else {
            binding.pinError.visibility = View.VISIBLE
            binding.pinInput.setText("")
            binding.pinInput.requestFocus()
        }
    }

    // ── CONTROL SCREEN ────────────────────────────────────────────────────────

    private fun setupControlScreen() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Shorts toggle
        binding.shortsToggle.isChecked = prefs.getBoolean(KEY_BLOCKING_ENABLED, false)
        binding.shortsToggle.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_BLOCKING_ENABLED, checked).apply()
        }
        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // VPN toggle
        binding.vpnToggle.isChecked = prefs.getBoolean(KEY_VPN_ENABLED, false)
        binding.vpnToggle.setOnCheckedChangeListener { _, checked ->
            if (checked) requestVpnPermission()
            else {
                DnsVpnService.stop(this)
                saveVpnEnabled(false)
            }
        }

        // Add domain button
        binding.btnAddDomain.setOnClickListener {
            val raw = binding.domainInput.text.toString().trim().lowercase()
                .removePrefix("http://").removePrefix("https://").removePrefix("www.")
                .split("/")[0]
            if (raw.isNotBlank() && raw.contains(".")) {
                addDomain(raw)
                binding.domainInput.setText("")
                binding.domainInputLayout.error = null
            } else {
                binding.domainInputLayout.error = "Enter a valid domain"
            }
        }

        // Lock button
        binding.btnLock.setOnClickListener {
            binding.pinInput.setText("")
            binding.pinError.visibility = View.GONE
            binding.pinScreen.visibility = View.VISIBLE
            binding.controlScreen.visibility = View.GONE
        }

        refreshDomainList()
    }

    private fun showControlScreen() {
        binding.pinScreen.visibility = View.GONE
        binding.controlScreen.visibility = View.VISIBLE
        updateServiceStatus()
        refreshDomainList()
    }

    private fun updateServiceStatus() {
        if (!isAccessibilityServiceEnabled()) {
            binding.serviceStatusText.text =
                "⚠ Accessibility permission not granted. Tap \"Open accessibility settings\" " +
                "in the YouTube Shorts module, find \"Weather Sync\", and enable it."
            binding.cardServiceWarning.visibility = View.VISIBLE
        } else {
            binding.cardServiceWarning.visibility = View.GONE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${ShortsBlockerService::class.java.name}"
        val enabled  = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    // ── VPN ───────────────────────────────────────────────────────────────────

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, VPN_REQUEST_CODE)
        else {
            DnsVpnService.start(this)
            saveVpnEnabled(true)
        }
    }

    private fun saveVpnEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VPN_ENABLED, enabled).apply()
    }

    // ── DOMAIN LIST ───────────────────────────────────────────────────────────

    private fun getDomains(): MutableList<String> {
        val raw = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BLOCKED_DOMAINS, "") ?: ""
        return if (raw.isBlank()) mutableListOf()
        else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
    }

    private fun saveDomains(domains: List<String>) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_BLOCKED_DOMAINS, domains.joinToString(",")).apply()
    }

    private fun addDomain(domain: String) {
        val domains = getDomains()
        if (!domains.contains(domain)) {
            domains.add(domain)
            saveDomains(domains)
            refreshDomainList()
        } else {
            binding.domainInputLayout.error = "Already in the list"
        }
    }

    private fun removeDomain(domain: String) {
        val domains = getDomains()
        domains.remove(domain)
        saveDomains(domains)
        refreshDomainList()
    }

    private fun refreshDomainList() {
        val domains   = getDomains()
        val container = binding.domainListContainer
        container.removeAllViews()

        if (domains.isEmpty()) {
            binding.emptyDomainsText.visibility = View.VISIBLE
            container.visibility = View.GONE
            return
        }

        binding.emptyDomainsText.visibility = View.GONE
        container.visibility = View.VISIBLE

        domains.forEach { domain ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_domain, container, false)
            row.findViewById<TextView>(R.id.domainText).text = domain
            row.findViewById<ImageButton>(R.id.btnRemoveDomain).setOnClickListener {
                removeDomain(domain)
            }
            container.addView(row)
        }
    }

    // ── UTILS ─────────────────────────────────────────────────────────────────

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}
