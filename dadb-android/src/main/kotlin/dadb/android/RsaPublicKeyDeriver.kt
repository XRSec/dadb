package dadb.android

import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import org.bouncycastle.asn1.pkcs.RSAPrivateKey as BcRsaPrivateKey
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo

internal object RsaPublicKeyDeriver {
    fun derive(privateKey: PrivateKey): RSAPublicKey {
        val rsaPrivateKey =
            privateKey as? RSAPrivateKey
                ?: throw IllegalStateException("Expected RSA private key")

        val rsaCrtPrivateKey = rsaPrivateKey as? RSAPrivateCrtKey
        if (rsaCrtPrivateKey != null) {
            return generate(rsaCrtPrivateKey.modulus, rsaCrtPrivateKey.publicExponent)
        }

        val encoded =
            rsaPrivateKey.encoded
                ?: throw IllegalStateException("RSA private key encoding is unavailable")

        val privateKeyInfo =
            runCatching {
                PrivateKeyInfo.getInstance(encoded)
            }.getOrElse { error ->
                throw IllegalStateException("Failed to parse RSA private key encoding", error)
            }

        val encodedRsaPrivateKey =
            runCatching {
                BcRsaPrivateKey.getInstance(privateKeyInfo.parsePrivateKey())
            }.getOrElse { error ->
                throw IllegalStateException("Failed to parse RSA private key parameters", error)
            }

        return generate(encodedRsaPrivateKey.modulus, encodedRsaPrivateKey.publicExponent)
    }

    private fun generate(
        modulus: BigInteger,
        publicExponent: BigInteger,
    ): RSAPublicKey {
        val spec = RSAPublicKeySpec(modulus, publicExponent)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }
}
