package dadb.android.tls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbTlsSocketsTest {
    @Test
    fun requiredClientProtocols_returnsTls13Only() {
        val protocols = AdbTlsSockets.requiredClientProtocols(arrayOf("TLSv1.2", "TLSv1.3"))

        assertArrayEquals(arrayOf("TLSv1.3"), protocols)
    }

    @Test
    fun requiredClientProtocols_failsWithoutTls13() {
        val error =
            try {
                AdbTlsSockets.requiredClientProtocols(arrayOf("TLSv1.2"))
                null
            } catch (t: IllegalStateException) {
                t
            }

        requireNotNull(error)
        assertEquals(
            "Wireless Debugging TLS requires TLSv1.3, but the current runtime only supports: TLSv1.2",
            error.message,
        )
    }
}
