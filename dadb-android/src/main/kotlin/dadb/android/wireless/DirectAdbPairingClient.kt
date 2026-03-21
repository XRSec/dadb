package dadb.android.wireless

import android.util.Log
import dadb.android.tls.AdbTlsCertificatePins
import dadb.android.tls.AdbTlsErrorMapper
import dadb.android.tls.AdbTlsSockets
import org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

private const val TAG = "DirectAdbPairing"

internal const val CURRENT_KEY_HEADER_VERSION = 1.toByte()
internal const val MIN_SUPPORTED_KEY_HEADER_VERSION = 1.toByte()
internal const val MAX_SUPPORTED_KEY_HEADER_VERSION = 1.toByte()
internal const val MAX_PEER_INFO_SIZE = 8192
internal const val MAX_PAYLOAD_SIZE = MAX_PEER_INFO_SIZE * 2

internal const val EXPORTED_KEY_LABEL = "adb-label\u0000"
internal const val EXPORTED_KEY_SIZE = 64
internal const val PAIRING_PACKET_HEADER_SIZE = 6

internal class PairingPeerInfo(
    val type: Byte,
    rawData: ByteArray,
) {
    val data = ByteArray(MAX_PEER_INFO_SIZE - 1)

    init {
        rawData.copyInto(data, endIndex = rawData.size.coerceAtMost(MAX_PEER_INFO_SIZE - 1))
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.put(type)
        buffer.put(data)
    }

    companion object {
        const val ADB_RSA_PUB_KEY: Byte = 0

        fun readFrom(buffer: ByteBuffer): PairingPeerInfo {
            val type = buffer.get()
            val data = ByteArray(MAX_PEER_INFO_SIZE - 1)
            buffer.get(data)
            return PairingPeerInfo(type, data)
        }
    }
}

internal class PairingPacketHeader(
    val version: Byte,
    val type: Byte,
    val payload: Int,
) {
    object Type {
        const val SPAKE2_MSG: Byte = 0
        const val PEER_INFO: Byte = 1
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.put(version)
        buffer.put(type)
        buffer.putInt(payload)
    }

    companion object {
        fun readFrom(
            buffer: ByteBuffer,
            logError: (String) -> Unit = { Log.e(TAG, it) },
        ): PairingPacketHeader? {
            val version = buffer.get()
            val type = buffer.get()
            val payload = buffer.int

            if (version !in MIN_SUPPORTED_KEY_HEADER_VERSION..MAX_SUPPORTED_KEY_HEADER_VERSION) {
                logError("header version mismatch: $version")
                return null
            }
            if (type != Type.SPAKE2_MSG && type != Type.PEER_INFO) {
                logError("unknown packet type: $type")
                return null
            }
            if (payload !in 1..MAX_PAYLOAD_SIZE) {
                logError("unsafe payload size: $payload")
                return null
            }
            return PairingPacketHeader(version, type, payload)
        }
    }
}

internal enum class PairingClientState {
    READY,
    EXCHANGING_MSGS,
    EXCHANGING_PEER_INFO,
    STOPPED,
}

internal object PairingClientStateMachine {
    fun start(state: PairingClientState): PairingClientState {
        check(state == PairingClientState.READY) { "Pairing client can only start from READY, was $state" }
        return PairingClientState.EXCHANGING_MSGS
    }

    fun afterMessageExchange(
        state: PairingClientState,
        success: Boolean,
    ): PairingClientState {
        check(state == PairingClientState.EXCHANGING_MSGS) {
            "Message exchange can only complete from EXCHANGING_MSGS, was $state"
        }
        return if (success) PairingClientState.EXCHANGING_PEER_INFO else PairingClientState.STOPPED
    }

    fun afterPeerInfoExchange(
        state: PairingClientState,
        success: Boolean,
    ): PairingClientState {
        check(state == PairingClientState.EXCHANGING_PEER_INFO) {
            "Peer info exchange can only complete from EXCHANGING_PEER_INFO, was $state"
        }
        return PairingClientState.STOPPED
    }
}

internal fun decodePeerAdbPublicKey(
    decrypted: ByteArray?,
    onInvalidSize: (Int) -> Unit = {},
): ByteArray? {
    val payload = decrypted ?: throw AdbInvalidPairingCodeException()
    if (payload.size != MAX_PEER_INFO_SIZE) {
        onInvalidSize(payload.size)
        return null
    }
    val remotePeerInfo = PairingPeerInfo.readFrom(ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN))
    return remotePeerInfo.data.copyUntilZeroByte()
}

internal fun ByteArray.copyUntilZeroByte(): ByteArray {
    var end = indexOf(0)
    if (end < 0) {
        end = size
    }
    return copyOf(end)
}

internal class PairingContext private constructor(
    private val nativePtr: Long,
) {
    val msg: ByteArray = nativeMsg(nativePtr)

    fun initCipher(theirMsg: ByteArray): Boolean = nativeInitCipher(nativePtr, theirMsg)

    fun encrypt(input: ByteArray): ByteArray? = nativeEncrypt(nativePtr, input)

    fun decrypt(input: ByteArray): ByteArray? = nativeDecrypt(nativePtr, input)

    fun destroy() {
        nativeDestroy(nativePtr)
    }

    private external fun nativeMsg(nativePtr: Long): ByteArray

    private external fun nativeInitCipher(
        nativePtr: Long,
        theirMsg: ByteArray,
    ): Boolean

    private external fun nativeEncrypt(
        nativePtr: Long,
        input: ByteArray,
    ): ByteArray?

    private external fun nativeDecrypt(
        nativePtr: Long,
        input: ByteArray,
    ): ByteArray?

    private external fun nativeDestroy(nativePtr: Long)

    companion object {
        init {
            System.loadLibrary("adbpairing")
        }

        fun create(password: ByteArray): PairingContext? {
            val ptr = nativeConstructor(true, password)
            return if (ptr != 0L) PairingContext(ptr) else null
        }

        @JvmStatic
        private external fun nativeConstructor(
            isClient: Boolean,
            password: ByteArray,
        ): Long
    }
}

