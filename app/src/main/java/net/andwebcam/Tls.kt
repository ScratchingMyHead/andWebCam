package net.andwebcam

import android.content.Context
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Loads the bundled self-signed certificate (see scripts/make-cert.sh) into an
 * SSLContext, without pulling in BouncyCastle: the cert is PEM and the key is
 * DER PKCS#8, both parseable with the platform's own crypto providers.
 */
object Tls {
    private const val STORE_PASS = "andwebcam"

    fun serverCert(ctx: Context): X509Certificate =
        ctx.assets.open("server.crt").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    fun certPem(ctx: Context): ByteArray = ctx.assets.open("server.crt").use { it.readBytes() }

    /** Colon-separated SHA-256 fingerprint, for telling the user what to trust. */
    fun fingerprint(ctx: Context): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(serverCert(ctx).encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    fun sslContext(ctx: Context): SSLContext {
        val cert = serverCert(ctx)
        val keyDer = ctx.assets.open("server.key.pk8").use { it.readBytes() }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyDer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("server", key, STORE_PASS.toCharArray(), arrayOf(cert))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, STORE_PASS.toCharArray())

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, null)
        }
    }
}
