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
import java.io.IOException
import java.util.regex.Pattern
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

fun assertShellResponse(shellResponse: AdbShellResponse, exitCode: Int, allOutput: String) {
    Truth.assertThat(shellResponse.allOutput).isEqualTo(allOutput)
    Truth.assertThat(shellResponse.exitCode).isEqualTo(exitCode)
}

fun assertShellPacket(shellPacket: AdbShellPacket, packetType: Class<out AdbShellPacket>, payload: String) {
    Truth.assertThat(String(shellPacket.payload)).isEqualTo(payload)
    Truth.assertThat(shellPacket).isInstanceOf(packetType)
}

fun killServer() {
    try {
        // Connection fails if there are simultaneous auth requests
        Runtime.getRuntime().exec("adb kill-server").waitFor()
    } catch (ignore: IOException) {}
}

fun startServer() {
    try {
        Runtime.getRuntime().exec("adb start-server").waitFor()
    } catch (ignore: IOException) {}
}

data class TestEmulatorEndpoint(
    val host: String,
    val port: Int,
    val serial: String?,
)

fun findTestRealDeviceSerial(): String {
    val configuredSerial = System.getenv("DADB_TEST_REAL_DEVICE_SERIAL")
    if (!configuredSerial.isNullOrBlank()) {
        return configuredSerial
    }

    val process = Runtime.getRuntime().exec(arrayOf("adb", "devices"))
    val output = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()

    return output
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("List of devices attached") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            val serial = parts.firstOrNull() ?: return@mapNotNull null
            val state = parts.getOrNull(1) ?: return@mapNotNull null
            if (state != "device" || serial.startsWith("emulator-")) {
                null
            } else {
                serial
            }
        }
        .firstOrNull()
        ?: throw IllegalStateException(
            "No real Android device found. Set DADB_TEST_REAL_DEVICE_SERIAL or connect one device via adb server.",
        )
}

fun configuredAdbServerTestSerials(): List<String> {
    val configured = System.getenv("DADB_TEST_SERIALS")
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    if (configured.isNotEmpty()) {
        return configured
    }

    val process = Runtime.getRuntime().exec(arrayOf("adb", "devices"))
    val output = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()

    return output
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("List of devices attached") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            val serial = parts.firstOrNull() ?: return@mapNotNull null
            val state = parts.getOrNull(1) ?: return@mapNotNull null
            if (state == "device") serial else null
        }
        .toList()
}

fun availableAdbDeviceSerials(): Set<String> {
    val process = Runtime.getRuntime().exec(arrayOf("adb", "devices"))
    val output = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()

    return output
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("List of devices attached") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            val serial = parts.firstOrNull() ?: return@mapNotNull null
            val state = parts.getOrNull(1) ?: return@mapNotNull null
            if (state == "device") serial else null
        }
        .toSet()
}

fun findConnectedDeviceSerials(): List<String> {
    val process = Runtime.getRuntime().exec(arrayOf("adb", "devices"))
    val output = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()

    return output
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("List of devices attached") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2 && parts[1] == "device") {
                parts[0]
            } else {
                null
            }
        }
        .toList()
}

fun findServerSmokeDeviceSerials(): List<String> {
    val configured = System.getenv("DADB_TEST_SERIALS")
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    return if (configured.isNotEmpty()) {
        configured
    } else {
        findConnectedDeviceSerials()
    }
}

fun findTestEmulatorEndpoint(): TestEmulatorEndpoint {
    val configuredPort = System.getenv("DADB_TEST_EMULATOR_PORT")?.toIntOrNull()
    if (configuredPort != null && configuredPort > 0) {
        return TestEmulatorEndpoint(
            host = System.getenv("DADB_TEST_EMULATOR_HOST") ?: "localhost",
            port = configuredPort,
            serial = System.getenv("DADB_TEST_EMULATOR_SERIAL"),
        )
    }

    val process =
        Runtime.getRuntime().exec(arrayOf("adb", "devices"))
    val output =
        process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()

    val emulatorPattern = Pattern.compile("^emulator-(\\d+)\\s+device$", Pattern.MULTILINE)
    val matcher = emulatorPattern.matcher(output)
    if (matcher.find()) {
        val consolePort = matcher.group(1).toInt()
        return TestEmulatorEndpoint(
            host = "localhost",
            port = consolePort + 1,
            serial = "emulator-$consolePort",
        )
    }

    throw IllegalStateException(
        "No emulator device found. Set DADB_TEST_EMULATOR_PORT or start an Android emulator before running direct dadb tests.",
    )
}

fun CompletableFuture<*>.waitFor() {
    get(1000, TimeUnit.MILLISECONDS)
}
