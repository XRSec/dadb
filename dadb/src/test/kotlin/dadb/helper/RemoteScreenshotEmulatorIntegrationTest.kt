package dadb.helper

import dadb.Dadb
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteScreenshotEmulatorIntegrationTest {
    @Test
    fun `pushes helper and captures a frame from the configured emulator`() {
        val port = System.getenv("DADB_SCREENSHOT_EMULATOR_PORT")?.toIntOrNull() ?: return
        val helperJar =
            System.getenv("DADB_SCREENSHOT_HELPER_JAR")
                ?.let(::File)
                ?: error("DADB_SCREENSHOT_HELPER_JAR is required")
        require(helperJar.isFile) { "Screenshot helper JAR does not exist: $helperJar" }

        Dadb.create(
            host = "127.0.0.1",
            port = port,
            keyPair = null,
            connectTimeout = 5_000,
            socketTimeout = 5_000,
        ).use { dadb ->
            dadb.push(helperJar, DEFAULT_REMOTE_HELPER_PATH)
            dadb
                .openRemoteScreenshotStreamWithHelper(helperJar)
                .use { stream ->
                    var capturedFrame: RemoteScreenshotFrame? = null
                    repeat(200) {
                        if (capturedFrame == null) {
                            capturedFrame = stream.requestFrame()
                        }
                    }
                    val frame = requireNotNull(capturedFrame) {
                        "Screenshot helper did not produce a frame after 200 requests"
                    }

                    assertTrue(frame.sourceWidth > 0)
                    assertTrue(frame.sourceHeight > 0)
                    assertTrue(frame.imageWidth > 0)
                    assertTrue(frame.imageHeight > 0)
                    assertEquals(
                        RemoteScreenshotCaptureBackend.SurfaceControl,
                        frame.captureBackend,
                    )
                    assertTrue(frame.jpegBytes.size > 2)
                    assertEquals(0xff.toByte(), frame.jpegBytes[0])
                    assertEquals(0xd8.toByte(), frame.jpegBytes[1])
                }
        }
    }
}
