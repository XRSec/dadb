package dadb.android

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.assertEquals
import org.junit.Test

class RsaPublicKeyDeriverTest {
    @Test
    fun derive_supportsNonCrtRsaPrivateKey() {
        val keyPair =
            KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048)
            }.generateKeyPair()
        val expectedPublicKey = keyPair.public as RSAPublicKey
        val nonCrtPrivateKey = NonCrtRsaPrivateKey(keyPair.private as RSAPrivateKey)

        val derivedPublicKey = RsaPublicKeyDeriver.derive(nonCrtPrivateKey)

        assertEquals(expectedPublicKey.modulus, derivedPublicKey.modulus)
        assertEquals(expectedPublicKey.publicExponent, derivedPublicKey.publicExponent)
    }

    private class NonCrtRsaPrivateKey(
        private val delegate: RSAPrivateKey,
    ) : RSAPrivateKey {
        override fun getAlgorithm(): String = delegate.algorithm

        override fun getFormat(): String = delegate.format

        override fun getEncoded(): ByteArray = delegate.encoded

        override fun getModulus(): BigInteger = delegate.modulus

        override fun getPrivateExponent(): BigInteger = delegate.privateExponent
    }
}
