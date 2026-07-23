package dadb.helper

import dadb.AdbStream
import dadb.Dadb
import okio.Buffer
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteScreenshotHelperTest {
    @Test
    fun `opens screenshot stream when peer only supports legacy shell`() {
        val dadb = LegacyShellDadb()
        val localHelperJar = File.createTempFile("dadb-device-helper", ".jar")

        try {
            dadb
                .openRemoteScreenshotStreamWithHelper(localHelperJar)
                .use {
                    assertEquals(
                        listOf(
                            true,
                            true,
                        ),
                        dadb.openedDestinations.map { destination ->
                            destination.startsWith("shell:") || destination.startsWith("exec:")
                        },
                    )
                    assertEquals(false, dadb.openedDestinations.any { it.startsWith("shell,v2") })
                    assertEquals(true, dadb.openedDestinations.first().startsWith("shell:"))
                    assertEquals(true, dadb.openedDestinations.last().startsWith("exec:"))
                }
        } finally {
            localHelperJar.delete()
        }
    }

    @Test
    fun `request frame decodes framed jpeg payload`() {
        val source =
            protocolSource().apply {
                writeByte(SCREENSHOT_STATUS_FRAME)
                writeInt(1920)
                writeInt(1080)
                writeInt(1080)
                writeInt(608)
                writeLong(1234L)
                writeByte(1)
                writeInt(18)
                writeInt(3)
                write(byteArrayOf(1, 2, 3))
            }
        val sink = Buffer()
        val stream = RemoteScreenshotStream(FakeAdbStream(source, sink))

        val frame = requireNotNull(stream.requestFrame())

        assertEquals(1920, frame.sourceWidth)
        assertEquals(1080, frame.sourceHeight)
        assertEquals(1080, frame.imageWidth)
        assertEquals(608, frame.imageHeight)
        assertEquals(1234L, frame.captureTimestampMillis)
        assertEquals(RemoteScreenshotCaptureBackend.DisplaySurface, frame.captureBackend)
        assertEquals(18, frame.captureDurationMillis)
        assertContentEquals(byteArrayOf(1, 2, 3), frame.jpegBytes)
        assertEquals(SCREENSHOT_COMMAND_NEXT.toByte(), sink.readByte())
    }

    @Test
    fun `request frame decodes surface control backend`() {
        val source =
            protocolSource().apply {
                writeByte(SCREENSHOT_STATUS_FRAME)
                writeInt(1920)
                writeInt(1080)
                writeInt(720)
                writeInt(405)
                writeLong(1234L)
                writeByte(2)
                writeInt(24)
                writeInt(1)
                writeByte(1)
            }
        val stream = RemoteScreenshotStream(FakeAdbStream(source, Buffer()))

        val frame = requireNotNull(stream.requestFrame())

        assertEquals(RemoteScreenshotCaptureBackend.SurfaceControl, frame.captureBackend)
    }

    @Test
    fun `no change response does not produce a frame`() {
        val source =
            protocolSource().apply {
                writeByte(SCREENSHOT_STATUS_NO_CHANGE)
                writeInt(20)
            }
        val stream = RemoteScreenshotStream(FakeAdbStream(source, Buffer()))

        assertEquals(null, stream.requestFrame())
    }

    @Test
    fun `request frame surfaces helper error`() {
        val message = "capture failed"
        val source =
            protocolSource().apply {
                writeByte(SCREENSHOT_STATUS_ERROR)
                writeInt(message.toByteArray().size)
                writeUtf8(message)
            }
        val stream = RemoteScreenshotStream(FakeAdbStream(source, Buffer()))

        val error = assertFailsWith<IOException> { stream.requestFrame() }

        assertEquals(message, error.message)
    }

    @Test
    fun `invalid handshake is rejected`() {
        val source = Buffer().apply { writeInt(0).writeByte(SCREENSHOT_PROTOCOL_VERSION) }

        assertFailsWith<IOException> {
            RemoteScreenshotStream(FakeAdbStream(source, Buffer()))
        }
    }

    private fun protocolSource(): Buffer =
        Buffer()
            .writeInt(SCREENSHOT_PROTOCOL_MAGIC)
            .writeByte(SCREENSHOT_PROTOCOL_VERSION)

    private class FakeAdbStream(
        override val source: Buffer,
        override val sink: Buffer,
    ) : AdbStream {
        override fun close() = Unit
    }

    private class LegacyShellDadb : Dadb {
        val openedDestinations = mutableListOf<String>()

        override fun open(destination: String): AdbStream {
            openedDestinations += destination
            val source =
                when {
                    destination.startsWith("shell:") -> Buffer().writeUtf8("PING_OK\n")
                    destination.startsWith("exec:") ->
                        Buffer()
                            .writeInt(SCREENSHOT_PROTOCOL_MAGIC)
                            .writeByte(SCREENSHOT_PROTOCOL_VERSION)
                    else -> error("Unexpected ADB destination: $destination")
                }
            return FakeAdbStream(source, Buffer())
        }

        override fun supportsFeature(feature: String): Boolean = false

        override fun isTlsConnection(): Boolean = false

        override fun close() = Unit
    }
}
