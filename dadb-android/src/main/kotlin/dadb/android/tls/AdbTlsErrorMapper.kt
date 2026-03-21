package dadb.android.tls

import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.channels.InterruptedByTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException

internal object AdbTlsErrorMapper {
    fun map(throwable: Throwable): Throwable {
        val messages =
            buildString {
                generateSequence(throwable) { it.cause }.forEach { cause ->
                    cause.message?.let {
                        append(it.lowercase())
                        append('\n')
                    }
                }
            }

        if (
            messages.contains("certificate_required") ||
            messages.contains("unknown_ca") ||
            messages.contains("access_denied") ||
            messages.contains("certificate_unknown")
        ) {
            return AdbTlsAuthException()
        }

        if (generateSequence(throwable) { it.cause }.any { it is InterruptedByTimeoutException || it is SocketTimeoutException }) {
            return IOException("TLS handshake timeout", throwable)
        }

        if (
            generateSequence(throwable) { it.cause }.any {
                it is SSLHandshakeException || it is SSLProtocolException || it is SSLException
            }
        ) {
            val message = throwable.message ?: ""
            return IOException("TLS handshake failed${if (message.isNotEmpty()) ": $message" else ""}", throwable)
        }

        return throwable
    }
}
