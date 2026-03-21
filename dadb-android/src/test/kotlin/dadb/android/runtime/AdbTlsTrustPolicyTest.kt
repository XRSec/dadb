package dadb.android.runtime

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateEncodingException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.X509ExtendedTrustManager
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalDadbAndroidApi::class)
class AdbTlsTrustPolicyTest {
    @Test
    fun trustAll_acceptsServerCertificate() {
        val trustManager =
            AdbTlsTrustPolicy.TrustAll.resolveTrustManager(
                target = AdbNetworkEndpoint(host = "192.168.0.10", port = 37099),
            )

        trustManager.checkServerTrusted(arrayOf(fakeCertificate(generatePublicKey())), "RSA")
    }

    @Test
    fun trustAll_acceptsAnyServerCertificateShape() {
        val trustManager =
            AdbTlsTrustPolicy.TrustAll.resolveTrustManager(
                target = AdbNetworkEndpoint(host = "192.168.0.10", port = 37099),
            )

        trustManager.checkServerTrusted(arrayOf(fakeCertificate(generatePublicKey())), "RSA")
        trustManager.checkServerTrusted(emptyArray(), "RSA")
        trustManager.checkServerTrusted(arrayOf(fakeCertificate(generatePublicKey())), null)
    }

    @Test
    fun custom_policy_usesProvidedTrustManagerFactory() {
        val customTrustManager = object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: java.net.Socket?,
            ) = Unit

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: javax.net.ssl.SSLEngine?,
            ) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: java.net.Socket?,
            ) = Unit

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: javax.net.ssl.SSLEngine?,
            ) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val trustManager =
            AdbTlsTrustPolicy.Custom { _ -> customTrustManager }
                .resolveTrustManager(target = AdbNetworkEndpoint(host = "192.168.0.10", port = 37099))

        assertSame(customTrustManager, trustManager)
    }

    private fun generatePublicKey(): PublicKey =
        KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair().public

    private fun fakeCertificate(publicKey: PublicKey): X509Certificate =
        object : X509Certificate() {
            override fun getPublicKey(): PublicKey = publicKey

            override fun checkValidity() = Unit

            override fun checkValidity(date: Date?) = Unit

            override fun getVersion(): Int = 3

            override fun getSerialNumber(): BigInteger = BigInteger.ONE

            override fun getIssuerDN(): Principal = Principal { "CN=test" }

            override fun getSubjectDN(): Principal = Principal { "CN=test" }

            override fun getNotBefore(): Date = Date(0)

            override fun getNotAfter(): Date = Date(Long.MAX_VALUE)

            override fun getTBSCertificate(): ByteArray = byteArrayOf()

            override fun getSignature(): ByteArray = byteArrayOf()

            override fun getSigAlgName(): String = "NONE"

            override fun getSigAlgOID(): String = "0.0"

            override fun getSigAlgParams(): ByteArray? = null

            override fun getIssuerUniqueID(): BooleanArray? = null

            override fun getSubjectUniqueID(): BooleanArray? = null

            override fun getKeyUsage(): BooleanArray? = null

            override fun getBasicConstraints(): Int = -1

            override fun getEncoded(): ByteArray = throw CertificateEncodingException("Not implemented in test certificate")

            override fun verify(key: PublicKey?) = Unit

            override fun verify(key: PublicKey?, sigProvider: String?) = Unit

            override fun toString(): String = "FakeX509Certificate"

            override fun hasUnsupportedCriticalExtension(): Boolean = false

            override fun getCriticalExtensionOIDs(): MutableSet<String>? = null

            override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null

            override fun getExtensionValue(oid: String?): ByteArray? = null
        }
}
