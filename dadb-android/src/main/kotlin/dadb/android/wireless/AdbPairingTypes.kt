package dadb.android.wireless

import dadb.AdbKeyPair
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
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

internal class AdbPairingKey(
    private val adbKeyPair: AdbKeyPair,
) {
    private val keyAlias = adbKeyPair.adbPublicKey().decodeAdbKeyAlias()

    private val rsaPrivateKey: RSAPrivateKey =
        adbKeyPair.privateKey() as? RSAPrivateKey
            ?: throw IllegalStateException("Expected RSA private key")

    private val rsaPublicKey: RSAPublicKey by lazy {
        val keyFactory = KeyFactory.getInstance("RSA")
        keyFactory.generatePublic(
            RSAPublicKeySpec(rsaPrivateKey.modulus, BigInteger.valueOf(65537L)),
        ) as RSAPublicKey
    }

    private val certificate: X509Certificate by lazy {
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
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
    }

    val adbPublicKey: ByteArray by lazy { rsaPublicKey.toAdbEncoded(keyAlias) }

    val sslContext: SSLContext by lazy {
        val conscryptProvider: Provider = Conscrypt.newProviderBuilder().build()
        if (Security.getProvider(conscryptProvider.name) == null) {
            Security.insertProviderAt(conscryptProvider, 1)
        }
        SSLContext.getInstance("TLSv1.3", conscryptProvider).apply {
            init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        }
    }

    private val keyManager: X509ExtendedKeyManager
        get() = object : X509ExtendedKeyManager() {
            private val keyAlias = "adbkey"

            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out java.security.Principal>?,
                socket: Socket?,
            ): String = keyAlias

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == keyAlias) arrayOf(certificate) else null

            override fun getPrivateKey(alias: String?) =
                if (alias == keyAlias) rsaPrivateKey else null

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

    @get:Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustManager: X509ExtendedTrustManager
        get() = object : X509ExtendedTrustManager() {
            private fun accept(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) {
                if (chain.isNullOrEmpty()) return
                if (authType.isNullOrBlank()) return
            }

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: Socket?,
            ) = accept(chain, authType)

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: SSLEngine?,
            ) = accept(chain, authType)

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) = accept(chain, authType)

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: Socket?,
            ) = accept(chain, authType)

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: SSLEngine?,
            ) = accept(chain, authType)

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) = accept(chain, authType)

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
}

private const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8
private const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
private const val RSA_PUBLIC_KEY_SIZE = 524
private const val DEFAULT_ADB_KEY_ALIAS = "unknown@unknown"

private fun ByteArray.decodeAdbKeyAlias(): String {
    val text =
        toString(Charsets.UTF_8)
            .trimEnd('\u0000')
            .trim()
    val separator = text.indexOf(' ')
    if (separator < 0 || separator == text.lastIndex) {
        return DEFAULT_ADB_KEY_ALIAS
    }
    return text.substring(separator + 1).trim().ifBlank { DEFAULT_ADB_KEY_ALIAS }
}

private fun BigInteger.toAdbEncodedWords(): IntArray {
    val encoded = IntArray(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    val r32 = BigInteger.ZERO.setBit(32)
    var remaining = this
    for (index in 0 until ANDROID_PUBKEY_MODULUS_SIZE_WORDS) {
        val parts = remaining.divideAndRemainder(r32)
        remaining = parts[0]
        encoded[index] = parts[1].toInt()
    }
    return encoded
}

private fun RSAPublicKey.toAdbEncoded(alias: String): ByteArray {
    val r32 = BigInteger.ZERO.setBit(32)
    val n0inv = modulus.remainder(r32).modInverse(r32).negate()
    val r = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
    val rr = r.modPow(BigInteger.valueOf(2), modulus)

    val buffer = ByteBuffer.allocate(RSA_PUBLIC_KEY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    buffer.putInt(n0inv.toInt())
    modulus.toAdbEncodedWords().forEach { buffer.putInt(it) }
    rr.toAdbEncodedWords().forEach { buffer.putInt(it) }
    buffer.putInt(publicExponent.toInt())

    val base64 = android.util.Base64.encode(buffer.array(), android.util.Base64.NO_WRAP)
    val suffix = " $alias\u0000".toByteArray(Charsets.UTF_8)
    return ByteArray(base64.size + suffix.size).also {
        base64.copyInto(it)
        suffix.copyInto(it, base64.size)
    }
}

class AdbInvalidPairingCodeException : Exception()
