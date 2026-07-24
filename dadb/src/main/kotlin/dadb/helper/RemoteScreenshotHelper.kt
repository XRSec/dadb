package dadb.helper

import dadb.AdbStream
import dadb.Dadb
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class RemoteScreenshotFrame(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val captureTimestampMillis: Long,
    val captureBackend: RemoteScreenshotCaptureBackend,
    val captureDurationMillis: Int,
    val jpegBytes: ByteArray,
)

enum class RemoteScreenshotCaptureBackend {
    DisplaySurface,
    SurfaceControl,
}

class RemoteScreenshotStream internal constructor(
    private val stream: AdbStream,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val ioLock = ReentrantLock()

    init {
        val magic = stream.source.readInt()
        if (magic != SCREENSHOT_PROTOCOL_MAGIC) {
            stream.close()
            throw IOException("Remote screenshot helper returned an invalid protocol header")
        }
    }

    fun requestFrame(): RemoteScreenshotFrame? {
        check(!closed.get()) { "Remote screenshot stream is closed" }
        return ioLock.withLock {
            check(!closed.get()) { "Remote screenshot stream is closed" }
            stream.sink.writeByte(SCREENSHOT_COMMAND_NEXT).flush()
            when (val status = stream.source.readByte().toInt() and 0xff) {
                SCREENSHOT_STATUS_FRAME -> readFrame()
                SCREENSHOT_STATUS_ERROR -> throw IOException(readError())
                SCREENSHOT_STATUS_NO_CHANGE -> {
                    val durationMillis = stream.source.readInt()
                    if (durationMillis < 0) {
                        throw IOException("Remote screenshot helper returned an invalid wait duration: $durationMillis")
                    }
                    null
                }
                else -> throw IOException("Remote screenshot helper returned an unknown status: $status")
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (!ioLock.tryLock()) {
            stream.close()
            return
        }
        try {
            runCatching {
                stream.sink.writeByte(SCREENSHOT_COMMAND_STOP).flush()
            }
            stream.close()
        } finally {
            ioLock.unlock()
        }
    }

    private fun readFrame(): RemoteScreenshotFrame {
        val sourceWidth = readDimension("source width")
        val sourceHeight = readDimension("source height")
        val imageWidth = readDimension("image width")
        val imageHeight = readDimension("image height")
        val captureTimestampMillis = stream.source.readLong()
        val captureBackend =
            when (val value = stream.source.readByte().toInt() and 0xff) {
                1 -> RemoteScreenshotCaptureBackend.DisplaySurface
                2 -> RemoteScreenshotCaptureBackend.SurfaceControl
                else -> throw IOException("Remote screenshot helper returned an unknown capture backend: $value")
            }
        val captureDurationMillis = stream.source.readInt()
        if (captureDurationMillis < 0) {
            throw IOException("Remote screenshot helper returned an invalid capture duration: $captureDurationMillis")
        }
        val payloadLength = stream.source.readInt()
        if (payloadLength !in 1..MAX_SCREENSHOT_PAYLOAD_BYTES) {
            throw IOException("Remote screenshot helper returned an invalid payload length: $payloadLength")
        }
        return RemoteScreenshotFrame(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            captureTimestampMillis = captureTimestampMillis,
            captureBackend = captureBackend,
            captureDurationMillis = captureDurationMillis,
            jpegBytes = stream.source.readByteArray(payloadLength.toLong()),
        )
    }

    private fun readDimension(name: String): Int {
        val value = stream.source.readInt()
        if (value !in 1..MAX_SCREENSHOT_DIMENSION) {
            throw IOException("Remote screenshot helper returned an invalid $name: $value")
        }
        return value
    }

    private fun readError(): String {
        val length = stream.source.readInt()
        if (length !in 0..MAX_SCREENSHOT_ERROR_BYTES) {
            throw IOException("Remote screenshot helper returned an invalid error length: $length")
        }
        return stream.source.readUtf8(length.toLong()).ifBlank {
            "Remote screenshot helper failed without an error message"
        }
    }
}

@Throws(IOException::class)
fun Dadb.openRemoteScreenshotStreamWithHelper(
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
    maxSize: Int = DEFAULT_SCREENSHOT_MAX_SIZE,
    jpegQuality: Int = DEFAULT_SCREENSHOT_JPEG_QUALITY,
): RemoteScreenshotStream {
    require(maxSize in 0..8192) { "maxSize must be between 0 and 8192" }
    require(jpegQuality in 1..100) { "jpegQuality must be between 1 and 100" }
    prepareRemoteDadbHelper(localHelperJar, remoteHelperPath)

    val command =
        buildString {
            append("CLASSPATH=")
            append(screenshotShellQuote(remoteHelperPath))
            append(" exec app_process / dadb.helper.ScreenshotStreamMain ")
            append(maxSize)
            append(' ')
            append(jpegQuality)
        }
    return RemoteScreenshotStream(open("exec:$command"))
}

private fun screenshotShellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

const val DEFAULT_SCREENSHOT_MAX_SIZE = 720
const val DEFAULT_SCREENSHOT_JPEG_QUALITY = 55
internal const val SCREENSHOT_PROTOCOL_MAGIC = 0x44534352
internal const val SCREENSHOT_COMMAND_NEXT = 1
internal const val SCREENSHOT_COMMAND_STOP = 2
internal const val SCREENSHOT_STATUS_FRAME = 0
internal const val SCREENSHOT_STATUS_ERROR = 1
internal const val SCREENSHOT_STATUS_NO_CHANGE = 2
private const val MAX_SCREENSHOT_DIMENSION = 16_384
private const val MAX_SCREENSHOT_PAYLOAD_BYTES = 16 * 1024 * 1024
private const val MAX_SCREENSHOT_ERROR_BYTES = 16 * 1024
