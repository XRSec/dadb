/*
 * Copyright (c) 2021 mobile.dev inc.
 * Additional transport support by github.com/XRSec/
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

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout
import java.io.IOException
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal class AdbConnectionTransportTest {

    @Test
    fun connect_customMaxData() {
        val deviceResponse = Buffer()
        val responsePayload = "device::features=shell_v2".toByteArray()
        AdbWriter(deviceResponse).write(
            Constants.CMD_CNXN,
            Constants.CONNECT_VERSION,
            4096,
            responsePayload,
            0,
            responsePayload.size,
        )

        val hostWrites = Buffer()
        val transport = SourceSinkAdbTransport(deviceResponse, hostWrites, "usb:test", 15 * 1024)

        AdbConnection.connect(transport).use { connection ->
            assertThat(connection.supportsFeature("shell_v2")).isTrue()
            assertThat(connection.isTlsConnection()).isFalse()
        }

        val connectMessage = AdbReader(hostWrites).readMessage()
        assertThat(connectMessage.command).isEqualTo(Constants.CMD_CNXN)
        assertThat(connectMessage.arg1).isEqualTo(15 * 1024)
        assertThat(connectMessage.arg0).isEqualTo(Constants.CONNECT_VERSION)
        assertThat(String(connectMessage.payload)).isEqualTo(String(Constants.CONNECT_PAYLOAD))
        assertThat(String(connectMessage.payload)).contains(Constants.FEATURE_DELAYED_ACK)
    }

    @Test
    fun open_delayedAckNegotiatesInitialWindows() {
        val deviceResponse = Buffer()
        val responsePayload = "device::features=shell_v2,delayed_ack".toByteArray()
        val deviceWriter = AdbWriter(deviceResponse)
        deviceWriter.write(
            Constants.CMD_CNXN,
            Constants.CONNECT_VERSION,
            4096,
            responsePayload,
            0,
            responsePayload.size,
        )
        val hostWrites = Buffer()
        var capturedOpenMessage: AdbMessage? = null
        val transport = SourceSinkAdbTransport(
            source = deviceResponse,
            sink = ReplyingSink(hostWrites) { message ->
                if (message.command == Constants.CMD_OPEN) {
                    capturedOpenMessage = message
                    AdbWriter(deviceResponse).writeOkay(2, message.arg0, 8192)
                }
            },
            description = "usb:test",
        )

        AdbConnection.connect(transport).use { connection ->
            connection.open("shell:echo delayed_ack").close()
        }

        val openMessage = checkNotNull(capturedOpenMessage)
        assertThat(openMessage.command).isEqualTo(Constants.CMD_OPEN)
        assertThat(openMessage.arg1).isEqualTo(Constants.INITIAL_DELAYED_ACK_BYTES)
        assertThat(String(openMessage.payload, 0, openMessage.payloadLength - 1)).isEqualTo("shell:echo delayed_ack")
    }

    @Test
    fun connect_stlsUpgrade() {
        val rawDeviceResponse = Buffer()
        AdbWriter(rawDeviceResponse).write(
            Constants.CMD_STLS,
            Constants.STLS_VERSION,
            0,
            null,
            0,
            0,
        )

        val upgradedDeviceResponse = Buffer()
        val responsePayload = "device::features=shell_v2".toByteArray()
        AdbWriter(upgradedDeviceResponse).write(
            Constants.CMD_CNXN,
            Constants.CONNECT_VERSION,
            4096,
            responsePayload,
            0,
            responsePayload.size,
        )

        val rawHostWrites = Buffer()
        val upgradedHostWrites = Buffer()
        val transport =
            FakeTlsTransport(
                rawSource = rawDeviceResponse,
                rawSink = rawHostWrites,
                upgradedSource = upgradedDeviceResponse,
                upgradedSink = upgradedHostWrites,
            )

        AdbConnection.connect(transport).use { connection ->
            assertThat(connection.supportsFeature("shell_v2")).isTrue()
            assertThat(connection.isTlsConnection()).isTrue()
        }

        assertThat(transport.upgraded).isTrue()
        assertThat(transport.upgradeVersion).isEqualTo(Constants.STLS_VERSION)
        assertThat(transport.rawConnectSeen).isTrue()
        assertThat(transport.rawStlsSeen).isTrue()
    }

    @Test
    fun sourceSinkTransport_closeWithoutCloseable_closesSourceAndSink() {
        val source = TrackingSource()
        val sink = TrackingSink()
        val transport = SourceSinkAdbTransport(source, sink, "test")

        transport.close()

        assertThat(source.closed).isTrue()
        assertThat(sink.closed).isTrue()
    }

    @Test
    fun connect_missingFeatures_defaultsToEmptyFeatureSet() {
        val deviceResponse = Buffer()
        val responsePayload = "device::ro.product.name=sdk".toByteArray()
        AdbWriter(deviceResponse).write(
            Constants.CMD_CNXN,
            Constants.CONNECT_VERSION,
            4096,
            responsePayload,
            0,
            responsePayload.size,
        )

        val hostWrites = Buffer()
        val transport = SourceSinkAdbTransport(deviceResponse, hostWrites, "usb:test")

        AdbConnection.connect(transport).use { connection ->
            assertThat(connection.supportsFeature("shell_v2")).isFalse()
            assertThat(connection.isTlsConnection()).isFalse()
        }
    }

    @Test
    fun connect_preservesPeerFeaturesForCapabilityChecks() {
        val deviceResponse = Buffer()
        val responsePayload = "device::features=shell_v2,stat_v2".toByteArray()
        AdbWriter(deviceResponse).write(
            Constants.CMD_CNXN,
            Constants.CONNECT_VERSION,
            4096,
            responsePayload,
            0,
            responsePayload.size,
        )

        val hostWrites = Buffer()
        val transport = SourceSinkAdbTransport(deviceResponse, hostWrites, "usb:test")

        AdbConnection.connect(transport).use { connection ->
            assertThat(connection.supportsFeature("shell_v2")).isTrue()
            assertThat(connection.supportsFeature("stat_v2")).isTrue()
        }
    }

    @Test
    fun reader_rejectsInvalidMagic() {
        val input = Buffer()
        input.writeIntLe(Constants.CMD_CNXN)
        input.writeIntLe(Constants.CONNECT_VERSION)
        input.writeIntLe(Constants.CONNECT_MAXDATA)
        input.writeIntLe(0)
        input.writeIntLe(0)
        input.writeIntLe(0)

        val error = assertFailsWith<IOException> {
            AdbReader(input).readMessage()
        }

        assertThat(error).hasMessageThat().contains("Invalid ADB magic")
    }

    @Test
    fun reader_rejectsPayloadLargerThanLimit() {
        val input = Buffer()
        val payload = "oversized".toByteArray()
        AdbWriter(input).write(
            Constants.CMD_CNXN,
            Constants.CONNECT_VERSION,
            Constants.CONNECT_MAXDATA,
            payload,
            0,
            payload.size,
        )

        val error = assertFailsWith<IOException> {
            AdbReader(input, maxPayloadSize = 4).readMessage()
        }

        assertThat(error).hasMessageThat().contains("Invalid ADB payload length")
    }

    @Test
    fun connect_rejectsPreNegotiationPayloadLargerThanV1Limit() {
        val deviceResponse = Buffer()
        val payload = ByteArray(Constants.MAX_PAYLOAD_V1 + 1)
        AdbWriter(deviceResponse).write(
            Constants.CMD_CNXN,
            Constants.CONNECT_VERSION,
            Constants.CONNECT_MAXDATA,
            payload,
            0,
            payload.size,
        )

        val transport = SourceSinkAdbTransport(deviceResponse, Buffer(), "usb:test")

        val error = assertFailsWith<IOException> {
            AdbConnection.connect(transport)
        }

        assertThat(error).hasMessageThat().contains("Invalid ADB payload length")
    }

    @Test
    fun connect_rejectsInvalidPeerMaxData() {
        val deviceResponse = Buffer()
        val responsePayload = "device::features=shell_v2".toByteArray()
        AdbWriter(deviceResponse).write(
            Constants.CMD_CNXN,
            Constants.CONNECT_VERSION,
            0,
            responsePayload,
            0,
            responsePayload.size,
        )

        val transport = SourceSinkAdbTransport(deviceResponse, Buffer(), "usb:test")

        val error = assertFailsWith<IOException> {
            AdbConnection.connect(transport)
        }

        assertThat(error).hasMessageThat().contains("Peer maxdata must be > 0")
    }

    @Test
    fun parseReverseTcpTarget_defaultsToLoopback() {
        assertEquals(ReverseTcpTarget("127.0.0.1", 27183), parseReverseTcpTarget("tcp:27183"))
    }

    @Test
    fun parseReverseTcpTarget_supportsExplicitHost() {
        assertEquals(ReverseTcpTarget("localhost", 27183), parseReverseTcpTarget("tcp:localhost:27183"))
    }

    @Test
    fun parseReverseTcpTarget_rejectsInvalidInput() {
        assertNull(parseReverseTcpTarget("localabstract:scrcpy"))
        assertNull(parseReverseTcpTarget("tcp:"))
        assertNull(parseReverseTcpTarget("tcp:0"))
        assertNull(parseReverseTcpTarget("tcp:host:not-a-port"))
        assertNull(parseReverseTcpTarget("tcp:host:70000"))
    }

    @Test
    fun parseAdbServiceResponse_decodesOkayPayload() {
        val response = parseAdbServiceResponse("OKAY0004test")
        assertEquals(AdbServiceStatus.OKAY, response.status)
        assertEquals("test", response.payload)
    }

    @Test
    fun parseAdbServiceResponse_decodesFailPayload() {
        val response = parseAdbServiceResponse("FAIL0007failure")
        assertEquals(AdbServiceStatus.FAIL, response.status)
        assertEquals("failure", response.payload)
    }

    @Test
    fun parseReverseListOutput_supportsHostPrefixedRows() {
        val rules = parseReverseListOutput("host tcp:27183 tcp:12345\n")
        assertThat(rules).containsExactly(AdbReverseRule(device = "tcp:27183", host = "tcp:12345"))
    }

    @Test
    fun reverseForward_rejectsUnsupportedHostDestination() {
        val dadb =
            object : Dadb {
                override fun open(destination: String): AdbStream {
                    error("open should not be called for invalid reverse destinations")
                }

                override fun supportsFeature(feature: String): Boolean = false

                override fun isTlsConnection(): Boolean = false

                override fun reconnect(withDelayedAck: Boolean) = Unit

                override fun close() = Unit
            }

        val error = assertFailsWith<IllegalArgumentException> {
            dadb.reverseForward("localabstract:scrcpy", "localabstract:host")
        }

        assertThat(error).hasMessageThat().contains("supports only tcp")
    }

    @Test
    fun buildReverseForwardDestination_supportsLocalAbstractDeviceAndTcpHost() {
        assertEquals(
            "reverse:forward:localabstract:scrcpy_123;tcp:27183",
            buildReverseForwardDestination("localabstract:scrcpy_123", "tcp:27183"),
        )
    }

    @Test
    fun reverseForward_noRebind_usesNoRebindServiceAndReturnsPayload() {
        val dadb = RecordingDadb("OKAY000527183")

        val assignedPort = dadb.reverseForward("tcp:0", "tcp:27183", noRebind = true)

        assertThat(assignedPort).isEqualTo("27183")
        assertThat(dadb.openDestinations).containsExactly("reverse:forward:norebind:tcp:0;tcp:27183")
    }

    @Test
    fun reverseForward_blankOkayPayload_returnsNull() {
        val dadb = RecordingDadb("OKAY")

        val assignedPort = dadb.reverseForward(27183, 27184)

        assertNull(assignedPort)
        assertThat(dadb.openDestinations).containsExactly("reverse:forward:tcp:27183;tcp:27184")
    }

    @Test
    fun reverseKillForward_usesExpectedServiceDestination() {
        val dadb = RecordingDadb("OKAY")

        dadb.reverseKillForward("tcp:27183")

        assertThat(dadb.openDestinations).containsExactly("reverse:killforward:tcp:27183")
    }

    @Test
    fun reverseKillAllForwards_usesExpectedServiceDestination() {
        val dadb = RecordingDadb("OKAY")

        dadb.reverseKillAllForwards()

        assertThat(dadb.openDestinations).containsExactly("reverse:killforward-all")
    }

    @Test
    fun reverseListForwards_parsesServiceOutput() {
        val dadb =
            RecordingDadb(
                "OKAYhost-19 tcp:27183 tcp:12345\nhost-19 localabstract:scrcpy tcp:27184\n",
            )

        val rules = dadb.reverseListForwards()

        assertThat(rules).containsExactly(
            AdbReverseRule(device = "tcp:27183", host = "tcp:12345"),
            AdbReverseRule(device = "localabstract:scrcpy", host = "tcp:27184"),
        )
        assertThat(dadb.openDestinations).containsExactly("reverse:list-forward")
    }

    @Test
    fun executeAdbService_failResponseThrowsIOException() {
        val dadb = RecordingDadb("FAIL0007failure")

        val error = assertFailsWith<IOException> {
            dadb.reverseKillAllForwards()
        }

        assertThat(error).hasMessageThat().contains("failure")
    }
}

private class FakeTlsTransport(
    private val rawSource: Source,
    private val rawSink: Buffer,
    private val upgradedSource: Source,
    private val upgradedSink: Buffer,
) : TlsUpgradableAdbTransport {
    var upgraded = false
        private set

    var upgradeVersion: Int? = null
        private set

    var rawConnectSeen = false
        private set

    var rawStlsSeen = false
        private set

    override val source: Source
        get() = if (upgraded) upgradedSource else rawSource

    override val sink: Sink
        get() = if (upgraded) upgradedSink else rawSink

    override val isClosed: Boolean = false

    override fun upgradeToTls(version: Int) {
        val reader = AdbReader(rawSink)
        rawConnectSeen = reader.readMessage().command == Constants.CMD_CNXN
        rawStlsSeen = reader.readMessage().command == Constants.CMD_STLS
        upgraded = true
        upgradeVersion = version
    }

    override fun close() = Unit
}

private class TrackingSource : Source {
    var closed = false
        private set

    override fun read(sink: Buffer, byteCount: Long): Long = -1

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed = true
    }
}

private class TrackingSink : Sink {
    var closed = false
        private set

    override fun write(source: Buffer, byteCount: Long) = Unit

    override fun flush() = Unit

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() {
        closed = true
    }
}

private class ReplyingSink(
    private val delegate: Buffer,
    private val onMessage: (AdbMessage) -> Unit,
) : Sink {
    override fun write(source: Buffer, byteCount: Long) {
        val bytes = source.readByteArray(byteCount)
        delegate.write(bytes)
        onMessage(AdbReader(Buffer().write(bytes)).readMessage())
    }

    override fun flush() = Unit

    override fun timeout(): Timeout = Timeout.NONE

    override fun close() = Unit
}

private class RecordingDadb(
    vararg serviceResponses: String,
) : Dadb {
    val openDestinations = mutableListOf<String>()
    private val pendingResponses = ArrayDeque(serviceResponses.toList())

    override fun open(destination: String): AdbStream {
        openDestinations += destination
        val response = if (pendingResponses.isEmpty()) error("No queued response for destination: $destination") else pendingResponses.removeFirst()
        return FakeAdbStream(response)
    }

    override fun supportsFeature(feature: String): Boolean = false

    override fun isTlsConnection(): Boolean = false

    override fun reconnect(withDelayedAck: Boolean) = Unit

    override fun close() = Unit
}

private class FakeAdbStream(
    response: String,
) : AdbStream {
    override val source = Buffer().writeUtf8(response)
    override val sink = Buffer()

    override fun close() = Unit
}
