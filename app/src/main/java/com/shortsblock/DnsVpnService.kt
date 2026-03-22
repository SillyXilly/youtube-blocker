package com.shortsblock

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class DnsVpnService : VpnService() {

    private val TAG = "DnsVpnService"
    private val PREFS_NAME = "shorts_blocker_prefs"
    private val KEY_BLOCKED_DOMAINS = "blocked_domains"

    private var vpnThread: Thread? = null
    private var pfd: ParcelFileDescriptor? = null
    @Volatile private var running = false

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = "START"
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).apply {
                action = "STOP"
            }
            context.startService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startVpn()
            "STOP"  -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return
        running = true

        val builder = Builder()
            .setSession("Weather Sync VPN")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.1")
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)

        try {
            pfd = builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN: ${e.message}")
            running = false
            return
        }

        val vpnInterface = pfd ?: return

        vpnThread = Thread {
            val inputStream  = FileInputStream(vpnInterface.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface.fileDescriptor)
            val buffer = ByteBuffer.allocate(32767)

            while (running) {
                try {
                    buffer.clear()
                    val length = inputStream.read(buffer.array())
                    if (length <= 0) continue
                    buffer.limit(length)

                    val blockedDomains = getBlockedDomains()
                    val response = processPacket(buffer, blockedDomains)
                    if (response != null) outputStream.write(response)

                } catch (e: Exception) {
                    if (running) Log.e(TAG, "VPN read error: ${e.message}")
                }
            }
        }.also { it.start() }

        Log.d(TAG, "VPN started")
    }

    private fun stopVpn() {
        running = false
        vpnThread?.interrupt()
        vpnThread = null
        try { pfd?.close() } catch (e: Exception) { Log.e(TAG, "Error closing VPN: ${e.message}") }
        pfd = null
        stopSelf()
        Log.d(TAG, "VPN stopped")
    }

    /**
     * Reads an IPv4/UDP/DNS packet. If the queried domain matches a blocked domain,
     * returns a DNS NXDOMAIN response. Otherwise returns null (packet passes through).
     */
    private fun processPacket(buffer: ByteBuffer, blockedDomains: Set<String>): ByteArray? {
        return try {
            val packet = buffer.array().copyOf(buffer.limit())
            if (packet.size < 20) return null

            val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
            val protocol = packet[9].toInt() and 0xFF
            if (protocol != 17) return null  // UDP only
            if (packet.size < ipHeaderLength + 8) return null

            val udpStart = ipHeaderLength
            val destPort = ((packet[udpStart + 2].toInt() and 0xFF) shl 8) or
                           (packet[udpStart + 3].toInt() and 0xFF)
            if (destPort != 53) return null  // DNS only

            val dnsStart = udpStart + 8
            if (packet.size <= dnsStart + 12) return null

            val dnsPayload = packet.copyOfRange(dnsStart, packet.size)
            val queriedDomain = extractDnsQueryDomain(dnsPayload) ?: return null

            val shouldBlock = blockedDomains.any { blocked ->
                queriedDomain == blocked || queriedDomain.endsWith(".$blocked")
            }
            if (!shouldBlock) return null

            Log.d(TAG, "Blocking DNS query for: $queriedDomain")
            buildBlockedDnsResponse(packet, ipHeaderLength, dnsPayload)

        } catch (e: Exception) {
            Log.e(TAG, "Packet processing error: ${e.message}")
            null
        }
    }

    private fun extractDnsQueryDomain(dns: ByteArray): String? {
        return try {
            var pos = 12  // DNS header is 12 bytes
            val labels = mutableListOf<String>()
            while (pos < dns.size) {
                val len = dns[pos].toInt() and 0xFF
                if (len == 0) break
                pos++
                if (pos + len > dns.size) return null
                labels.add(String(dns, pos, len))
                pos += len
            }
            labels.joinToString(".")
        } catch (e: Exception) { null }
    }

    private fun buildBlockedDnsResponse(
        originalPacket: ByteArray,
        ipHeaderLength: Int,
        dnsQuery: ByteArray
    ): ByteArray {
        val dnsResponse = dnsQuery.copyOf()
        dnsResponse[2] = (0x81).toByte()  // QR + AA
        dnsResponse[3] = (0x83).toByte()  // NXDOMAIN

        val srcIp = originalPacket.copyOfRange(12, 16)
        val dstIp = originalPacket.copyOfRange(16, 20)
        val response = ByteArray(originalPacket.size).also { originalPacket.copyInto(it) }
        dstIp.copyInto(response, 12)
        srcIp.copyInto(response, 16)

        val udpStart = ipHeaderLength
        val srcPort  = response.copyOfRange(udpStart, udpStart + 2)
        val dstPortB = response.copyOfRange(udpStart + 2, udpStart + 4)
        dstPortB.copyInto(response, udpStart)
        srcPort.copyInto(response, udpStart + 2)

        dnsResponse.copyInto(response, udpStart + 8)

        val udpLength   = 8 + dnsResponse.size
        val totalLength = ipHeaderLength + udpLength
        response[udpStart + 4] = (udpLength shr 8).toByte()
        response[udpStart + 5] = (udpLength and 0xFF).toByte()
        response[udpStart + 6] = 0  // checksum cleared
        response[udpStart + 7] = 0

        response[2] = (totalLength shr 8).toByte()
        response[3] = (totalLength and 0xFF).toByte()
        response[10] = 0  // IP checksum cleared — kernel recalculates
        response[11] = 0

        return response.copyOf(totalLength)
    }

    private fun getBlockedDomains(): Set<String> {
        val raw = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BLOCKED_DOMAINS, "") ?: ""
        return if (raw.isBlank()) emptySet()
        else raw.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
