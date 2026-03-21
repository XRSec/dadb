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

import com.google.common.truth.Truth
import dadb.adbserver.AdbServer
import okio.Buffer
import okio.Sink
import okio.sink
import okio.source
import okio.Timeout
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test

internal class DadbImplTest {

    private val endpoint = findTestEmulatorEndpoint()
    private val dadb = Dadb.create(endpoint.host, endpoint.port, keyPair = AdbKeyPair.readDefault()) as DadbImpl

    @AfterTest
    fun tearDown() {
        dadb.close()
    }

    @Test
    fun reconnection() {
        killServer()
        assertShellResponse(dadb.shell("echo hello1"), 0, "hello1\n")

        dadb.closeConnection()

        assertShellResponse(dadb.shell("echo hello2"), 0, "hello2\n")
    }

    @Test
    fun reconnection_customTransport() {
        killServer()
        val customDadb =
            Dadb.create(
                description = "${endpoint.host}:${endpoint.port}",
                keyPair = AdbKeyPair.readDefault(),
            ) {
                val socket = Socket(endpoint.host, endpoint.port)
                SourceSinkAdbTransport(
                    source = socket.source(),
                    sink = socket.sink(),
                    description = "${endpoint.host}:${endpoint.port}",
                    closeable = socket,
                )
            } as DadbImpl

        customDadb.use {
            assertShellResponse(it.shell("echo hello1"), 0, "hello1\n")
            it.closeConnection()
            assertShellResponse(it.shell("echo hello2"), 0, "hello2\n")
        }
    }

    @Ignore
    @Test
    fun root() {
        dadb.root()
        dadb.unroot()
    }

    @Test
    fun discover() {
        startServer()
        val emulatorSerial = checkNotNull(endpoint.serial)
        val dadb =
            AdbServer.listDadbs("localhost")
                .firstOrNull { it.toString() == emulatorSerial }
                ?: fail("Failed to discover emulator $emulatorSerial")
        dadb.use {
            assertShellResponse(it.shell("echo hello"), 0, "hello\n")
        }
    }

    @Test
    fun open_retriesOnceAfterRecoverableStaleTransportFailure() {
        val connectCount = AtomicInteger(0)
        val dadb =
            DadbImpl(
                transportFactory =
                    AdbTransportFactory {
                        val attempt = connectCount.incrementAndGet()
                        when (attempt) {
                            1 -> scriptedTransport(respondToOpen = false)
                            2 -> scriptedTransport(respondToOpen = true)
                            else -> throw IOException("Unexpected connect attempt $attempt")
                        }
                    },
                keyPair = null,
            )

        dadb.use {
            it.open("sync:").use { _ -> }
        }

        Truth.assertThat(connectCount.get()).isEqualTo(2)
    }

    private fun fail(message: String): Nothing {
        Truth.assertWithMessage(message).fail()
        throw RuntimeException()
    }

    private fun scriptedTransport(respondToOpen: Boolean): AdbTransport {
        val incoming = Buffer()
        val payload = "device::features=shell_v2".toByteArray()
        AdbWriter(incoming).write(
            Constants.CMD_CNXN,
            Constants.CONNECT_VERSION,
            4096,
            payload,
            0,
            payload.size,
        )

        val closed = AtomicBoolean(false)
        val outgoing =
            object : Sink {
                private val written = Buffer()

                override fun write(
                    source: Buffer,
                    byteCount: Long,
                ) {
                    written.write(source, byteCount)
                    while (true) {
                        val message = readNextMessage(written) ?: break
                        if (respondToOpen && message.command == Constants.CMD_OPEN) {
                            val localId = message.arg0
                            AdbWriter(incoming).write(
                                Constants.CMD_OKAY,
                                1,
                                localId,
                                null,
                                0,
                                0,
                            )
                        }
                    }
                }

                override fun flush() = Unit

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() {
                    closed.set(true)
                }
            }

        return SourceSinkAdbTransport(
            source = incoming,
            sink = outgoing,
            description = if (respondToOpen) "responsive" else "stale",
            closeable =
                AutoCloseable {
                    closed.set(true)
                },
        )
    }

    private fun readNextMessage(buffer: Buffer): AdbMessage? {
        if (buffer.size < 24) {
            return null
        }
        val header = buffer.copy()
        header.readIntLe()
        header.readIntLe()
        header.readIntLe()
        val payloadLength = header.readIntLe()
        val messageLength = 24L + payloadLength
        if (buffer.size < messageLength) {
            return null
        }
        val packet = Buffer()
        packet.write(buffer, messageLength)
        return AdbReader(packet).readMessage()
    }
}
