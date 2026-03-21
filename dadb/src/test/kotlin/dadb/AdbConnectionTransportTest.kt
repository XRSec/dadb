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
import kotlin.test.Test

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
