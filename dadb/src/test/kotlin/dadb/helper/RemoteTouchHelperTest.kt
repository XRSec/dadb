package dadb.helper

import dadb.AdbStream
import okio.Buffer
import okio.ForwardingSink
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteTouchHelperTest {
    @Test
    fun `touch event is framed and acknowledged`() {
        val source =
            protocolSource()
                .writeByte(TOUCH_STATUS_OK)
        val sink = Buffer()
        val stream = RemoteTouchStream(FakeAdbStream(source, sink))

        stream.sendTouch(TOUCH_ACTION_MOVE, 120, 240)

        assertEquals(TOUCH_ACTION_MOVE.toByte(), sink.readByte())
        assertEquals(120, sink.readInt())
        assertEquals(240, sink.readInt())
    }

    @Test
    fun `touch helper error is surfaced`() {
        val message = "injection rejected"
        val source =
            protocolSource()
                .writeByte(TOUCH_STATUS_ERROR)
                .writeInt(message.toByteArray().size)
                .writeUtf8(message)
        val stream = RemoteTouchStream(FakeAdbStream(source, Buffer()))

        val error =
            assertFailsWith<IOException> {
                stream.sendTouch(TOUCH_ACTION_DOWN, 10, 20)
            }

        assertEquals(message, error.message)
    }

    @Test
    fun `close sends stop command`() {
        val sink = Buffer()
        val stream = RemoteTouchStream(FakeAdbStream(protocolSource(), sink))

        stream.close()
        stream.close()

        assertEquals(TOUCH_COMMAND_STOP.toByte(), sink.readByte())
        assertEquals(true, sink.exhausted())
    }

    @Test
    fun `close interrupts an in-flight touch without interleaving stop`() {
        val responseSource = BlockingSource(protocolSource())
        val requestBytes = Buffer()
        val requestFlushed = CountDownLatch(1)
        val requestSink =
            object : ForwardingSink(requestBytes) {
                override fun flush() {
                    super.flush()
                    requestFlushed.countDown()
                }
            }.buffer()
        val adbStream =
            object : AdbStream {
                override val source = responseSource.buffer()
                override val sink = requestSink

                override fun close() {
                    source.close()
                    sink.close()
                }
            }
        val stream = RemoteTouchStream(adbStream)
        val sendResult = AtomicReference<Result<Unit>>()
        val sender =
            thread(name = "remote-touch-test") {
                sendResult.set(runCatching { stream.sendTouch(TOUCH_ACTION_DOWN, 10, 20) })
            }

        assertTrue(requestFlushed.await(1, TimeUnit.SECONDS))
        stream.close()
        sender.join(1_000L)

        assertFalse(sender.isAlive)
        assertTrue(sendResult.get().isFailure)
        assertEquals(9L, requestBytes.size)
    }

    @Test
    fun `invalid handshake is rejected`() {
        val source = Buffer().writeInt(0)

        assertFailsWith<IOException> {
            RemoteTouchStream(FakeAdbStream(source, Buffer()))
        }
    }

    private fun protocolSource(): Buffer =
        Buffer()
            .writeInt(TOUCH_PROTOCOL_MAGIC)

    private class BlockingSource(
        private val available: Buffer,
    ) : Source {
        private val lock = ReentrantLock()
        private val stateChanged = lock.newCondition()
        private var closed = false

        override fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long =
            lock.withLock {
                while (available.exhausted() && !closed) {
                    stateChanged.await()
                }
                if (closed) throw IOException("Source is closed")
                available.read(sink, byteCount)
            }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            lock.withLock {
                closed = true
                stateChanged.signalAll()
            }
        }
    }

    private class FakeAdbStream(
        override val source: Buffer,
        override val sink: Buffer,
    ) : AdbStream {
        override fun close() = Unit
    }
}
