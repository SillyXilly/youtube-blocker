package com.shortsblock

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class DnsVpnService : VpnService() {

    private val TAG = "DnsVpnService"
    private val PREFS_NAME = "shorts_blocker_prefs"
    private val KEY_BLOCKED_DOMAINS = "blocked_domains"

    // The VPN assigns itself this address as the DNS server.
    // Only traffic destined for this IP goes through the tunnel —
    // all other traffic (HTTP, HTTPS, etc.) is unaffected.
    private val VPN_ADDRESS = "10.0.0.2"
    private val VPN_DNS     = "10.0.0.1"

    // Real upstream DNS — allowed queries are forwarded here
    private val UPSTREAM_DNS = "8.8.8.8"
    private val UPSTREAM_PORT = 53

    private var vpnThread: Thread? = null
    private var pfd: ParcelFileDescriptor? = null
    @Volatile private var running = false

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, DnsVpnService::class.java).apply { action = "START" })
        }
        fun stop(context: Context) {
            context.startService(Intent(context, DnsVpnService::class.java).apply { action = "STOP" })
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
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS)
            // CRITICAL: only route traffic to our fake DNS IP through the tunnel.
            // All other traffic (HTTP, HTTPS, etc.) bypasses the tunnel entirely
            // and goes directly to the network — no internet disruption.
            .addRoute(VPN_DNS, 32)
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
            val buffer = ByteArray(32767)

            while (running) {
                try {
                    val length = inputStream.read(buffer)
                    if (length <= 0) continue

                    val packet = buffer.copyOf(length)
                    val response = handleDnsPacket(packet)
                    if (response != null) {
                        outputStream.write(response)
                    }
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "VPN loop error: ${e.message}")
                }
            }
        }.also { it.start() }

        Log.d(TAG, "VPN started — only DNS traffic routed through tunnel")
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
     * Handles an incoming IPv4/UDP/DNS packet.
     *
     * - If the queried domain is in the block list: returns a NXDOMAIN response.
     * - If the queried domain is NOT blocked: forwards the query to the real upstream
     *   DNS (8.8.8.8), waits for the real response, and returns it.
     * - If the packet is not a DNS query: returns null (ignored).
     */
    private fun handleDnsPacket(packet: ByteArray): ByteArray? {
        return try {
            if (packet.size < 20) return null

            val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
            val protocol    = packet[9].toInt() and 0xFF
            if (protocol != 17) return null  // UDP only

            if (packet.size < ipHeaderLen + 8) return null
            val udpStart = ipHeaderLen
            val dstPort  = ((packet[udpStart + 2].toInt() and 0xFF) shl 8) or
                            (packet[udpStart + 3].toInt() and 0xFF)
            if (dstPort != 53) return null  // DNS only

            val dnsStart = udpStart + 8
            if (packet.size <= dnsStart + 12) return null
            val dnsPayload = packet.copyOfRange(dnsStart, packet.size)

            val queriedDomain = extractDnsQueryDomain(dnsPayload)
            Log.d(TAG, "DNS query: $queriedDomain")

            val blockedDomains = getBlockedDomains()
            val shouldBlock = queriedDomain != null && blockedDomains.any { blocked ->
                queriedDomain == blocked || queriedDomain.endsWith(".$blocked")
            }

            if (shouldBlock) {
                Log.d(TAG, "Blocking: $queriedDomain")
                buildNxdomainResponse(packet, ipHeaderLen, dnsPayload)
            } else {
                // Forward to real DNS and relay the real answer back
                forwardToUpstreamDns(packet, ipHeaderLen, dnsPayload)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleDnsPacket error: ${e.message}")
            null
        }
    }

    /**
     * Sends the DNS query to the real upstream DNS server (8.8.8.8),
     * waits for the response, and wraps it in the original IP/UDP headers
     * pointing back to the original requester.
     */
    private fun forwardToUpstreamDns(
        originalPacket: ByteArray,
        ipHeaderLen: Int,
        dnsPayload: ByteArray
    ): ByteArray? {
        return try {
            val socket = DatagramSocket()
            // protect() tells Android to route this socket OUTSIDE the VPN tunnel,
            // so it uses the real network interface — no infinite loop.
            protect(socket)

            val upstreamAddress = InetAddress.getByName(UPSTREAM_DNS)
            val queryPacket = DatagramPacket(dnsPayload, dnsPayload.size, upstreamAddress, UPSTREAM_PORT)
            socket.soTimeout = 3000
            socket.send(queryPacket)

            val responseBuffer = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)
            socket.close()

            val dnsResponse = responseBuffer.copyOf(responsePacket.length)
            buildIpUdpResponse(originalPacket, ipHeaderLen, dnsResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Upstream DNS forward failed: ${e.message}")
            null
        }
    }

    /**
     * Builds a NXDOMAIN DNS response — used for blocked domains.
     */
    private fun buildNxdomainResponse(
        originalPacket: ByteArray,
        ipHeaderLen: Int,
        dnsQuery: ByteArray
    ): ByteArray {
        val dnsResponse = dnsQuery.copyOf()
        dnsResponse[2] = 0x81.toByte()  // QR=1, AA=1
        dnsResponse[3] = 0x83.toByte()  // RCODE=3 (NXDOMAIN)
        return buildIpUdpResponse(originalPacket, ipHeaderLen, dnsResponse)
    }

    /**
     * Wraps a DNS payload in IP + UDP headers, swapping src/dst so the
     * response goes back to the original requester.
     */
    private fun buildIpUdpResponse(
        originalPacket: ByteArray,
        ipHeaderLen: Int,
        dnsPayload: ByteArray
    ): ByteArray {
        val udpStart    = ipHeaderLen
        val udpLength   = 8 + dnsPayload.size
        val totalLength = ipHeaderLen + udpLength
        val response    = ByteArray(totalLength)

        // Copy IP header and swap src/dst addresses
        originalPacket.copyInto(response, 0, 0, ipHeaderLen)
        originalPacket.copyInto(response, 12, 16, 20)  // dst IP → src
        originalPacket.copyInto(response, 16, 12, 16)  // src IP → dst

        // Swap UDP src/dst ports
        response[udpStart]     = originalPacket[udpStart + 2]
        response[udpStart + 1] = originalPacket[udpStart + 3]
        response[udpStart + 2] = originalPacket[udpStart]
        response[udpStart + 3] = originalPacket[udpStart + 1]

        // UDP length
        response[udpStart + 4] = (udpLength shr 8).toByte()
        response[udpStart + 5] = (udpLength and 0xFF).toByte()

        // Clear UDP checksum — valid for loopback/VPN
        response[udpStart + 6] = 0
        response[udpStart + 7] = 0

        // DNS payload
        dnsPayload.copyInto(response, udpStart + 8)

        // Fix IP total length
        response[2] = (totalLength shr 8).toByte()
        response[3] = (totalLength and 0xFF).toByte()

        // Clear IP checksum — kernel recalculates
        response[10] = 0
        response[11] = 0

        return response
    }

    private fun extractDnsQueryDomain(dns: ByteArray): String? {
        return try {
            var pos = 12
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
