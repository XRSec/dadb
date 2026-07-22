/*
 * Copyright (c) 2021 mobile.dev inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package dadb

import dadb.forwarding.StreamForwarder
import okio.Sink
import okio.Source
import okio.buffer
import okio.sink
import okio.source
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal class AdbConnection internal constructor(
        adbReader: AdbReader,
        private val adbWriter: AdbWriter,
        private val closeable: AutoCloseable?,
        private val supportedFeatures: Set<String>,
        private val delayedAckEnabled: Boolean,
        private val maxPayloadSize: Int,
        private val tlsUpgraded: Boolean,
) : AutoCloseable {

    private val nextLocalId = AtomicInteger(0)
    private val messageQueue = AdbMessageQueue(adbReader)
    private val reverseSessionThreads = ConcurrentLinkedQueue<Thread>()

    @Volatile
    private var reverseThread: Thread? = null

    @Volatile
    private var closed = false

    init {
        startReverseBridgeLoop()
    }

    @Throws(AdbException::class)
    fun open(destination: String): AdbStream {
        val localId = newId()
        messageQueue.startListening(localId)
        try {
            val initialReceiveWindow = if (delayedAckEnabled) Constants.INITIAL_DELAYED_ACK_BYTES else 0
            adbWriter.writeOpen(localId, destination, initialReceiveWindow)
            val message = messageQueue.take(localId, Constants.CMD_OKAY)
            val remoteId = message.arg0
            val initialAvailableSendBytes = Constants.decodeOkayAckBytes(message, delayedAckEnabled).toLong()
            return AdbStreamImpl(
                messageQueue = messageQueue,
                adbWriter = adbWriter,
                maxPayloadSize = maxPayloadSize,
                localId = localId,
                remoteId = remoteId,
                delayedAckEnabled = delayedAckEnabled,
                initialAvailableSendBytes = initialAvailableSendBytes,
            )
        } catch (e: AdbStreamClosed) {
            messageQueue.stopListening(localId)
            throw AdbStreamOpenException(destination, "adbd refused to open stream: $destination", e)
        } catch (e: AdbException) {
            messageQueue.stopListening(localId)
            throw e
        } catch (e: IOException) {
            messageQueue.stopListening(localId)
            throw if (e is SocketTimeoutException) {
                AdbTimeoutException("Timed out opening stream: $destination", e)
            } else {
                AdbConnectionClosedException("Connection lost while opening stream: $destination", e)
            }
        } catch (e: Throwable) {
            messageQueue.stopListening(localId)
            throw e
        }
    }

    fun supportsFeature(feature: String): Boolean {
        return supportedFeatures.contains(feature)
    }

    fun isTlsConnection(): Boolean {
        return tlsUpgraded
    }

    private fun newId(): Int {
        return nextLocalId.incrementAndGet()
    }

    @TestOnly
    internal fun ensureEmpty() {
        messageQueue.ensureEmpty()
    }

    override fun close() {
        closed = true
        try {
            reverseThread?.interrupt()
            reverseThread = null
            while (true) {
                val thread = reverseSessionThreads.poll() ?: break
                thread.interrupt()
            }
            runCatching { messageQueue.stopListening(REVERSE_LISTENER_ID) }
            messageQueue.close()
            adbWriter.close()
            closeable?.close()
        } catch (ignore: Throwable) {}
    }

    private fun startReverseBridgeLoop() {
        messageQueue.startListening(REVERSE_LISTENER_ID)
        reverseThread = thread(name = "dadb-reverse-accept") {
            while (!Thread.currentThread().isInterrupted && !closed) {
                try {
                    val openMessage = messageQueue.take(REVERSE_LISTENER_ID, Constants.CMD_OPEN)
                    handleIncomingReverseOpen(openMessage)
                } catch (_: InterruptedException) {
                    return@thread
                } catch (_: AdbStreamClosed) {
                    if (closed) {
                        return@thread
                    }
                    messageQueue.startListening(REVERSE_LISTENER_ID)
                } catch (t: Throwable) {
                    if (!closed && !Thread.currentThread().isInterrupted) {
                        log { "reverse bridge loop error: ${t.message}" }
                    }
                }
            }
        }
    }

    private fun handleIncomingReverseOpen(message: AdbMessage) {
        val destination = extractOpenDestination(message) ?: run {
            adbWriter.writeClose(REVERSE_LISTENER_ID, message.arg0)
            return
        }

        val target = parseReverseTcpTarget(destination) ?: run {
            adbWriter.writeClose(REVERSE_LISTENER_ID, message.arg0)
            return
        }

        val localSocket = runCatching { Socket(target.host, target.port) }.getOrElse {
            adbWriter.writeClose(REVERSE_LISTENER_ID, message.arg0)
            return
        }

        val localId = newId()
        messageQueue.startListening(localId)
        val initialReceiveWindow = if (delayedAckEnabled) Constants.INITIAL_DELAYED_ACK_BYTES else null
        adbWriter.writeOkay(localId, message.arg0, initialReceiveWindow)

        val initialAvailableSendBytes = if (delayedAckEnabled) {
            message.arg1.toLong().coerceAtLeast(0)
        } else {
            0L
        }
        val adbStream = AdbStreamImpl(
            messageQueue = messageQueue,
            adbWriter = adbWriter,
            maxPayloadSize = maxPayloadSize,
            localId = localId,
            remoteId = message.arg0,
            delayedAckEnabled = delayedAckEnabled,
            initialAvailableSendBytes = initialAvailableSendBytes,
        )
        val socketSource = localSocket.getInputStream().source()
        val socketSink = localSocket.getOutputStream().sink().buffer()

        val localToDevice = thread(name = "dadb-reverse-local-to-device") {
            try {
                StreamForwarder.transfer(socketSource, adbStream.sink)
            } finally {
                reverseSessionThreads.remove(Thread.currentThread())
            }
        }
        reverseSessionThreads.add(localToDevice)

        val deviceToLocal = thread(name = "dadb-reverse-device-to-local") {
            try {
                StreamForwarder.transfer(adbStream.source, socketSink)
            } finally {
                runCatching { adbStream.close() }
                runCatching { localSocket.close() }
                localToDevice.interrupt()
                reverseSessionThreads.remove(Thread.currentThread())
            }
        }
        reverseSessionThreads.add(deviceToLocal)
    }

    private fun extractOpenDestination(message: AdbMessage): String? {
        if (message.payloadLength <= 0) {
            return null
        }

        val endExclusive =
            if (message.payload[message.payloadLength - 1].toInt() == 0) {
                message.payloadLength - 1
            } else {
                message.payloadLength
            }

        if (endExclusive <= 0) {
            return null
        }

        return String(message.payload, 0, endExclusive)
    }
    companion object {
        private const val REVERSE_LISTENER_ID = 0

        fun connect(socket: Socket, keyPair: AdbKeyPair? = null): AdbConnection {
            val source = socket.source()
            val sink = socket.sink()
            return connect(source, sink, keyPair, socket)
        }

        fun connect(transport: AdbTransport, keyPair: AdbKeyPair? = null, features: Set<String> = Constants.CONNECT_FEATURES.toSet()): AdbConnection {
            return connect(
                transport.source,
                transport.sink,
                keyPair,
                closeable = transport,
                connectMaxData = transport.connectMaxData,
                tlsUpgradableTransport = transport as? TlsUpgradableAdbTransport,
                features = features,
            )
        }

        internal fun connect(
            source: Source,
            sink: Sink,
            keyPair: AdbKeyPair? = null,
            closeable: AutoCloseable? = null,
            connectMaxData: Int = Constants.CONNECT_MAXDATA,
            tlsUpgradableTransport: TlsUpgradableAdbTransport? = null,
            features: Set<String> = Constants.CONNECT_FEATURES.toSet(),
        ): AdbConnection {
            val adbReader = AdbReader(source, maxPayloadSize = Constants.MAX_PAYLOAD_V1)
            val adbWriter = AdbWriter(sink)

            try {
                return connect(adbReader, adbWriter, keyPair, closeable, connectMaxData, tlsUpgradableTransport, features)
            } catch (e: AdbException) {
                adbReader.close()
                adbWriter.close()
                throw e
            } catch (e: IOException) {
                adbReader.close()
                adbWriter.close()
                throw AdbConnectException("Connection handshake failed", e)
            } catch (t: Throwable) {
                adbReader.close()
                adbWriter.close()
                throw t
            }
        }

        private fun connect(
            adbReader: AdbReader,
            adbWriter: AdbWriter,
            keyPair: AdbKeyPair?,
            closeable: AutoCloseable?,
            connectMaxData: Int,
            tlsUpgradableTransport: TlsUpgradableAdbTransport?,
            features: Set<String> = Constants.CONNECT_FEATURES.toSet(),
        ): AdbConnection {
            var currentReader = adbReader
            var currentWriter = adbWriter
            var tlsUpgraded = false
            val connectPayload = "host::features=${features.joinToString(",")}".toByteArray()
            currentWriter.writeConnect(connectMaxData, connectPayload)

            var message = currentReader.readMessage()

            if (message.command == Constants.CMD_STLS) {
                if (tlsUpgradableTransport == null) {
                    throw AdbProtocolException("TLS upgrade requested by device, but transport does not support STLS/TLS upgrade")
                }
                currentWriter.writeStls(message.arg0)
                tlsUpgradableTransport.upgradeToTls(message.arg0)
                tlsUpgraded = true
                currentReader = AdbReader(tlsUpgradableTransport.source, maxPayloadSize = Constants.MAX_PAYLOAD_V1)
                currentWriter = AdbWriter(tlsUpgradableTransport.sink)
                message = currentReader.readMessage()
            }

            if (message.command == Constants.CMD_AUTH) {
                if (keyPair == null) {
                    throw AdbAuthException("Authentication required but no key pair was provided")
                }
                if (message.arg0 != Constants.AUTH_TYPE_TOKEN) {
                    throw AdbProtocolException("Unsupported auth type: $message")
                }

                val signature = keyPair.signPayload(message)
                currentWriter.writeAuth(Constants.AUTH_TYPE_SIGNATURE, signature)

                message = currentReader.readMessage()
                if (message.command == Constants.CMD_AUTH) {
                    currentWriter.writeAuth(Constants.AUTH_TYPE_RSA_PUBLIC, keyPair.publicKeyBytes)
                    message = currentReader.readMessage()
                }
            }

            if (message.command != Constants.CMD_CNXN) {
                if (message.command == Constants.CMD_AUTH) {
                    throw AdbAuthException("Device rejected authentication (unauthorized)")
                }
                throw AdbConnectException("Connection failed: $message")
            }

            val connectionString = parseConnectionString(String(message.payload))
            val peerFeatures = connectionString.features
            val delayedAckEnabled =
                Constants.FEATURE_DELAYED_ACK in features &&
                    Constants.FEATURE_DELAYED_ACK in peerFeatures
            val version = minOf(message.arg0, Constants.CONNECT_VERSION)
            val peerMaxPayloadSize = message.arg1
            if (peerMaxPayloadSize <= 0) {
                throw AdbProtocolException("Peer maxdata must be > 0: $peerMaxPayloadSize")
            }
            val maxPayloadSize = minOf(peerMaxPayloadSize, connectMaxData)
                    .coerceAtLeast(1)
                    .coerceAtMost(Constants.CONNECT_MAXDATA)
            currentReader.setMaxPayloadSize(maxPayloadSize)
            currentWriter.updateProtocolVersion(version)

            return AdbConnection(
                currentReader,
                currentWriter,
                closeable,
                peerFeatures,
                delayedAckEnabled,
                maxPayloadSize,
                tlsUpgraded,
            )
        }

        // ie: "device::ro.product.name=sdk_gphone_x86;ro.product.model=Android SDK built for x86;ro.product.device=generic_x86;features=fixed_push_symlink_timestamp,apex,fixed_push_mkdir,stat_v2,abb_exec,cmd,abb,shell_v2"
        private fun parseConnectionString(connectionString: String): ConnectionString {
            val keyValues = connectionString.substringAfter("device::")
                    .split(";")
                    .mapNotNull { property ->
                        val delimiter = property.indexOf('=')
                        if (delimiter <= 0 || delimiter == property.lastIndex) {
                            null
                        } else {
                            property.substring(0, delimiter) to property.substring(delimiter + 1)
                        }
                    }
                    .toMap()
            val features = keyValues["features"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?: emptySet()
            return ConnectionString(features)
        }
    }
}

private data class ConnectionString(val features: Set<String>)
