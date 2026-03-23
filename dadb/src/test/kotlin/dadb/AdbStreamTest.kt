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
import okio.Buffer
import kotlin.test.assertFailsWith
import kotlin.test.Test

internal class AdbStreamTest {

    @Test
    fun testLargeRemoteWrite() {
        val payload = ByteArray(1024 * 1024).apply { fill(1) }
        val adbReader = createAdbReader(1, 2, payload)
        val messageQueue = AdbMessageQueue(adbReader)
        messageQueue.startListening(1)
        val stream = AdbStreamImpl(messageQueue, AdbWriter(Buffer()), 1024, 1, 2)
        Truth.assertThat(stream.source.readByteArray()).isEqualTo(payload)
    }

    @Test
    fun sinkWaitsForOkayAfterWrite() {
        val source = Buffer().apply {
            AdbWriter(this).write(Constants.CMD_OKAY, 2, 1, null, 0, 0)
        }
        val writes = Buffer()
        val messageQueue = AdbMessageQueue(AdbReader(source))
        messageQueue.startListening(1)
        val stream = AdbStreamImpl(messageQueue, AdbWriter(writes), 1024, 1, 2)

        stream.sink.writeUtf8("hello")
        stream.sink.flush()

        val message = AdbReader(writes).readMessage()
        Truth.assertThat(message.command).isEqualTo(Constants.CMD_WRTE)
        Truth.assertThat(message.payload).isEqualTo("hello".toByteArray())
    }

    @Test
    fun sinkFailsWhenOkayIsMissing() {
        val writes = Buffer()
        val messageQueue = AdbMessageQueue(AdbReader(Buffer()))
        messageQueue.startListening(1)
        val stream = AdbStreamImpl(messageQueue, AdbWriter(writes), 1024, 1, 2)

        stream.sink.writeUtf8("hello")

        val error = assertFailsWith<java.io.IOException> {
            stream.sink.flush()
        }

        Truth.assertThat(error).hasMessageThat().contains("ADB stream closed before OKAY")
        val wrte = AdbReader(writes).readMessage()
        Truth.assertThat(wrte.command).isEqualTo(Constants.CMD_WRTE)
        Truth.assertThat(writes.exhausted()).isTrue()
    }

    @Test
    fun sourceDelayedAckAcknowledgesDeliveredPayload() {
        val payload = "hello".toByteArray()
        val writes = Buffer()
        val messageQueue = AdbMessageQueue(createAdbReader(1, 2, payload))
        messageQueue.startListening(1)
        val stream = AdbStreamImpl(
            messageQueue = messageQueue,
            adbWriter = AdbWriter(writes),
            maxPayloadSize = 1024,
            localId = 1,
            remoteId = 2,
            delayedAckEnabled = true,
        )

        Truth.assertThat(stream.source.readByteArray()).isEqualTo(payload)

        val ack = AdbReader(writes).readMessage()
        Truth.assertThat(ack.command).isEqualTo(Constants.CMD_OKAY)
        Truth.assertThat(Constants.decodeOkayAckBytes(ack, delayedAckEnabled = true)).isEqualTo(payload.size)
        Truth.assertThat(writes.exhausted()).isTrue()
    }

    @Test
    fun sinkDelayedAckSplitsWritesByAvailableWindow() {
        val source = Buffer().apply {
            AdbWriter(this).writeOkay(2, 1, 2)
        }
        val writes = Buffer()
        val messageQueue = AdbMessageQueue(AdbReader(source))
        messageQueue.startListening(1)
        val stream = AdbStreamImpl(
            messageQueue = messageQueue,
            adbWriter = AdbWriter(writes),
            maxPayloadSize = 1024,
            localId = 1,
            remoteId = 2,
            delayedAckEnabled = true,
            initialAvailableSendBytes = 3,
        )

        stream.sink.writeUtf8("hello")
        stream.sink.flush()

        val reader = AdbReader(writes)
        val firstWrite = reader.readMessage()
        val secondWrite = reader.readMessage()
        Truth.assertThat(firstWrite.command).isEqualTo(Constants.CMD_WRTE)
        Truth.assertThat(String(firstWrite.payload)).isEqualTo("hel")
        Truth.assertThat(secondWrite.command).isEqualTo(Constants.CMD_WRTE)
        Truth.assertThat(String(secondWrite.payload)).isEqualTo("lo")
        Truth.assertThat(writes.exhausted()).isTrue()
    }

    @Test
    fun sinkDelayedAckRejectsBareOkayPayload() {
        val source = Buffer().apply {
            AdbWriter(this).writeOkay(2, 1)
        }
        val writes = Buffer()
        val messageQueue = AdbMessageQueue(AdbReader(source))
        messageQueue.startListening(1)
        val stream = AdbStreamImpl(
            messageQueue = messageQueue,
            adbWriter = AdbWriter(writes),
            maxPayloadSize = 1024,
            localId = 1,
            remoteId = 2,
            delayedAckEnabled = true,
        )

        stream.sink.writeUtf8("x")

        val error = assertFailsWith<java.io.IOException> {
            stream.sink.flush()
        }

        Truth.assertThat(error).hasMessageThat().contains("missing OKAY payload")
        Truth.assertThat(writes.exhausted()).isTrue()
    }

    private fun createAdbReader(localId: Int, remoteId: Int, writePayload: ByteArray): AdbReader {
        val source = Buffer()
        AdbWriter(source).write(Constants.CMD_WRTE, remoteId, localId, writePayload, 0, writePayload.size)
        return AdbReader(source)
    }
}
