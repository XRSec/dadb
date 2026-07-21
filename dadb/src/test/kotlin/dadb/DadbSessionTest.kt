package dadb

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

class DadbSessionTest {
    @Test
    fun singleConnectionRoutesStreamingToPrimary() {
        val primary = SessionFakeDadb()
        val session = DadbSession(primary)

        assertThat(session.route(DadbRoute.PRIMARY)).isSameInstanceAs(primary)
        assertThat(session.route(DadbRoute.STREAMING)).isSameInstanceAs(primary)
        assertThat(session.hasDedicatedStreamingRoute).isFalse()

        session.close()
        assertThat(primary.closed).isTrue()
    }

    @Test
    fun concurrentWaitersShareOneStreamingAttempt() {
        val primary = SessionFakeDadb()
        val streaming = SessionFakeDadb(setOf(Dadb.FEATURE_DELAYED_ACK))
        val gate = CountDownLatch(1)
        val creations = AtomicInteger()
        val session =
            DadbSession(
                primary = primary,
                streamingFactory = {
                    creations.incrementAndGet()
                    check(gate.await(5, TimeUnit.SECONDS))
                    streaming
                },
                warmStreaming = false,
            )
        val executor = Executors.newFixedThreadPool(2)

        val first = executor.submit<Dadb> { session.route(DadbRoute.STREAMING) }
        val second = executor.submit<Dadb> { session.route(DadbRoute.STREAMING) }
        gate.countDown()

        assertThat(first.get(5, TimeUnit.SECONDS)).isSameInstanceAs(streaming)
        assertThat(second.get(5, TimeUnit.SECONDS)).isSameInstanceAs(streaming)
        assertThat(creations.get()).isEqualTo(1)

        executor.shutdownNow()
        session.close()
    }

    @Test
    fun failedStreamingAttemptCanRetryWithoutClosingPrimary() {
        val primary = SessionFakeDadb()
        val streaming = SessionFakeDadb(setOf(Dadb.FEATURE_DELAYED_ACK))
        val creations = AtomicInteger()
        val session =
            DadbSession(
                primary = primary,
                streamingFactory = {
                    if (creations.incrementAndGet() == 1) throw IOException("first attempt")
                    streaming
                },
                warmStreaming = false,
            )

        assertThat(runCatching { session.route(DadbRoute.STREAMING) }.isFailure).isTrue()
        assertThat(session.route(DadbRoute.STREAMING)).isSameInstanceAs(streaming)
        assertThat(creations.get()).isEqualTo(2)
        assertThat(primary.closed).isFalse()

        session.close()
    }

    @Test
    fun closeReleasesReadyConnectionsIndependently() {
        val primary = SessionFakeDadb(failClose = true)
        val streaming = SessionFakeDadb()
        val session = DadbSession(primary, streamingFactory = { streaming }, warmStreaming = false)

        session.route(DadbRoute.STREAMING)
        session.close()

        assertThat(primary.closed).isTrue()
        assertThat(streaming.closed).isTrue()
    }
}

private class SessionFakeDadb(
    private val features: Set<String> = emptySet(),
    private val failClose: Boolean = false,
) : Dadb {
    @Volatile
    var closed = false

    override fun open(destination: String): AdbStream = error("Not used")

    override fun supportsFeature(feature: String): Boolean = feature in features

    override fun isTlsConnection(): Boolean = false

    override fun close() {
        closed = true
        if (failClose) error("close failed")
    }
}
