package dadb.android.tls

import dadb.AdbKeyPair
import dadb.AdbTransport
import dadb.AdbTransportFactory
import dadb.TlsUpgradableAdbTransport
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedTrustManager
import okio.Sink
import okio.Source
import okio.sink
import okio.source

/**
 * STLS-aware ADB transport for Wireless Debugging.
 *
 * The connection starts as a normal TCP socket. If the device responds with `A_STLS`, dadb core
 * will request the upgrade and then call [TlsUpgradableAdbTransport.upgradeToTls] on this
 * transport to wrap the same socket in TLS before continuing the normal ADB handshake.
 *
 * The convenience constructor that only takes an [AdbKeyPair] uses an insecure trust policy so it
 * can interoperate with self-signed Wireless Debugging peers. Callers that have a stricter trust
 * strategy should provide a custom [SSLSocketFactory] or [X509ExtendedTrustManager].
 */
class TlsAdbTransportFactory(
    private val host: String,
    private val port: Int,
    private val socketFactory: SSLSocketFactory,
    private val connectTimeout: Int = 0,
    private val socketTimeout: Int = 0,
    private val configureSocket: (SSLSocket) -> Unit = {},
    private val onHandshakeCompleted: (SSLSocket) -> Unit = {},
) : AdbTransportFactory {
    constructor(
        host: String,
        port: Int,
        keyPair: AdbKeyPair,
        connectTimeout: Int = 0,
        socketTimeout: Int = 0,
        configureSocket: (SSLSocket) -> Unit = {},
        onHandshakeCompleted: (SSLSocket) -> Unit = {},
    ) : this(
        host = host,
        port = port,
        socketFactory = AdbTlsContexts.createUnsafe(keyPair).socketFactory,
        connectTimeout = connectTimeout,
        socketTimeout = socketTimeout,
        configureSocket = configureSocket,
        onHandshakeCompleted = onHandshakeCompleted,
    )

    constructor(
        host: String,
        port: Int,
        keyPair: AdbKeyPair,
        trustManager: X509ExtendedTrustManager,
        connectTimeout: Int = 0,
        socketTimeout: Int = 0,
        configureSocket: (SSLSocket) -> Unit = {},
        onHandshakeCompleted: (SSLSocket) -> Unit = {},
    ) : this(
        host = host,
        port = port,
        socketFactory = AdbTlsContexts.create(keyPair, trustManager).socketFactory,
        connectTimeout = connectTimeout,
        socketTimeout = socketTimeout,
        configureSocket = configureSocket,
        onHandshakeCompleted = onHandshakeCompleted,
    )

    override val description: String = "$host:$port"

    override fun connect(): AdbTransport {
        val socket = Socket()
        socket.soTimeout = socketTimeout
        socket.connect(InetSocketAddress(host, port), connectTimeout)
        return UpgradableTlsSocketAdbTransport(
            socket = socket,
            host = host,
            port = port,
            socketFactory = socketFactory,
            socketTimeout = socketTimeout,
            description = description,
            configureSocket = configureSocket,
            onHandshakeCompleted = onHandshakeCompleted,
        )
    }
}

private class UpgradableTlsSocketAdbTransport(
    private val socket: Socket,
    private val host: String,
    private val port: Int,
    private val socketFactory: SSLSocketFactory,
    private val socketTimeout: Int,
    override val description: String,
    private val configureSocket: (SSLSocket) -> Unit,
    private val onHandshakeCompleted: (SSLSocket) -> Unit,
) : TlsUpgradableAdbTransport {
    companion object {
        private const val STLS_VERSION = 0x01000000
    }

    private val closed = AtomicBoolean(false)
    private var currentSocket: Socket = socket
    private var currentSource: Source = socket.source()
    private var currentSink: Sink = socket.sink()

    override val source: Source
        get() = currentSource

    override val sink: Sink
        get() = currentSink

    override val isClosed: Boolean
        get() = closed.get() || currentSocket.isClosed

    override fun upgradeToTls(version: Int) {
        check(version >= STLS_VERSION) { "Unsupported STLS version: $version" }
        val sslSocket =
            socketFactory.createSocket(currentSocket, host, port, true) as SSLSocket

        try {
            sslSocket.useClientMode = true
            sslSocket.soTimeout = socketTimeout
            configureSocket(sslSocket)
            sslSocket.startHandshake()
            onHandshakeCompleted(sslSocket)
            currentSocket = sslSocket
            currentSource = sslSocket.source()
            currentSink = sslSocket.sink()
        } catch (t: Throwable) {
            runCatching { sslSocket.close() }
            throw t
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        currentSocket.close()
    }
}
