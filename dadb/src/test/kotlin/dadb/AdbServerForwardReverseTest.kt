package dadb

import com.google.common.truth.Truth.assertThat
import dadb.adbserver.AdbServer
import okio.buffer
import okio.sink
import okio.source
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test

internal class AdbServerForwardReverseTest {

    private val executor = Executors.newCachedThreadPool()
    private val realDeviceSerial = findTestRealDeviceSerial()

    @Test
    fun tcpForward_realDeviceOverAdbServer() {
        withRealDevice { dadb ->
            dadb.tcpForward(8891, "tcp:8891").use {
                val future =
                    executor.submit {
                        dadb.shell("echo -e 'OK' | nc -lp 8891")
                    }
                Thread.sleep(500)

                val result =
                    Socket("localhost", 8891).use { socket ->
                        socket.source().buffer().readUtf8()
                    }

                future.get(5, TimeUnit.SECONDS)
                assertThat(result).isEqualTo("OK\n")
            }
        }
    }

    @Test
    fun reverseForward_realDeviceOverAdbServer() {
        assumeFalse(
            realDeviceSerial.contains("_adb-tls-connect._tcp"),
            "adb reverse over wireless debugging is unreliable; use a USB-connected device for this integration test",
        )

        withRealDevice { dadb ->
            val devicePort = 8892
            ServerSocket(0).use { server ->
                val hostPort = server.localPort
                val accepted =
                    executor.submit(
                        Callable {
                            server.accept().use { socket ->
                                socket.source().buffer().readUtf8LineStrict()
                            }
                        },
                    )

                dadb.reverseForward(devicePort, hostPort)

                try {
                    val response = dadb.shell("echo -e 'PING' | nc -w 2 127.0.0.1 $devicePort")
                    assertThat(response.exitCode).isEqualTo(0)
                    assertThat(accepted.get(5, TimeUnit.SECONDS)).isEqualTo("PING")
                } finally {
                    runCatching { dadb.reverseKillForward("tcp:$devicePort") }
                }
            }
        }
    }

    private fun withRealDevice(body: (Dadb) -> Unit) {
        AdbServer.createDadb(
            adbServerHost = "localhost",
            adbServerPort = 5037,
            deviceQuery = "host:transport:$realDeviceSerial",
            socketTimeout = 5000,
        ).use(body)
    }
}
