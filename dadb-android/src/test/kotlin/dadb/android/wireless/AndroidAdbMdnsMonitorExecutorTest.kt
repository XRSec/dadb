package dadb.android.wireless

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidAdbMdnsMonitorExecutorTest {
    @Test
    fun lateNsdCallbackIsDiscardedAfterExecutorShutdown() {
        val executor = newMdnsCallbackExecutor()
        val callbackRan = AtomicBoolean(false)

        executor.shutdownNow()
        executor.execute { callbackRan.set(true) }

        assertFalse(callbackRan.get())
    }
}
