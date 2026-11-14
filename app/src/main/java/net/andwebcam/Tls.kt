package net.andwebcam

import android.content.Context
import android.util.Base64
import java.io.File
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * The phone's TLS identity.
 *
 * The key is generated on the device the first time it is needed and kept in
 * the app's private files, and the certificate that goes with it is signed by
 * that key alone. Every install therefore has its own identity, and no key
 * material is ever in the APK or the repository, so a stolen copy of the source
 * cannot be used to impersonate this phone.
 *
 * It is a self-signed leaf (CA:FALSE), not an authority, so browsers show their
 * warning and ask you to make an exception. The point is that the fingerprint
 * on the app's screen identifies this install: compare it with the one the
 * browser shows, and you know the certificate came from this phone.
 *
 * No BouncyCastle: the certificate is DER built here and signed with the
 * platform's own SHA256withECDSA.
 */
object Tls {
    private const val STORE_PASS = "andwebcam"
    private const val DIR = "tls"
    private const val CERT = "server.crt"
    private const val KEY = "server.key.pk8"
    private const val DAYS = 3650L

    private class Identity(val cert: X509Certificate, val key: PrivateKey, val pem: ByteArray)

    private var loaded: Identity? = null

    fun serverCert(ctx: Context): X509Certificate = identity(ctx).cert

    /** PEM as served by /cert, so a client can pin or trust it out of band. */
    fun certPem(ctx: Context): ByteArray = identity(ctx).pem

    /** Colon-separated SHA-256 fingerprint, for telling the user what to trust. */
    fun fingerprint(ctx: Context): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(serverCert(ctx).encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    fun sslContext(ctx: Context): SSLContext {
        val id = identity(ctx)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("server", id.key, STORE_PASS.toCharArray(), arrayOf(id.cert))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, STORE_PASS.toCharArray())

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, null)
        }
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
        val existing = if (certFile.isFile && keyFile.isFile) {
            try {
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certFile.inputStream()) as X509Certificate
                val key = KeyFactory.getInstance("EC")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                // A renamed host or an expired certificate is worth replacing:
                // nobody has to trust the old one any more.
                if (usable(cert, name)) Identity(cert, key, certFile.readBytes()) else null
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
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), rnd)
        }.generateKeyPair()

        val algorithm = Der.seq(Der.oid("1.2.840.10045.4.3.2")) // ecdsa-with-SHA256
        val subject = Der.seq(
            Der.set(Der.seq(Der.oid("2.5.4.10"), Der.utf8("andWebCam"))),
            Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(name))),
        )
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val now = System.currentTimeMillis()
        // An hour of backdating covers a phone whose clock is behind the PC's.
        val validity = Der.seq(
            Der.utcTime(fmt.format(Date(now - 3600_000L))),
            Der.utcTime(fmt.format(Date(now + DAYS * 86400_000L))),
        )
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

        val tbs = Der.seq(
            Der.raw(0xA0, Der.integer(BigInteger.valueOf(2))),                   // v3
            Der.integer(BigInteger(64, rnd).add(BigInteger.ONE)),
            algorithm,
            subject,                                                             // issuer
            validity,
            subject,                                                             // subject
            pair.public.encoded,
            Der.raw(0xA3, extensions),
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(tbs)
            sign()
        }
        val der = Der.seq(tbs, algorithm, Der.raw(0x03, byteArrayOf(0) + signature))

        return Identity(
            CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate,
            pair.private,
            pem(der),
        )
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
    private object Der {
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
