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

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import kotlin.test.Test

internal class AdbWriterTest {

    @Test
    fun write_usesPayloadSliceForChecksumBeforeNegotiation() {
        val sink = Buffer()
        val payload = byteArrayOf(1, 2, 3, 4, 5)

        AdbWriter(sink).write(
            Constants.CMD_WRTE,
            1,
            2,
            payload,
            1,
            3,
        )

        val message = AdbReader(sink).readMessage()
        assertThat(message.checksum).isEqualTo(2 + 3 + 4)
        assertThat(message.payload).isEqualTo(byteArrayOf(2, 3, 4))
    }

    @Test
    fun write_skipsChecksumAfterNegotiation() {
        val sink = Buffer()
        val writer = AdbWriter(sink)
        writer.updateProtocolVersion(Constants.CONNECT_VERSION)

        val payload = "shell:echo hello".toByteArray()
        writer.write(
            Constants.CMD_OPEN,
            1,
            0,
            payload,
            0,
            payload.size,
        )

        val message = AdbReader(sink).readMessage()
        assertThat(message.checksum).isEqualTo(0)
    }
}
