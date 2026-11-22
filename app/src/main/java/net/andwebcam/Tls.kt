package net.andwebcam

import android.content.Context
import android.util.Base64
import java.io.File
import java.net.Socket
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.Principal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

/**
 * The phone's TLS identity.
 *
 * The keys are generated on the device the first time they are needed and
 * kept in the app's private files. A small self-signed root authority is
 * created with them, and it signs the server certificate; the handshake
 * presents both, and /cert serves the root. A root is what trust stores are
 * built to accept - a self-signed leaf imported into one reads as invalid
 * rather than trusted - so this is what makes certificate warnings fixable
 * at all. Every install therefore has its own identity, and no key material
 * is ever in the APK or the repository, so a stolen copy of the source
 * cannot be used to impersonate this phone.
 *
 * The fingerprint on the app's screen is the server certificate's: it is the
 * one a browser shows, and comparing the two tells you the certificate came
 * from this phone.
 *
 * No BouncyCastle: the certificates are DER built here and signed with the
 * platform's own SHA256withECDSA.
 */
object Tls {
    private const val STORE_PASS = "andwebcam"
    private const val DIR = "tls"
    private const val CERT = "server.crt"
    private const val KEY = "server.key.pk8"
    private const val CA_CERT = "root.crt"
    private const val DAYS = 3650L
    private const val PUB_CERT = "public.crt"
    private const val PUB_KEY = "public.key.pk8"

    private class Identity(
        val cert: X509Certificate,
        val ca: X509Certificate,
        val key: PrivateKey,
        val pem: ByteArray,
        val caPem: ByteArray,
    )

    private var loaded: Identity? = null

    fun serverCert(ctx: Context): X509Certificate = identity(ctx).cert

    /**
     * PEM as served by /cert: the root certificate, the one a client imports
     * to trust everything this phone signs.
     */
    fun certPem(ctx: Context): ByteArray = identity(ctx).caPem

