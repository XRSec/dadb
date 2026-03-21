package dadb.forwarding

import dadb.Dadb
import okio.buffer
import okio.sink
import okio.source
import java.net.InetSocketAddress
import java.net.ServerSocket

internal class TcpForwarder(
    dadb: Dadb,
    private val hostPort: Int,
    remoteDestination: String,
    private val bindHost: String? = null,
) : BaseForwarder(
    dadb = dadb,
    remoteDestination = remoteDestination,
    endpointDescription = "port $hostPort",
    forwardingType = "TCP",
) {

    override fun createServer(): ForwardingServer = TcpServerAdapter(bindHost, hostPort)

    private class TcpServerAdapter(
        bindHost: String?,
        port: Int,
    ) : ForwardingServer {
        private val delegate =
            if (bindHost == null) {
                ServerSocket(port)
            } else {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindHost, port))
                }
            }

        override fun accept(): ForwardingClient = TcpClientAdapter(delegate.accept())

        override fun close() {
            delegate.close()
        }
    }

    private class TcpClientAdapter(
        private val delegate: java.net.Socket,
    ) : ForwardingClient {
        override val source = delegate.getInputStream().source()
        override val sink = delegate.sink().buffer()

        override fun close() {
            delegate.close()
        }
    }
}
