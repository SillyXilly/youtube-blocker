package com.shortsblock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.shortsblock.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val CORRECT_PIN = "1407"
    private val PREFS_NAME = "shorts_blocker_prefs"
    private val KEY_BLOCKING_ENABLED = "blocking_enabled"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupPinScreen()
        setupControlScreen()
    }

    override fun onResume() {
        super.onResume()
        if (binding.controlScreen.visibility == View.VISIBLE) {
            updateServiceStatus()
        }
    }

    private fun setupPinScreen() {
        binding.pinInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.pinError.visibility = View.GONE
                if (s?.length == 4) checkPin(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.btnUnlock.setOnClickListener {
            checkPin(binding.pinInput.text.toString())
        }
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

    private fun setupControlScreen() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.shortsToggle.isChecked = prefs.getBoolean(KEY_BLOCKING_ENABLED, false)
        binding.shortsToggle.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_BLOCKING_ENABLED, checked).apply()
        }
        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnLock.setOnClickListener {
            binding.pinInput.setText("")
            binding.pinError.visibility = View.GONE
            binding.pinScreen.visibility = View.VISIBLE
            binding.controlScreen.visibility = View.GONE
        }
    }

    private fun showControlScreen() {
        binding.pinScreen.visibility = View.GONE
        binding.controlScreen.visibility = View.VISIBLE
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        if (!isAccessibilityServiceEnabled()) {
            binding.serviceStatusText.text =
                "Accessibility permission not granted. Tap \"Open Accessibility Settings\" below, find \"Weather Sync\", and enable it."
            binding.serviceStatusText.visibility = View.VISIBLE
        } else {
            binding.serviceStatusText.visibility = View.GONE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedService = "$packageName/${ShortsBlockerService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any {
            it.equals(expectedService, ignoreCase = true)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}
