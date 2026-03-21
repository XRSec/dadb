package dadb.android.tls

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

object AdbTlsCertificatePins {
    fun publicKeySha256Base64(certificate: X509Certificate): String = sha256Base64(certificate.publicKey.encoded)

    fun sha256Base64(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return Base64.getEncoder().encodeToString(digest)
    }
}
