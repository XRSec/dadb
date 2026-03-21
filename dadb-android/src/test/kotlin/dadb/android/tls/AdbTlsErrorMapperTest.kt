package dadb.android.tls

import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbTlsErrorMapperTest {
    @Test
    fun map_authRelatedHandshake_returnsTlsAuthException() {
        val error = AdbTlsErrorMapper.map(SSLHandshakeException("certificate_required"))

        assertTrue(error is AdbTlsAuthException)
    }

    @Test
    fun map_timeout_returnsHandshakeTimeoutIoException() {
        val timeout = SocketTimeoutException("read timed out")
        val error = AdbTlsErrorMapper.map(IOException("handshake failed", timeout))

        assertTrue(error is IOException)
        assertEquals("TLS handshake timeout", error.message)
    }

    @Test
    fun map_nonTlsFailure_returnsOriginalThrowable() {
        val error = IOException("plain failure")

        assertSame(error, AdbTlsErrorMapper.map(error))
    }
}
