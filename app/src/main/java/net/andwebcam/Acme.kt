package net.andwebcam

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A small ACME v2 client, with no dependencies, for Let's Encrypt certificates.
 *
 * Two ways of proving control of the name are supported: dns-01, which writes
 * a TXT record through DuckDNS and needs no inbound connection at all, and
 * http-01, which answers a request on a port the router forwards from 80.
 * Everything else - account key, JWS, order, CSR, chain, renewal - is shared.
 *
 * Keys and certificates live in the app's private files. The account key is
 * separate from the TLS key, as it should be: the account can be reused for
 * several names while each certificate gets its own key pair.
 */
object Acme {
    private const val TAG = "andWebCam"
    private const val PROD = "https://acme-v02.api.letsencrypt.org/directory"
    private const val STAGING = "https://acme-staging-v02.api.letsencrypt.org/directory"
    private const val DUCK = "https://www.duckdns.org/update"

    @Volatile private var busy = false
    @Volatile private var lastError: String? = null
    @Volatile private var nonce: String? = null
    /** The last address successfully pushed to DuckDNS; avoids churn on reconnect. */
    @Volatile private var lastPublished: String? = null
    private var scheduler: ScheduledExecutorService? = null
    var onChanged: (() -> Unit)? = null

    private class Resp(val code: Int, val body: String, val headers: Map<String, String>)

    private class CertInfo(val domain: String, val issued: Long, val notAfter: Long)

    // ---- public surface ---------------------------------------------------

    fun status(ctx: Context): Map<String, Any?> = mapOf(
        "domain" to Prefs.certDomain,
        "method" to Prefs.certMethod,
        "publish" to Prefs.certPublish,
        "staging" to Prefs.certStaging,
        "hasToken" to Prefs.certToken.isNotEmpty(),
        "busy" to busy,
        "error" to lastError,
        "cert" to Tls.publicCertInfo(ctx),
        "publicPort" to Prefs.publicPort,
        "acmePort" to Prefs.acmePort,
        "challengePort" to ChallengeServer.port,
    )

    fun configure(vals: Map<String, String>) {
        vals["domain"]?.let { Prefs.certDomain = it }
        vals["token"]?.let { Prefs.certToken = it }
        vals["method"]?.let { Prefs.certMethod = it }
        vals["publish"]?.let { Prefs.certPublish = it }
        vals["staging"]?.let { Prefs.certStaging = it.toBoolean() }
        vals["acmePort"]?.toIntOrNull()?.let { Prefs.acmePort = it }
        vals["publicPort"]?.toIntOrNull()?.let { Prefs.publicPort = it }
        onChanged?.invoke()
    }

