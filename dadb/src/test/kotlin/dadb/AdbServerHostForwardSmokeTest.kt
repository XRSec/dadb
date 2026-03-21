package dadb

import com.google.common.truth.Truth.assertThat
import dadb.adbserver.AdbServer
import dadb.adbserver.forward
import dadb.adbserver.killForward
import dadb.adbserver.listForwards
import okio.buffer
import okio.source
import java.io.IOException
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class AdbServerHostForwardSmokeTest {

    private val serials =
        configuredAdbServerTestSerials()
            .filter { it in availableAdbDeviceSerials() }
    private val executor = Executors.newCachedThreadPool()

    @Test
    fun hostForward_listAndKillForwardSmoke() {
        assertThat(serials).isNotEmpty()

        serials.forEachIndexed { index, serial ->
            val devicePort = 39680 + index
            val expected = "HOST-FORWARD-${Random.nextInt(1000, 9999)}"
            val listener =
                executor.submit<String> {
                    adbShell(serial, buildSingleShotDeviceHttpResponseCommand(devicePort, expected))
                }

            Thread.sleep(500)

            val assignedPort =
                checkNotNull(
                    AdbServer.forward(
                        local = "tcp:0",
                        remote = "tcp:$devicePort",
                        serial = serial,
                    ),
                )
            val localPort = assignedPort.toInt()
            val localSpec = "tcp:$localPort"

            try {
                val rules = AdbServer.listForwards(serial = serial)
                assertThat(rules.any { it.serial == serial && it.local == localSpec && it.remote == "tcp:$devicePort" }).isTrue()
                assertThat(AdbServer.listForwards().any { it.serial == serial && it.local == localSpec && it.remote == "tcp:$devicePort" }).isTrue()

                val payload = awaitForwardedHttpBody(localPort)
                assertThat(payload).isEqualTo(expected)
                assertThat(listener.get(8, TimeUnit.SECONDS)).isEmpty()
            } finally {
                runCatching { AdbServer.killForward(localSpec, serial = serial) }
            }

            val remainingRules = AdbServer.listForwards(serial = serial)
            assertThat(remainingRules.any { it.local == localSpec }).isFalse()
        }
    }

    @Test
    fun hostForward_noRebindSmoke() {
        assertThat(serials).isNotEmpty()

        val serial = serials.first()
        val devicePort = 39780
        val assignedPort =
            checkNotNull(
                AdbServer.forward(
                    local = "tcp:0",
                    remote = "tcp:$devicePort",
                    serial = serial,
                ),
            )
        val localSpec = "tcp:$assignedPort"

        try {
            val error =
                assertFailsWith<IOException> {
                    AdbServer.forward(
                        local = localSpec,
                        remote = "tcp:$devicePort",
                        serial = serial,
                        noRebind = true,
                    )
                }

            assertThat(error).hasMessageThat().contains(localSpec)
        } finally {
            runCatching { AdbServer.killForward(localSpec, serial = serial) }
        }
    }

    private fun adbShell(
        serial: String,
        command: String,
    ): String {
        val process = Runtime.getRuntime().exec(arrayOf("adb", "-s", serial, "shell", command))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return output
    }

    private fun buildSingleShotDeviceHttpResponseCommand(
        devicePort: Int,
        responseBody: String,
    ): String {
        return "printf 'HTTP/1.1 200 OK\\r\\nContent-Length: ${responseBody.length}\\r\\nConnection: close\\r\\n\\r\\n$responseBody' | nc -lp $devicePort"
    }

    private fun awaitForwardedHttpBody(port: Int): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var lastFailure: IOException? = null
        while (System.nanoTime() < deadline) {
            try {
                return Socket("127.0.0.1", port).use { socket ->
                    socket.source().buffer().readUtf8().substringAfter("\r\n\r\n").trim()
                }
            } catch (e: IOException) {
                lastFailure = e
                Thread.sleep(200)
            }
        }
        throw lastFailure ?: IOException("Timed out connecting to forwarded port $port")
    }
}
