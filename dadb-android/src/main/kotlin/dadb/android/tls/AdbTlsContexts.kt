package dadb.android.tls

import dadb.AdbKeyPair
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt

internal object AdbTlsContexts {
    fun create(
        keyPair: AdbKeyPair,
        trustManager: X509ExtendedTrustManager,
    ): SSLContext {
        val privateKey = keyPair.privateKey()
        val certificate = createCertificate(privateKey)

        return runCatching {
            val conscryptProvider = ensureConscryptProvider()
            SSLContext.getInstance("TLSv1.3", conscryptProvider)
        }.recoverCatching {
            SSLContext.getInstance("TLSv1.3")
        }.recoverCatching {
            SSLContext.getInstance("TLS")
        }.getOrThrow().apply {
            init(
                arrayOf(createKeyManager(privateKey, certificate)),
                arrayOf(trustManager),
                SecureRandom(),
            )
        }
    }

    fun createUnsafe(keyPair: AdbKeyPair): SSLContext = create(keyPair, createInsecureTrustManager())

    private fun createCertificate(privateKey: PrivateKey): X509Certificate {
        val rsaPrivateKey =
            privateKey as? RSAPrivateKey
                ?: throw IllegalStateException("Wireless Debugging TLS requires an RSA private key")

        val rsaPublicKey =
            KeyFactory.getInstance("RSA")
                .generatePublic(RSAPublicKeySpec(rsaPrivateKey.modulus, BigInteger.valueOf(65537L))) as RSAPublicKey

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(rsaPrivateKey)
        val builder =
            X509v3CertificateBuilder(
                X500Name("CN=00"),
                BigInteger.ONE,
                java.util.Date(0),
                java.util.Date(2_461_449_600_000L),
                X500Name("CN=00"),
                SubjectPublicKeyInfo.getInstance(rsaPublicKey.encoded),
            )
        val encoded = builder.build(signer).encoded

        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
    }

    private fun createKeyManager(
        privateKey: PrivateKey,
        certificate: X509Certificate,
    ): X509ExtendedKeyManager =
        object : X509ExtendedKeyManager() {
            private val keyAlias = "adbkey"

            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out java.security.Principal>?,
                socket: Socket?,
            ): String = keyAlias

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == keyAlias) {
                    arrayOf(certificate)
                } else {
                    null
                }

            override fun getPrivateKey(alias: String?): PrivateKey? =
                if (alias == keyAlias) {
                    privateKey
                } else {
                    null
                }

            override fun getClientAliases(
                keyType: String?,
                issuers: Array<out java.security.Principal>?,
            ): Array<String>? = null

            override fun getServerAliases(
                keyType: String?,
                issuers: Array<out java.security.Principal>?,
            ): Array<String>? = null

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out java.security.Principal>?,
                socket: Socket?,
            ): String? = null
        }

    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun createInsecureTrustManager(): X509ExtendedTrustManager =
        AdbTlsTrustManagers.createUnsafe()

    private fun ensureConscryptProvider(): Provider {
        val provider = Conscrypt.newProviderBuilder().build()
        if (Security.getProvider(provider.name) == null) {
            Security.insertProviderAt(provider, 1)
        }
        return provider
    }
}