    fun start(ctx: Context, changed: () -> Unit) {
        onChanged = changed
        if (scheduler != null) return
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "andwebcam-acme").apply { isDaemon = true }
        }
        scheduler!!.scheduleWithFixedDelay({ check(ctx) }, 5, 30, TimeUnit.MINUTES)
    }

    /**
     * The phone's address changed, or connectivity came back after a drop.
     * In wan mode this is what makes us look up the public address straight
     * away rather than waiting for the next half-hour tick. A drop to "no
     * address" is handled by [publishIp] simply doing nothing; if the old
     * address comes back, the cached value stops us republishing it.
     */
    fun onNetworkChanged(ctx: Context) {
        Thread {
            try {
                publishIp(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "DDNS update after a network change failed: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
        onChanged = null
    }

    fun issueAsync(ctx: Context) {
        if (!begin()) return
        Thread {
            try {
                doIssue(ctx)
                lastError = null
            } catch (e: Exception) {
                lastError = e.message?.take(300) ?: e.javaClass.simpleName
                Log.w(TAG, "issuance failed: $lastError")
            } finally {
                end()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun begin(): Boolean = synchronized(this) {
        if (busy) false else { busy = true; true }
    }

    private fun end() {
        synchronized(this) { busy = false }
        onChanged?.invoke()
    }

    /** Called on the renewal thread: publish the address, renew when due. */
    private fun check(ctx: Context) {
        if (Prefs.certDomain.isEmpty()) return
        try { publishIp(ctx) } catch (e: Exception) {
            Log.w(TAG, "DDNS update failed: ${e.message}")
        }
        val have = certInfo(ctx)
        val due = have == null || have.domain != Prefs.certDomain ||
            System.currentTimeMillis() > have.issued + (have.notAfter - have.issued) * 2 / 3
        if (!due) return
        if (!begin()) return
        try {
            doIssue(ctx)
            lastError = null
        } catch (e: Exception) {
            lastError = e.message?.take(300) ?: e.javaClass.simpleName
            Log.w(TAG, "renewal failed: $lastError")
        } finally {
            end()
        }
    }

    // ---- the order --------------------------------------------------------

    private fun doIssue(ctx: Context) {
        val domain = Prefs.certDomain
        if (!Regex("^[a-z0-9.-]+\\.[a-z]{2,}$").matches(domain)) {
            throw Exception("Enter a domain name first")
        }
        val method = Prefs.certMethod
        if (method == "dns-01" && Prefs.certToken.isEmpty()) {
            throw Exception("Enter the DuckDNS token for DNS validation")
        }
        publishIp(ctx, force = true)

        val dirUrl = if (Prefs.certStaging) STAGING else PROD
        val d = get(dirUrl)
        if (d.code !in 200..299) throw Exception("Could not reach Let's Encrypt: ${err(d)}")
        val dir = asMap(Json.parse(d.body))
        nonce = d.headers["replay-nonce"] ?: nonce
        val newAccount = s(dir["newAccount"])
        val newOrder = s(dir["newOrder"])
        if (newAccount.isEmpty() || newOrder.isEmpty()) throw Exception("Unrecognised ACME directory")

        val account = accountPair(ctx)
        var kid = accountUrl(ctx)
        if (kid.isEmpty()) {
            val r = jpost(dirUrl, dir, account, null, newAccount, """{"termsOfServiceAgreed":true}""")
            if (r.code !in 200..299) throw Exception("Account registration: ${err(r)}")
            kid = r.headers["location"] ?: throw Exception("The ACME server gave no account URL")
            File(files(ctx), "account.url").writeText(kid)
        }

        val ordResp = jpost(
            dirUrl, dir, account, kid, newOrder,
            """{"identifiers":[{"type":"dns","value":"$domain"}]}""",
        )
        if (ordResp.code !in 200..299) throw Exception("New order: ${err(ordResp)}")
        val orderUrl = ordResp.headers["location"] ?: newOrder
        val order = asMap(Json.parse(ordResp.body))
        val authzUrl = (order["authorizations"] as? List<*>)?.firstOrNull()?.toString()
            ?: throw Exception("The order had no authorization")

        val azResp = jpost(dirUrl, dir, account, kid, authzUrl, "")
        val authz = asMap(Json.parse(azResp.body))
        val challenge = (authz["challenges"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.firstOrNull { it["type"] == method }
            ?: throw Exception("The server offered no $method challenge")
        val token = s(challenge["token"])
        val challengeUrl = s(challenge["url"])
        if (token.isEmpty() || challengeUrl.isEmpty()) throw Exception("Incomplete challenge from the server")

        val keyAuth = "$token.${thumbprint(account.public as ECPublicKey)}"
        var txtSet = false
        var httpSet = false
        try {
            if (method == "dns-01") {
                // DuckDNS puts the value at _acme-challenge.<our name>.
                val value = b64(MessageDigest.getInstance("SHA-256").digest(keyAuth.toByteArray()))
                duckCall(domain, Prefs.certToken, "&txt=${enc(value)}")
                txtSet = true
            } else {
                if (!ChallengeServer.start(Prefs.acmePort) && ChallengeServer.port < 1) {
                    throw Exception("Could not bind the challenge port ${Prefs.acmePort}")
                }
                ChallengeServer.set(token, keyAuth)
                httpSet = true
            }

            val trigger = jpost(dirUrl, dir, account, kid, challengeUrl, "{}")
            if (trigger.code !in 200..299) throw Exception("Challenge: ${err(trigger)}")

            var state = ""
            for (i in 0 until 45) {
                val r = jpost(dirUrl, dir, account, kid, authzUrl, "")
                state = s(asMap(Json.parse(r.body))["status"])
                if (state == "valid") break
                if (state == "invalid") {
                    val e = asMap(Json.parse(r.body))["error"]
                    throw Exception("Challenge failed: ${s(asMap(e)["detail"]).ifEmpty { "rejected" }}")
                }
                Thread.sleep(2000)
            }
            if (state != "valid") throw Exception("The challenge did not validate in time")

            val tlsPair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            }.generateKeyPair()
            val tlsKey = tlsPair.private
            val csr = csr(domain, tlsPair)
            val fin = jpost(dirUrl, dir, account, kid, s(order["finalize"]),
                """{"csr":"${b64(csr)}"}""")
            if (fin.code !in 200..299) throw Exception("Finalize: ${err(fin)}")

            var certUrl = ""
            for (i in 0 until 30) {
                val r = jpost(dirUrl, dir, account, kid, orderUrl, "")
                val m = asMap(Json.parse(r.body))
                when (s(m["status"])) {
                    "valid" -> { certUrl = s(m["certificate"]); break }
                    "invalid" -> throw Exception("The order was rejected")
                }
                Thread.sleep(2000)
            }
            if (certUrl.isEmpty()) throw Exception("The certificate was not ready in time")

            val cr = jpost(dirUrl, dir, account, kid, certUrl, "")
            if (cr.code !in 200..299) throw Exception("Downloading the certificate: ${err(cr)}")
            val pem = cr.body.trim()
            if (!pem.contains("BEGIN CERTIFICATE")) throw Exception("The server returned no certificate")
            val leaf = CertificateFactory.getInstance("X.509")
                .generateCertificates(pem.byteInputStream()).firstOrNull() as? X509Certificate
                ?: throw Exception("The certificate could not be read")

            val tlsDir = File(ctx.applicationContext.filesDir, "tls")
            tlsDir.mkdirs()
            File(tlsDir, "public.key.pk8").writeBytes(tlsKey.encoded)
            File(tlsDir, "public.crt").writeBytes((pem + "\n").toByteArray())
            File(tlsDir, "public.info").writeText(
                "domain=$domain\nissued=${System.currentTimeMillis()}\nnotAfter=${leaf.notAfter.time}\n"
            )
            Log.i(TAG, "certificate installed for $domain, valid until ${leaf.notAfter}")
        } finally {
            if (txtSet) {
                try { duckCall(domain, Prefs.certToken, "&txt=&clear=true") } catch (e: Exception) {
                    Log.w(TAG, "could not clear the TXT record: ${e.message}")
                }
            }
            if (httpSet) ChallengeServer.clear(token)
        }
    }

    // ---- DuckDNS ----------------------------------------------------------

    private fun publishIp(ctx: Context, force: Boolean = false) {
        val mode = Prefs.certPublish
        val domain = Prefs.certDomain
        val token = Prefs.certToken
        if (mode == "none" || domain.isEmpty() || token.isEmpty()) return
        val ip = if (mode == "lan") {
            State.ip
        } else {
            val r = get("https://api.ipify.org")
            if (r.code in 200..299) r.body.trim() else ""
        }
        if (ip.isNullOrEmpty() || !ip.matches(Regex("[0-9.]+"))) return
        if (!force && ip == lastPublished) return
        duckCall(domain, token, "&ip=$ip")
        lastPublished = ip
        Log.i(TAG, "published $ip to DuckDNS")
    }

    private fun duckCall(domain: String, token: String, extra: String) {
        val host = if (domain.endsWith(".duckdns.org")) domain else "$domain.duckdns.org"
        val url = "$DUCK?token=${enc(token)}&domains=${enc(host)}$extra"
        val r = get(url)
        if (r.code !in 200..299 || r.body.trim() != "OK") {
            throw Exception("DuckDNS refused the update: ${r.body.take(120).ifEmpty { "HTTP ${r.code}" }}")
        }
    }

    // ---- account and certificate files ------------------------------------

    private fun files(ctx: Context): File = File(ctx.applicationContext.filesDir, "acme")

    private fun accountPair(ctx: Context): KeyPair {
        val dir = files(ctx)
        dir.mkdirs()
        val keyFile = File(dir, "account.key")
        val pubFile = File(dir, "account.pub")
        if (keyFile.isFile && pubFile.isFile) {
            try {
                val kf = KeyFactory.getInstance("EC")
                return KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes())),
                    kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes())),
                )
            } catch (e: Exception) {
                Log.w(TAG, "regenerating unreadable account key")
            }
        }
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        keyFile.writeBytes(pair.private.encoded)
        pubFile.writeBytes(pair.public.encoded)
        return pair
    }

    private fun accountUrl(ctx: Context): String {
        val f = File(files(ctx), "account.url")
        return if (f.isFile) f.readText().trim() else ""
    }

    private fun certInfo(ctx: Context): CertInfo? {
        val f = File(File(ctx.applicationContext.filesDir, "tls"), "public.info")
        if (!f.isFile) return null
        return try {
            val p = HashMap<String, String>()
            f.readLines().forEach {
                val i = it.indexOf('=')
                if (i > 0) p[it.substring(0, i)] = it.substring(i + 1)
            }
            CertInfo(
                p["domain"] ?: return null,
                p["issued"]?.toLongOrNull() ?: return null,
                p["notAfter"]?.toLongOrNull() ?: return null,
            )
        } catch (e: Exception) {
            null
        }
    }

    // ---- JWS / CSR --------------------------------------------------------

    private fun jwk(pub: ECPublicKey): Map<String, String> = mapOf(
        "kty" to "EC",
        "crv" to "P-256",
        "x" to b64(fixed32(pub.w.affineX)),
        "y" to b64(fixed32(pub.w.affineY)),
    )

    private fun thumbprint(pub: ECPublicKey): String {
        val j = jwk(pub)
        // RFC 7638: the members in lexicographic order, no whitespace.
        val canonical = "{\"crv\":\"${j["crv"]}\",\"kty\":\"${j["kty"]}\"," +
            "\"x\":\"${j["x"]}\",\"y\":\"${j["y"]}\"}"
        return b64(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
    }

    private fun jpost(
        dirUrl: String,
        dir: Map<String, Any?>,
        account: KeyPair,
        kid: String?,
        url: String,
        payload: String,
    ): Resp {
        for (attempt in 0..1) {
            val header = LinkedHashMap<String, Any?>()
            header["alg"] = "ES256"
            if (kid == null) header["jwk"] = jwk(account.public as ECPublicKey)
            else header["kid"] = kid
            header["nonce"] = nonce ?: refreshNonce(dirUrl, dir)
            header["url"] = url

            val p64 = b64(Json.stringify(header).toByteArray())
            val a64 = b64(payload.toByteArray())
            val der = Signature.getInstance("SHA256withECDSA").run {
                initSign(account.private)
                update("$p64.$a64".toByteArray())
                sign()
            }
            // Boulder accepts the flattened JSON form; the compact form is
            // rejected before the signature is even looked at.
            val body = Json.stringify(
                mapOf(
                    "payload" to a64,
                    "protected" to p64,
                    "signature" to b64(derToRaw(der)),
                )
            )
            val r = rawPost(url, body)
            nonce = r.headers["replay-nonce"] ?: nonce
            val problem = s(asMap(Json.parse(r.body))["type"])
            if (attempt == 0 && (r.code == 400 && problem.endsWith("badNonce"))) {
                nonce = refreshNonce(dirUrl, dir)
                continue
            }
            return r
        }
        throw Exception("Could not get a fresh ACME nonce")
    }

    private fun refreshNonce(dirUrl: String, dir: Map<String, Any?>): String {
        val u = s(dir["newNonce"])
        val r = if (u.isEmpty()) get(dirUrl) else head(u)
        return r.headers["replay-nonce"] ?: throw Exception("The ACME server sent no nonce")
    }

    /** PKCS#10, with the SAN extension Let's Encrypt requires. */
    private fun csr(domain: String, pair: KeyPair): ByteArray {
        val alg = Tls.Der.seq(Tls.Der.oid("1.2.840.10045.4.3.2"))
        val subject = Tls.Der.seq(
            Tls.Der.set(Tls.Der.seq(Tls.Der.oid("2.5.4.3"), Tls.Der.utf8(domain)))
        )
        val san = Tls.Der.extension(
            "2.5.29.17", false, Tls.Der.seq(Tls.Der.raw(0x82, domain.toByteArray()))
        )
        val attribute = Tls.Der.seq(
            Tls.Der.oid("1.2.840.113549.1.9.14"), // extensionRequest
            Tls.Der.set(Tls.Der.seq(san)),
        )
        val info = Tls.Der.seq(
            Tls.Der.integer(BigInteger.ZERO),
            subject,
            pair.public.encoded,
            Tls.Der.raw(0xA0, attribute), // [0] IMPLICIT Attributes
        )
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(info)
            sign()
        }
        return Tls.Der.seq(info, alg, Tls.Der.raw(0x03, byteArrayOf(0) + sig))
    }

    // ---- small helpers ----------------------------------------------------

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun fixed32(v: BigInteger): ByteArray {
        var b = v.toByteArray()
        while (b.size > 1 && b[0] == 0.toByte()) b = b.copyOfRange(1, b.size)
        val out = ByteArray(32)
        if (b.size > 32) b = b.copyOfRange(b.size - 32, b.size)
        b.copyInto(out, 32 - b.size)
        return out
    }

    /** Java signs with DER; JWS wants the two integers concatenated. */
    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 0
        fun len(): Int {
            val b = der[i++].toInt() and 0xFF
            if (b < 0x80) return b
            val n = b and 0x7F
            var v = 0
            repeat(n) { v = (v shl 8) or (der[i++].toInt() and 0xFF) }
            return v
        }
        if (der[i++] != 0x30.toByte()) throw Exception("bad ECDSA signature")
        len()
        if (der[i++] != 0x02.toByte()) throw Exception("bad ECDSA signature")
        val lr = len()
        val r = der.copyOfRange(i, i + lr)
        i += lr
        if (der[i++] != 0x02.toByte()) throw Exception("bad ECDSA signature")
        val ls = len()
        val ss = der.copyOfRange(i, i + ls)
        fun norm(src: ByteArray): ByteArray {
            var b = src
            while (b.size > 1 && b[0] == 0.toByte()) b = b.copyOfRange(1, b.size)
            if (b.size > 32) b = b.copyOfRange(b.size - 32, b.size)
            val out = ByteArray(32)
            b.copyInto(out, 32 - b.size)
            return out
        }
        return norm(r) + norm(ss)
    }

    private fun headers(c: HttpURLConnection): Map<String, String> {
        val out = HashMap<String, String>()
        for ((k, v) in c.headerFields) {
            if (k != null && v.isNotEmpty()) out[k.lowercase()] = v.first()
        }
        return out
    }

    private fun body(c: HttpURLConnection): String {
        val stream = if (c.responseCode in 200..399) c.inputStream else c.errorStream
        return stream?.use { String(it.readBytes()) } ?: ""
    }

    private fun get(url: String): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", "application/json, text/plain")
        c.setRequestProperty("User-Agent", "andWebCam ACME")
        val r = Resp(c.responseCode, body(c), headers(c))
        c.disconnect()
        return r
    }

    private fun head(url: String): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        val r = Resp(c.responseCode, "", headers(c))
        c.disconnect()
        return r
    }

    private fun rawPost(url: String, payload: String): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 40_000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Content-Type", "application/jose+json")
        c.setRequestProperty("Accept", "application/json, application/pem-certificate-chain")
        c.setRequestProperty("User-Agent", "andWebCam ACME")
        c.outputStream.use { it.write(payload.toByteArray()) }
        val r = Resp(c.responseCode, body(c), headers(c))
        c.disconnect()
        return r
    }

    private fun err(r: Resp): String {
        val detail = s(asMap(Json.parse(r.body))["detail"])
        return detail.ifEmpty { r.body.take(160).replace('\n', ' ').ifEmpty { "HTTP ${r.code}" } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()

    private fun s(v: Any?): String = v?.toString() ?: ""
}
