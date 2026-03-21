package dadb

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith

internal class AdbSyncBehaviorTest {

    @Test
    fun send_decodesFailResponse() {
        val source =
            Buffer().apply {
                writeString(FAIL, Charsets.UTF_8)
                writeIntLe("permission denied".length)
                writeString("permission denied", Charsets.UTF_8)
            }

        val stream = bufferBackedStream(source = source, sink = Buffer())

        val error =
            assertFailsWith<IOException> {
                AdbSyncStream(stream).use {
                    it.send(Buffer().writeUtf8("hello"), "/data/local/tmp/hello", 420, 0)
                }
            }

        assertThat(error).hasMessageThat().contains("permission denied")
    }

    @Test
    fun send_usesSendV2WhenFeaturePresent() {
        val response =
            Buffer().apply {
                writeString(OKAY, Charsets.UTF_8)
                writeIntLe(0)
            }
        val sink = Buffer()
        val stream = bufferBackedStream(source = response, sink = sink)

        AdbSyncStream(stream, setOf(FEATURE_SENDRECV_V2)).use {
            it.send(Buffer().writeUtf8("hello"), "/data/local/tmp/hello", 420, 2_000)
        }

        assertFrameHeader(sink, SEND_V2, 21)
        assertThat(sink.readUtf8(21L)).isEqualTo("/data/local/tmp/hello")
        assertFrameHeader(sink, SEND_V2, 420)
        assertThat(sink.readIntLe()).isEqualTo(0)
        assertFrameHeader(sink, DATA, 5)
        assertThat(sink.readUtf8(5L)).isEqualTo("hello")
        assertFrameHeader(sink, DONE, 2)
        assertFrameHeader(sink, QUIT, 0)
        assertThat(sink.exhausted()).isTrue()
    }

    @Test
    fun recv_usesRecvV2WhenFeaturePresent() {
        val source =
            Buffer().apply {
                writeString(DATA, Charsets.UTF_8)
                writeIntLe(5)
                writeUtf8("hello")
                writeString(DONE, Charsets.UTF_8)
                writeIntLe(0)
            }
        val sink = Buffer()
        val stream = bufferBackedStream(source = source, sink = sink)
        val output = Buffer()

        AdbSyncStream(stream, setOf(FEATURE_SENDRECV_V2)).use {
            it.recv(output, "/data/local/tmp/hello")
        }

        assertThat(output.readUtf8()).isEqualTo("hello")
        assertFrameHeader(sink, RECV_V2, 21)
        assertThat(sink.readUtf8(21L)).isEqualTo("/data/local/tmp/hello")
        assertFrameHeader(sink, RECV_V2, 0)
        assertFrameHeader(sink, QUIT, 0)
        assertThat(sink.exhausted()).isTrue()
    }

    @Test
    fun lstat_usesStatV2WhenFeaturePresent() {
        val source =
            Buffer().apply {
                writeString(LSTAT_V2, Charsets.UTF_8)
                writeIntLe(0)
                writeLongLe(1)
                writeLongLe(2)
                writeIntLe(33188)
                writeIntLe(1)
                writeIntLe(2000)
                writeIntLe(2000)
                writeLongLe(123)
                writeLongLe(10)
                writeLongLe(20)
                writeLongLe(30)
            }

        val stream = bufferBackedStream(source = source, sink = Buffer())

        AdbSyncStream(stream, setOf(FEATURE_STAT_V2)).use {
            val stat = it.lstat("/data/local/tmp/hello")
            assertThat(stat.mode).isEqualTo(33188)
            assertThat(stat.size).isEqualTo(123)
            assertThat(stat.mtimeSec).isEqualTo(20)
        }
    }

    @Test
    fun list_usesListV2WhenFeaturePresent() {
        val source =
            Buffer().apply {
                writeString(DENT_V2, Charsets.UTF_8)
                writeIntLe(0)
                writeLongLe(1)
                writeLongLe(2)
                writeIntLe(16877)
                writeIntLe(1)
                writeIntLe(2000)
                writeIntLe(2000)
                writeLongLe(123)
                writeLongLe(10)
                writeLongLe(20)
                writeLongLe(30)
                writeIntLe(5)
                writeUtf8("hello")
                writeString(DONE, Charsets.UTF_8)
                write(ByteArray(LIST_V2_DONE_TAIL_BYTES.toInt()))
            }

        val stream = bufferBackedStream(source = source, sink = Buffer())

        AdbSyncStream(stream, setOf(FEATURE_LS_V2)).use {
            val entries = it.list("/data/local/tmp")
            assertThat(entries).hasSize(1)
            assertThat(entries.first().name).isEqualTo("hello")
            assertThat(entries.first().mode).isEqualTo(16877)
            assertThat(entries.first().size).isEqualTo(123)
            assertThat(entries.first().mtimeSec).isEqualTo(20)
            assertThat(entries.first().errorCode).isNull()
        }
    }

    @Test
    fun recv_rejectsOversizedDataChunk() {
        val source =
            Buffer().apply {
                writeString(DATA, Charsets.UTF_8)
                writeIntLe(SYNC_DATA_MAX + 1)
            }

        val stream = bufferBackedStream(source = source, sink = Buffer())

        val error =
            assertFailsWith<IOException> {
                AdbSyncStream(stream).use {
                    it.recv(Buffer(), "/data/local/tmp/hello")
                }
            }

        assertThat(error).hasMessageThat().contains("Sync DATA chunk too large")
    }

    @Test
    fun send_rejectsTooLongPath() {
        val longPath = "/data/local/tmp/" + "a".repeat(SYNC_PATH_MAX + 10)
        val stream = bufferBackedStream(source = Buffer(), sink = Buffer())

        val error =
            assertFailsWith<IllegalArgumentException> {
                AdbSyncStream(stream).use {
                    it.send(Buffer().writeUtf8("hello"), longPath, 420, 0)
                }
            }

        assertThat(error).hasMessageThat().contains("Sync path too long")
    }

    @Test
    fun recv_rejectsTooLongPath() {
        val longPath = "/data/local/tmp/" + "a".repeat(SYNC_PATH_MAX + 10)
        val stream = bufferBackedStream(source = Buffer(), sink = Buffer())

        val error =
            assertFailsWith<IllegalArgumentException> {
                AdbSyncStream(stream).use {
                    it.recv(Buffer(), longPath)
                }
            }

        assertThat(error).hasMessageThat().contains("Sync path too long")
    }

    private fun bufferBackedStream(
        source: Buffer,
        sink: Buffer,
    ): AdbStream =
        object : AdbStream {
            override val source = source
            override val sink = sink

            override fun close() = Unit
        }

    private fun assertFrameHeader(buffer: Buffer, id: String, arg: Int) {
        assertThat(buffer.readString(4L, Charsets.UTF_8)).isEqualTo(id)
        assertThat(buffer.readIntLe()).isEqualTo(arg)
    }
}