internal class DirectAdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairingCode: String,
    private val key: AdbPairingKey,
) : Closeable {
    private lateinit var socket: Socket
    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream

    private val peerInfo = PairingPeerInfo(PairingPeerInfo.ADB_RSA_PUB_KEY, key.adbPublicKey)
    private lateinit var pairingContext: PairingContext
    private var state: PairingClientState = PairingClientState.READY
    private var pairingTlsPublicKeySha256Base64: String? = null

    fun start(): PairingSessionMetadata? {
        setupTlsConnection()
        state = PairingClientStateMachine.start(state)
        val exchangedMsgs = doExchangeMsgs()
        state = PairingClientStateMachine.afterMessageExchange(state, exchangedMsgs)
        if (!exchangedMsgs) {
            return null
        }

        val peerMetadata = doExchangePeerInfo()
        state = PairingClientStateMachine.afterPeerInfoExchange(state, peerMetadata != null)
        if (peerMetadata == null) {
            return null
        }

        return PairingSessionMetadata(
            peerAdbPublicKey = peerMetadata.adbPublicKey,
            pairingTlsPublicKeySha256Base64 = pairingTlsPublicKeySha256Base64,
        )
    }

    private fun setupTlsConnection() {
        socket = Socket(host, port)
        socket.tcpNoDelay = true

        val tlsSocket =
            key.sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        try {
            AdbTlsSockets.configureClientSocket(tlsSocket, socket.soTimeout)
            tlsSocket.startHandshake()
        } catch (t: Throwable) {
            runCatching { tlsSocket.close() }
            throw AdbTlsErrorMapper.map(t)
        }
        pairingTlsPublicKeySha256Base64 = tlsSocket.peerLeafCertificate()?.let(AdbTlsCertificatePins::publicKeySha256Base64)

        inputStream = DataInputStream(tlsSocket.inputStream)
        outputStream = DataOutputStream(tlsSocket.outputStream)

        val codeBytes = pairingCode.toByteArray(Charsets.UTF_8)
        val keyMaterial = exportTlsKeyingMaterial(tlsSocket)
        val password = ByteArray(codeBytes.size + keyMaterial.size)
        codeBytes.copyInto(password)
        keyMaterial.copyInto(password, destinationOffset = codeBytes.size)

        pairingContext =
            checkNotNull(PairingContext.create(password)) {
                "Unable to create pairing context"
            }
    }

    private fun exportTlsKeyingMaterial(sslSocket: SSLSocket): ByteArray {
        if (Conscrypt.isConscrypt(sslSocket)) {
            return Conscrypt.exportKeyingMaterial(
                sslSocket,
                EXPORTED_KEY_LABEL,
                null,
                EXPORTED_KEY_SIZE,
            )
        }

        throw IllegalStateException(
            "TLS socket is not backed by bundled Conscrypt: ${sslSocket.javaClass.name}",
        )
    }

    private fun createHeader(
        type: Byte,
        payloadSize: Int,
    ): PairingPacketHeader = PairingPacketHeader(CURRENT_KEY_HEADER_VERSION, type, payloadSize)

    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(PAIRING_PACKET_HEADER_SIZE)
        inputStream.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return PairingPacketHeader.readFrom(buffer)
    }

    private fun writeHeader(
        header: PairingPacketHeader,
        payload: ByteArray,
    ) {
        val buffer = ByteBuffer.allocate(PAIRING_PACKET_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)
        outputStream.write(buffer.array())
        outputStream.write(payload)
    }

    private fun doExchangeMsgs(): Boolean {
        val msg = pairingContext.msg
        writeHeader(createHeader(PairingPacketHeader.Type.SPAKE2_MSG, msg.size), msg)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.SPAKE2_MSG) return false

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)
        return pairingContext.initCipher(theirMessage)
    }

    private fun doExchangePeerInfo(): PairingPeerMetadata? {
        val plain = ByteBuffer.allocate(MAX_PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)
        peerInfo.writeTo(plain)

        val encrypted = pairingContext.encrypt(plain.array()) ?: return null
        writeHeader(createHeader(PairingPacketHeader.Type.PEER_INFO, encrypted.size), encrypted)

        val theirHeader = readHeader() ?: return null
        if (theirHeader.type != PairingPacketHeader.Type.PEER_INFO) return null

        val peerMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(peerMessage)

        val adbPublicKey =
            decodePeerAdbPublicKey(
                decrypted = pairingContext.decrypt(peerMessage),
                onInvalidSize = { size -> Log.e(TAG, "invalid peer info size: $size") },
            ) ?: return null
        return PairingPeerMetadata(
            adbPublicKey = adbPublicKey,
        )
    }

    override fun close() {
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        runCatching { socket.close() }
        if (state != PairingClientState.READY) {
            runCatching { pairingContext.destroy() }
        }
    }
}

private fun SSLSocket.peerLeafCertificate(): X509Certificate? =
    session.peerCertificates.firstOrNull() as? X509Certificate

internal data class PairingSessionMetadata(
    val peerAdbPublicKey: ByteArray,
    val pairingTlsPublicKeySha256Base64: String?,
)

private data class PairingPeerMetadata(
    val adbPublicKey: ByteArray,
)
