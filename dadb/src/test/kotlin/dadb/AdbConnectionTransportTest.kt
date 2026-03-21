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
        }

        val connectMessage = AdbReader(hostWrites).readMessage()
        assertThat(connectMessage.command).isEqualTo(Constants.CMD_CNXN)
        assertThat(connectMessage.arg1).isEqualTo(15 * 1024)
    }
}
