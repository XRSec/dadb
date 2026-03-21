/*
 * Copyright (c) 2021 mobile.dev inc.
 * Android USB transport support by github.com/XRSec/
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

package dadb.android.usb

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbPacketCodecTest {

    @Test
    fun completePacketLength_partialPacket() {
        val packet = buildPacket(command = 0x45545257, arg0 = 1, arg1 = 2, payload = "hello".toByteArray())
        val pending = Buffer()

        pending.write(packet, 0, AdbPacketCodec.HEADER_LENGTH - 1)
        assertNull(AdbPacketCodec.completePacketLength(pending))

        pending.write(packet, AdbPacketCodec.HEADER_LENGTH - 1, 1)
        assertNull(AdbPacketCodec.completePacketLength(pending))

        pending.write(packet, AdbPacketCodec.HEADER_LENGTH, packet.size - AdbPacketCodec.HEADER_LENGTH)
        assertEquals(packet.size.toLong(), AdbPacketCodec.completePacketLength(pending))
    }

    @Test
    fun completePacketLength_multiplePackets() {
        val firstPacket = buildPacket(command = 0x4e45504f, arg0 = 7, arg1 = 0, payload = "shell,v2,raw:echo one\u0000".toByteArray())
        val secondPacket = buildPacket(command = 0x45545257, arg0 = 7, arg1 = 8, payload = "two".toByteArray())
        val pending = Buffer().write(firstPacket).write(secondPacket)

        val firstLength = AdbPacketCodec.completePacketLength(pending)
        assertEquals(firstPacket.size.toLong(), firstLength)
        assertEquals(firstPacket.size.toLong(), pending.readByteArray(firstLength!!).size.toLong())

        val secondLength = AdbPacketCodec.completePacketLength(pending)
        assertEquals(secondPacket.size.toLong(), secondLength)
    }

    @Test
    fun parseHeader_invalidMagic() {
        val header = buildPacket(command = 0x59414b4f, arg0 = 11, arg1 = 22, payload = ByteArray(0)).copyOf(AdbPacketCodec.HEADER_LENGTH)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 0)

        assertThrows(IOException::class.java) {
            AdbPacketCodec.parseHeader(header)
        }
    }

    private fun buildPacket(
        command: Int,
        arg0: Int,
        arg1: Int,
        payload: ByteArray,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(AdbPacketCodec.HEADER_LENGTH + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(payload.size)
        buffer.putInt(payload.sumOf { it.toUByte().toInt() })
        buffer.putInt(command xor -0x1)
        buffer.put(payload)
        return buffer.array()
    }
}
