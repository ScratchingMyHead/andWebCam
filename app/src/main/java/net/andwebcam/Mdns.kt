package net.andwebcam

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException

/**
 * A deliberately small mDNS / DNS-SD responder.
 *
 * It advertises one host (NAME.local) and one service (_andwebcam._tcp.local),
 * and answers A / SRV / TXT / PTR queries for them. That is all the desktop
 * needs to discover the camera and resolve it by name; a full responder such as
 * jmDNS would add a dependency for no benefit here.
 */
class Mdns(
    private val ctx: android.content.Context,
    serviceName: String,
    private val port: Int,
) {
    companion object {
        private const val TAG = "andWebCam/mdns"
        private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
        private const val MDNS_PORT = 5353
        private const val TTL = 120L
        private const val SERVICE_TYPE = "_andwebcam._tcp.local"
        private const val ENUM = "_services._dns-sd._udp.local"
    }

    private val host = "${serviceName.lowercase()}.local"
    private val instance = "$serviceName.$SERVICE_TYPE"

    @Volatile private var running = false
    private var socket: MulticastSocket? = null
    private var receiver: Thread? = null
    private var announcer: Thread? = null

    fun start() {
        stop()
        receiver = Thread({ receiveLoop() }, "andwebcam-mdns-rx").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    // ------------------------------------------------------------------

    private fun receiveLoop() {
        val nif = findWifiInterface()
        if (nif == null) {
            Log.w(TAG, "no usable network interface; mDNS disabled")
            return
        }
        try {
            val sock = MulticastSocket(null as SocketAddress?)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(MDNS_PORT))
            sock.networkInterface = nif
            sock.timeToLive = 255
            sock.joinGroup(InetSocketAddress(GROUP, MDNS_PORT), nif)
            sock.soTimeout = 1000
            socket = sock
            running = true
        } catch (e: Exception) {
            Log.w(TAG, "cannot start mDNS: ${e.message}")
            return
        }

        startAnnouncer(nif)

        val buf = ByteArray(4096)
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                socket?.receive(pkt)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running) continue else break
            }
            if (!running) break
            try {
                if (shouldAnswer(pkt.data, pkt.length)) {
                    val ip = ipBytes(nif) ?: continue
                    send(nif, buildAnnouncement(ip))
                }
            } catch (e: Exception) {
                Log.w(TAG, "query handling failed: ${e.message}")
            }
        }
    }

    private fun startAnnouncer(nif: NetworkInterface) {
        announcer = Thread({
            var n = 0
            while (running) {
                try {
                    val ip = ipBytes(nif)
                    if (ip != null) send(nif, buildAnnouncement(ip))
                } catch (_: Exception) {
                }
                // A few quick repeats at startup, then periodically.
                val delay = when (n) { 0, 1 -> 1000L; 2 -> 2000L; else -> 45000L }
                n++
                try { Thread.sleep(delay) } catch (_: InterruptedException) { return@Thread }
            }
        }, "andwebcam-mdns-tx").apply { isDaemon = true; start() }
    }

    private fun send(nif: NetworkInterface, data: ByteArray) {
        val sock = socket ?: return
        try {
            sock.send(DatagramPacket(data, data.size, GROUP, MDNS_PORT))
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
        }
    }

    // ---- query handling ----------------------------------------------

    private fun shouldAnswer(data: ByteArray, len: Int): Boolean {
        for ((name, type) in parseQuestions(data, len)) {
            val n = name.trimEnd('.')
            when {
                n == ENUM.trimEnd('.') && (type == 12 || type == 255) -> return true
                n == SERVICE_TYPE.trimEnd('.') && (type == 12 || type == 255) -> return true
                n == instance.trimEnd('.') && (type == 33 || type == 16 || type == 255) -> return true
                n == host.trimEnd('.') && (type == 1 || type == 255) -> return true
            }
        }
        return false
    }

    private fun parseQuestions(data: ByteArray, len: Int): List<Pair<String, Int>> {
        if (len < 12) return emptyList()
        val qd = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        var pos = 12
        val out = ArrayList<Pair<String, Int>>()
        for (q in 0 until qd) {
            val res = readName(data, len, pos) ?: break
            val name = res.first
            pos = res.second
            if (pos + 4 > len) break
            val type = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 4
            out.add(name to type)
        }
        return out
    }

    private fun readName(data: ByteArray, len: Int, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var pos = start
        var end = -1
        var guard = 0
        while (pos < len && guard++ < 128) {
            val l = data[pos].toInt() and 0xFF
            if (l == 0) {
                pos++
                if (end < 0) end = pos
                break
            }
            if ((l and 0xC0) == 0xC0) {
                if (pos + 1 >= len) break
                val ptr = ((l and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                if (end < 0) end = pos + 2
                pos = ptr
                continue
            }
            pos++
            if (pos + l > len) break
            sb.append(String(data, pos, l, Charsets.UTF_8)).append('.')
            pos += l
        }
        if (end < 0) end = pos
        return sb.toString().lowercase() to end
    }

    // ---- response building -------------------------------------------

    private fun buildAnnouncement(ip: ByteArray): ByteArray {
        val w = DnsWriter()
        w.u16(0)
        w.u16(0x8400)   // response + authoritative
        w.u16(0)        // questions
        w.u16(5)        // answers
        w.u16(0)        // authority
        w.u16(0)        // additional

        // PTR _services._dns-sd._udp.local -> _andwebcam._tcp.local
        w.name(ENUM); w.u16(12); w.u16(1); w.u32(TTL)
        w.rdlength { it.name(SERVICE_TYPE) }

        // PTR _andwebcam._tcp.local -> <instance>
        w.name(SERVICE_TYPE); w.u16(12); w.u16(1); w.u32(TTL)
        w.rdlength { it.name(instance) }

        // SRV <instance> -> <host>:port
        w.name(instance); w.u16(33); w.u16(0x8001); w.u32(TTL)
        w.rdlength { it.u16(0); it.u16(0); it.u16(port); it.name(host) }

        // TXT <instance>
        w.name(instance); w.u16(16); w.u16(0x8001); w.u32(TTL)
        w.rdlength {
            for (s in listOf("path=/", "version=0.1", "tls=1")) {
                val b = s.toByteArray()
                it.u8(b.size); it.bytes(b)
            }
        }

        // A <host> -> ip
        w.name(host); w.u16(1); w.u16(0x8001); w.u32(TTL)
        w.u16(4); w.bytes(ip)

        return w.toByteArray()
    }

    private class DnsWriter {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v shr 8); u8(v) }
        fun u32(v: Long) { u8((v shr 24).toInt()); u8((v shr 16).toInt()); u8((v shr 8).toInt()); u8(v.toInt()) }
        fun bytes(b: ByteArray) = out.write(b)
        fun name(n: String) {
            for (label in n.trim('.').split('.')) {
                if (label.isEmpty()) continue
                val b = label.toByteArray()
                u8(b.size); out.write(b)
            }
            u8(0)
        }
        fun size() = out.size()
        fun toByteArray() = out.toByteArray()
    }

    /** Writes a length-prefixed RDATA block produced by [body]. */
    private inline fun DnsWriter.rdlength(body: (DnsWriter) -> Unit) {
        val inner = DnsWriter()
        body(inner)
        u16(inner.size())
        bytes(inner.toByteArray())
    }

    private fun findWifiInterface(): NetworkInterface? {
        var fallback: NetworkInterface? = null
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                if (!nif.inetAddresses.toList().any { it is Inet4Address }) continue
                if (nif.name.startsWith("wlan")) return nif
                if (fallback == null) fallback = nif
            }
        } catch (_: Exception) {
        }
        return fallback
    }

    private fun ipBytes(nif: NetworkInterface): ByteArray? =
        nif.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()?.address
}
