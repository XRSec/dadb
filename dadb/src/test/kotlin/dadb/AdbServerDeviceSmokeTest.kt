package dadb

import com.google.common.truth.Truth.assertThat
import dadb.adbserver.AdbServer
import okio.buffer
import okio.source
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test

internal class AdbServerDeviceSmokeTest {

    private val serials =
        configuredAdbServerTestSerials()
            .filter { it in availableAdbDeviceSerials() }
    private val executor = Executors.newCachedThreadPool()

    @Test
    fun shellSmoke() {
        assertThat(serials).isNotEmpty()

        serials.forEach { serial ->
            withDevice(serial) { dadb ->
                val response = dadb.shell("echo smoke-$serial")
                assertThat(response.exitCode).isEqualTo(0)
                assertThat(response.output.trim()).isEqualTo("smoke-$serial")
            }
        }
    }

    @Test
    fun forwardSmoke() {
        assertThat(serials).isNotEmpty()

        serials.forEachIndexed { index, serial ->
            withDevice(serial) { dadb ->
                val devicePort = 39080 + index
                val hostPort = 39180 + index
                val expected = "FORWARD-$index"

                dadb.forward(hostPort, "tcp:$devicePort").use {
                    val future =
                        executor.submit {
                            dadb.shell("echo -e '$expected' | nc -lp $devicePort")
                        }
                    Thread.sleep(500)

                    val result =
                        Socket("localhost", hostPort).use { socket ->
                            socket.source().buffer().readUtf8()
                        }

                    future.get(5, TimeUnit.SECONDS)
                    assertThat(result.trim()).isEqualTo(expected)
                }
            }
        }
    }

    @Test
    fun reverseSmoke() {
        assertThat(serials).isNotEmpty()

        serials.forEachIndexed { index, serial ->
            withDevice(serial) { dadb ->
                val devicePort = 39280 + index
                val expected = "REVERSE-${Random.nextInt(1000, 9999)}"
                val response =
                    captureReverseHttpExchange(
                        serial = serial,
                        dadb = dadb,
                        devicePort = devicePort,
                        responseBody = expected,
                    ) { url ->
                        adbShell(serial, "curl -fsS --max-time 5 $url").trim()
                    }

                assertThat(response).isEqualTo(expected)
            }
        }
    }

    @Test
    fun reverseSmoke_comparesExternalAdbShellAndDadbShellTriggers() {
        assertThat(serials).isNotEmpty()

        serials.forEachIndexed { index, serial ->
            withDevice(serial) { dadb ->
                val expected = "REVERSE-COMPARE-${Random.nextInt(1000, 9999)}"
                val externalAdbPayload =
                    captureReverseHttpExchange(
                        serial = serial,
                        dadb = dadb,
                        devicePort = 39480 + (index * 2),
                        responseBody = expected,
                    ) { url ->
                        adbShell(serial, "curl -fsS --max-time 5 $url").trim()
                    }

                val dadbShellPayload =
                    captureReverseHttpExchange(
                        serial = serial,
                        dadb = dadb,
                        devicePort = 39481 + (index * 2),
                        responseBody = expected,
                    ) { url ->
                        val response = dadb.shell("curl -fsS --max-time 5 $url")
                        assertThat(response.exitCode).isEqualTo(0)
                        assertThat(response.errorOutput).isEmpty()
                        response.output.trim()
                    }

                assertThat(externalAdbPayload).isEqualTo(expected)
                assertThat(dadbShellPayload).isEqualTo(expected)
            }
        }
    }

    private fun withDevice(
        serial: String,
        body: (Dadb) -> Unit,
    ) {
        AdbServer.createDadb(
            adbServerHost = "localhost",
            adbServerPort = 5037,
            deviceQuery = "host:transport:$serial",
            socketTimeout = 5000,
        ).use(body)
    }

    private fun adbReverseList(serial: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("adb", "-s", serial, "reverse", "--list"))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return output
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

    private fun captureReverseHttpExchange(
        serial: String,
        dadb: Dadb,
        devicePort: Int,
        responseBody: String,
        trigger: (url: String) -> String,
    ): String {
        ServerSocket(0).use { server ->
            val hostPort = server.localPort
            val servedRequest =
                executor.submit(
                    Callable<String> {
                        serveSingleHttpResponse(server, responseBody)
                    },
                )

            dadb.reverseForward(devicePort, hostPort)
            try {
                val reverseList = adbReverseList(serial)
                assertThat(reverseList).contains("tcp:$devicePort")
                assertThat(reverseList).contains("tcp:$hostPort")
                val deviceUrl = "http://127.0.0.1:$devicePort/"
                val triggerOutput = trigger(deviceUrl)
                val request = servedRequest.get(8, TimeUnit.SECONDS)
                assertThat(request).contains("GET / HTTP")
                return triggerOutput
            } finally {
                runCatching { dadb.reverseKillForward("tcp:$devicePort") }
            }
        }
    }

    private fun serveSingleHttpResponse(
        server: ServerSocket,
        responseBody: String,
    ): String {
        server.soTimeout = 8000
        return server.accept().use { socket ->
            socket.soTimeout = 5000
            val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val request = StringBuilder()

            while (true) {
                val line = reader.readLine() ?: break
                request.append(line).append('\n')
                if (line.isEmpty()) {
                    break
                }
            }

            val body = responseBody.toByteArray(Charsets.UTF_8)
            val headers =
                buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.US_ASCII)

            socket.getOutputStream().use { output ->
                output.write(headers)
                output.write(body)
                output.flush()
            }

            request.toString()
        }
    }
}
