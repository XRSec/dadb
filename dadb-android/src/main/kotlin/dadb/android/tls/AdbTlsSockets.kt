package dadb.android.tls

import javax.net.ssl.SSLSocket

internal object AdbTlsSockets {
    private const val TLS_V1_3 = "TLSv1.3"

    fun configureClientSocket(
        sslSocket: SSLSocket,
        socketTimeout: Int,
        configureSocket: (SSLSocket) -> Unit = {},
    ) {
        sslSocket.useClientMode = true
        sslSocket.soTimeout = socketTimeout
        sslSocket.enabledProtocols = requiredClientProtocols(sslSocket.supportedProtocols)
        configureSocket(sslSocket)
    }

    internal fun requiredClientProtocols(supportedProtocols: Array<String>): Array<String> {
        check(supportedProtocols.contains(TLS_V1_3)) {
            "Wireless Debugging TLS requires TLSv1.3, but the current runtime only supports: ${supportedProtocols.joinToString()}"
        }
        return arrayOf(TLS_V1_3)
    }
}