    /** Colon-separated SHA-256 fingerprint, for telling the user what to trust. */
    fun fingerprint(ctx: Context): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(serverCert(ctx).encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    fun sslContext(ctx: Context): SSLContext {
        val id = identity(ctx)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("server", id.key, STORE_PASS.toCharArray(), arrayOf(id.cert, id.ca))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, STORE_PASS.toCharArray())

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, null)
        }
    }

    /**
     * A key manager that looks up the current public identity at handshake
     * time, rather than freezing it when the listener is built. Renewals and
     * on/off switches therefore take effect without restarting the server -
     * which would also throw away every viewer's login session.
     *
     * [preferPublic] makes the extra listener always use the LE certificate
     * when one exists; on the main port the choice comes from SNI instead.
     */
    private fun dynamicManager(ctx: Context, preferPublic: Boolean): X509ExtendedKeyManager {
        val own = identity(ctx)
        fun publicEntry(): Pair<PrivateKey, Array<X509Certificate>>? =
            publicIdentity(ctx)?.let { Pair(it.key, it.chain) }

        fun pick(session: SSLSession?): String {
            if (publicEntry() != null) {
                if (preferPublic) return "public"
                val wanted = Prefs.certDomain
                val names = (session as? ExtendedSSLSession)?.requestedServerNames
                if (wanted.isNotEmpty() && names != null) {
                    for (n in names) {
                        val host = String(n.encoded, Charsets.US_ASCII).trimEnd('\u0000')
                        if (host.equals(wanted, ignoreCase = true)) return "public"
                    }
                }
            }
            return "server"
        }

        return object : X509ExtendedKeyManager() {
            override fun chooseServerAlias(
                keyType: String?, issuers: Array<Principal>?, socket: Socket?
            ): String = pick((socket as? SSLSocket)?.handshakeSession)

            override fun chooseEngineServerAlias(
                keyType: String?, issuers: Array<Principal>?, engine: SSLEngine?
            ): String = pick(engine?.handshakeSession)

            override fun getServerAliases(keyType: String?, issuers: Array<Principal>?): Array<String> =
                if (publicEntry() != null) arrayOf("server", "public") else arrayOf("server")

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? = when (alias) {
                "public" -> publicEntry()?.second
                "server" -> arrayOf(own.cert, own.ca)
                else -> null
            }

            override fun getPrivateKey(alias: String?): PrivateKey? = when (alias) {
                "public" -> publicEntry()?.first
                "server" -> own.key
                else -> null
            }

            override fun chooseClientAlias(
                keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?
            ): String? = null

            override fun getClientAliases(keyType: String?, issuers: Array<Principal>?): Array<String>? = null
        }
    }

    /** One port, two identities: SNI decides which certificate to present. */
    fun dualSslContext(ctx: Context): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf(dynamicManager(ctx, false)), null, null) }

    /** The extra listener prefers the public certificate when one is present. */
    fun publicSslContext(ctx: Context): SSLContext =
        SSLContext.getInstance("TLS").apply { init(arrayOf(dynamicManager(ctx, true)), null, null) }

    // ---- public (Let's Encrypt) identity ----------------------------------

    internal class PublicIdentity(
        val cert: X509Certificate,
        val key: PrivateKey,
        val chain: Array<X509Certificate>,
    )

    /**
     * The ACME certificate, if one has been issued for the currently
     * configured name and is still good. Nothing is cached: a renewal writes
     * new files, and the next call picks them straight up.
     */
    @Synchronized
    internal fun publicIdentity(ctx: Context): PublicIdentity? {
        val name = Prefs.certDomain
        if (!Prefs.certEnabled || name.isEmpty()) return null
        val dir = File(ctx.applicationContext.filesDir, DIR)
        val certFile = File(dir, PUB_CERT)
        val keyFile = File(dir, PUB_KEY)
        if (!certFile.isFile || !keyFile.isFile) return null
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            // Android's generateCertificates() reads only the first PEM block
            // from a stream here, so split the chain ourselves. Trust anchors
            // are left out: the client already has them and a self-signed root
            // in the handshake only confuses path building.
            val chain = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----")
                .findAll(certFile.readText())
                .mapNotNull { cf.generateCertificate(it.value.byteInputStream()) as? X509Certificate }
                .filter { it.subjectX500Principal != it.issuerX500Principal }
                .toList()
                .toTypedArray()
            val leaf = chain.firstOrNull() ?: return null
            val key = KeyFactory.getInstance("EC")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            // A certificate for an old name is not usable for this one.
            if (!usable(leaf, name)) return null
            PublicIdentity(leaf, key, chain)
        } catch (e: Exception) {
            null
        }
    }

    fun publicCertInfo(ctx: Context): Map<String, Any?> {
        val pub = publicIdentity(ctx) ?: return mapOf("valid" to false)
        return mapOf(
            "valid" to true,
            "domain" to Prefs.certDomain,
            "notAfter" to pub.cert.notAfter.time,
            "fingerprint" to MessageDigest.getInstance("SHA-256")
                .digest(pub.cert.encoded).joinToString(":") { "%02X".format(it) },
        )
    }

    // ---- identity storage ---------

    @Synchronized
    private fun identity(ctx: Context): Identity {
        val name = "${Prefs.serviceName.lowercase()}.local"
        val cached = loaded
        if (cached != null && usable(cached.cert, name)) return cached

        val dir = File(ctx.applicationContext.filesDir, DIR)
        val certFile = File(dir, CERT)
        val keyFile = File(dir, KEY)
        val caCertFile = File(dir, CA_CERT)
        val existing = if (certFile.isFile && keyFile.isFile && caCertFile.isFile) {
            try {
                val cf = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(certFile.inputStream()) as X509Certificate
                val ca = cf.generateCertificate(caCertFile.inputStream()) as X509Certificate
                val key = KeyFactory.getInstance("EC")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                // A renamed host or an expired certificate is worth replacing:
                // nobody has to trust the old one any more.
                if (usable(cert, name)) {
                    Identity(cert, ca, key, certFile.readBytes(), caCertFile.readBytes())
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        val id = existing ?: generate(name).also {
            dir.mkdirs()
            certFile.writeBytes(it.pem)
            keyFile.writeBytes(it.key.encoded)
            caCertFile.writeBytes(it.caPem)
        }
        loaded = id
        return id
    }

    private fun usable(cert: X509Certificate, name: String): Boolean {
        if (cert.notAfter.time < System.currentTimeMillis() + 86400_000L) return false
        val names = try {
            cert.subjectAlternativeNames
        } catch (e: Exception) {
            null
        } ?: return false
        return names.any { it.size > 1 && it[0] == 2 && name.equals(it[1] as? String, true) }
    }

    private fun generate(name: String): Identity {
        val rnd = SecureRandom()
        // P-256 rather than RSA: it generates in milliseconds, so this can even
        // run on the UI thread that asks for the fingerprint, and browsers have
        // supported it for years.
        val algorithm = Der.seq(Der.oid("1.2.840.10045.4.3.2")) // ecdsa-with-SHA256
        val org = Der.set(Der.seq(Der.oid("2.5.4.10"), Der.utf8("andWebCam")))

        // The root: self-signed, an actual CA, allowed to sign certificates.
        // Nothing else about it matters; the leaf carries the name.
        val caName = Der.seq(org, Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8("andWebCam root"))))
        val caExt = Der.seq(
            // BasicConstraints CA:TRUE, no path length.
            Der.extension("2.5.29.19", true, Der.seq(Der.raw(0x01, byteArrayOf(0xFF.toByte())))),
            // keyUsage: digitalSignature (bit 0) and keyCertSign (bit 5).
            Der.extension("2.5.29.15", true, Der.raw(0x03, byteArrayOf(2, 0x84.toByte()))),
        )
        val caPair = keyPair(rnd)
        val caDer = signed(
            tbs(caName, caName, caPair.public.encoded, caExt, rnd), algorithm, caPair.private,
        )

        // The leaf: signed by the root, named for this phone, good for TLS.
        val subject = Der.seq(org, Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(name))))
        val extensions = Der.seq(
            Der.extension("2.5.29.19", true, Der.seq()),                          // CA:FALSE
            Der.extension("2.5.29.15", true, Der.raw(0x03, byteArrayOf(7, 0x80.toByte()))), // digitalSignature
            Der.extension("2.5.29.37", false, Der.seq(Der.oid("1.3.6.1.5.5.7.3.1"))), // serverAuth
            Der.extension(
                "2.5.29.17", false, Der.seq(
                    Der.raw(0x82, name.toByteArray(Charsets.UTF_8)),
                    Der.raw(0x82, "localhost".toByteArray(Charsets.UTF_8)),
                    Der.raw(0x87, byteArrayOf(127, 0, 0, 1)),
                )
            ),
        )
        val pair = keyPair(rnd)
        val der = signed(
            tbs(subject, caName, pair.public.encoded, extensions, rnd), algorithm, caPair.private,
        )

        return Identity(
            CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate,
            CertificateFactory.getInstance("X.509").generateCertificate(caDer.inputStream()) as X509Certificate,
            pair.private,
            pem(der),
            pem(caDer),
        )
    }

    private fun keyPair(rnd: SecureRandom) = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), rnd)
    }.generateKeyPair()

    /** One TBSCertificate: [subject] as issuer and subject both, if self-signed. */
    private fun tbs(
        subject: ByteArray,
        issuer: ByteArray,
        publicKey: ByteArray,
        extensions: ByteArray,
        rnd: SecureRandom,
    ): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val now = System.currentTimeMillis()
        // An hour of backdating covers a phone whose clock is behind the PC's.
        val validity = Der.seq(
            Der.utcTime(fmt.format(Date(now - 3600_000L))),
            Der.utcTime(fmt.format(Date(now + DAYS * 86400_000L))),
        )
        return Der.seq(
            Der.raw(0xA0, Der.integer(BigInteger.valueOf(2))),                   // v3
            Der.integer(BigInteger(64, rnd).add(BigInteger.ONE)),
            Der.seq(Der.oid("1.2.840.10045.4.3.2")),                             // ecdsa-with-SHA256
            issuer,
            validity,
            subject,
            publicKey,
            Der.raw(0xA3, extensions),
        )
    }

    private fun signed(tbs: ByteArray, algorithm: ByteArray, key: PrivateKey): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(tbs)
            sign()
        }
        return Der.seq(tbs, algorithm, Der.raw(0x03, byteArrayOf(0) + signature))
    }

    private fun pem(der: ByteArray): ByteArray {
        val text = StringBuilder("-----BEGIN CERTIFICATE-----\n")
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        var i = 0
        while (i < b64.length) {
            text.append(b64, i, minOf(i + 64, b64.length)).append('\n')
            i += 64
        }
        return text.append("-----END CERTIFICATE-----\n").toString().toByteArray()
    }

    /**
     * Just enough DER for one certificate. Only the pieces that cannot be had
     * from the platform are written by hand: the public key comes back already
     * encoded from the KeyPair.
     */
    internal object Der {
        fun raw(tag: Int, body: ByteArray): ByteArray =
            byteArrayOf(tag.toByte()) + length(body.size) + body

        fun seq(vararg parts: ByteArray): ByteArray = raw(0x30, join(parts))
        fun set(vararg parts: ByteArray): ByteArray = raw(0x31, join(parts))
        fun utf8(s: String): ByteArray = raw(0x0C, s.toByteArray(Charsets.UTF_8))
        fun utcTime(s: String): ByteArray = raw(0x17, s.toByteArray(Charsets.US_ASCII))
        fun integer(v: BigInteger): ByteArray = raw(0x02, v.toByteArray())

        fun extension(id: String, critical: Boolean, value: ByteArray): ByteArray =
            seq(
                oid(id),
                if (critical) raw(0x01, byteArrayOf(0xFF.toByte())) else ByteArray(0),
                raw(0x04, value),
            )

        fun oid(dotted: String): ByteArray {
            val parts = dotted.split('.').map { it.toLong() }
            val body = ArrayList<Byte>()
            body.add((parts[0] * 40 + parts[1]).toByte())
            for (i in 2 until parts.size) {
                var v = parts[i]
                val digits = ArrayList<Byte>()
                do {
                    digits.add((v and 0x7F).toByte())
                    v = v shr 7
                } while (v > 0)
                for (j in digits.indices.reversed()) {
                    val b = digits[j].toInt() and 0x7F
                    body.add((if (j > 0) b or 0x80 else b).toByte())
                }
            }
            return raw(0x06, body.toByteArray())
        }

        private fun length(n: Int): ByteArray {
            if (n < 0x80) return byteArrayOf(n.toByte())
            var bytes = 0
            var v = n
            while (v > 0) {
                bytes++
                v = v ushr 8
            }
            val out = ByteArray(bytes + 1)
            out[0] = (0x80 or bytes).toByte()
            for (i in bytes downTo 1) out[i] = (n ushr (8 * (bytes - i))).toByte()
            return out
        }

        private fun join(parts: Array<out ByteArray>): ByteArray {
            var size = 0
            for (p in parts) size += p.size
            val out = ByteArray(size)
            var at = 0
            for (p in parts) {
                p.copyInto(out, at)
                at += p.size
            }
            return out
        }
    }
}
