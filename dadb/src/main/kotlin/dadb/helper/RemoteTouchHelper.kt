package dadb.helper

import dadb.AdbStream
import dadb.Dadb
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RemoteTouchStream internal constructor(
    private val stream: AdbStream,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val ioLock = ReentrantLock()

    init {
        val magic = stream.source.readInt()
        if (magic != TOUCH_PROTOCOL_MAGIC) {
            stream.close()
            throw IOException("Remote touch helper returned an invalid protocol header")
        }
    }

    fun sendTouch(
        action: Int,
        x: Int,
        y: Int,
    ) {
        require(action in TOUCH_ACTION_DOWN..TOUCH_ACTION_CANCEL) {
            "Unsupported touch action: $action"
        }
        check(!closed.get()) { "Remote touch stream is closed" }
        ioLock.withLock {
            check(!closed.get()) { "Remote touch stream is closed" }
            stream.sink
                .writeByte(action)
                .writeInt(x)
                .writeInt(y)
                .flush()
            when (val status = stream.source.readByte().toInt() and 0xff) {
                TOUCH_STATUS_OK -> Unit
                TOUCH_STATUS_ERROR -> throw IOException(readError())
                else -> throw IOException("Remote touch helper returned an unknown status: $status")
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
                stream.sink.writeByte(TOUCH_COMMAND_STOP).flush()
            }
            stream.close()
        } finally {
            ioLock.unlock()
        }
    }

    private fun readError(): String {
        val length = stream.source.readInt()
        if (length !in 0..MAX_TOUCH_ERROR_BYTES) {
            throw IOException("Remote touch helper returned an invalid error length: $length")
        }
        return stream.source.readUtf8(length.toLong()).ifBlank {
            "Remote touch helper failed without an error message"
        }
    }
}

@Throws(IOException::class)
fun Dadb.openRemoteTouchStreamWithHelper(
    localHelperJar: File,
    remoteHelperPath: String = DEFAULT_REMOTE_HELPER_PATH,
): RemoteTouchStream {
    prepareRemoteDadbHelper(localHelperJar, remoteHelperPath)
    val command =
        "CLASSPATH=${touchShellQuote(remoteHelperPath)} " +
            "exec app_process / dadb.helper.TouchStreamMain"
    return RemoteTouchStream(open("exec:$command"))
}

private fun touchShellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

internal const val TOUCH_PROTOCOL_MAGIC = 0x44544348
internal const val TOUCH_ACTION_DOWN = 0
internal const val TOUCH_ACTION_UP = 1
internal const val TOUCH_ACTION_MOVE = 2
internal const val TOUCH_ACTION_CANCEL = 3
internal const val TOUCH_COMMAND_STOP = 4
internal const val TOUCH_STATUS_OK = 0
internal const val TOUCH_STATUS_ERROR = 1
private const val MAX_TOUCH_ERROR_BYTES = 16 * 1024
