/*
 * Copyright (c) 2021 mobile.dev inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dadb

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Selects the connection characteristics needed by an operation. */
enum class DadbRoute {
    /** Compatibility-first connection, suitable for interactive and management operations. */
    PRIMARY,

    /** Throughput-first connection, suitable for sustained or latency-sensitive streams. */
    STREAMING,
}

/**
 * Owns the physical [Dadb] connections behind one logical device session.
 *
 * The primary connection is immediately available. When a streaming factory is supplied, its
 * connection is created once in the background, shared by concurrent waiters, and retried after a
 * failed attempt. A session without a streaming factory routes both purposes to the primary
 * connection, which is the required shape for exclusive transports such as Android USB Host.
 *
 * Closing the session closes every connection it owns. Callers must not close a value returned by
 * [route] directly.
 */
class DadbSession @JvmOverloads constructor(
    private val primary: Dadb,
    private val streamingFactory: (() -> Dadb)? = null,
    warmStreaming: Boolean = true,
) : Dadb {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val streamingExecutor: ExecutorService? =
        streamingFactory?.let {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "dadb-streaming-connection").apply { isDaemon = true }
            }
        }

    @Volatile
    private var streaming: Dadb? = null

    private var streamingAttempt: CompletableFuture<Dadb>? = null

    val hasDedicatedStreamingRoute: Boolean
        get() = streamingFactory != null

    init {
        if (warmStreaming) {
            warmStreaming()
        }
    }

    override fun open(destination: String): AdbStream = primary.open(destination)

    override fun supportsFeature(feature: String): Boolean = primary.supportsFeature(feature)

    override fun isTlsConnection(): Boolean = primary.isTlsConnection()

    /** Starts creating the streaming connection without delaying the caller. */
    fun warmStreaming() {
        if (streamingFactory == null || closed.get()) return
        synchronized(lock) {
            if (!closed.get() && streaming == null) {
                streamingAttempt()
            }
        }
    }

    /**
     * Returns the connection selected for [route], waiting for streaming setup when necessary.
     * Concurrent callers share the same setup attempt. A later call retries a failed attempt.
     */
    fun route(route: DadbRoute): Dadb {
        if (route == DadbRoute.PRIMARY || streamingFactory == null) {
            check(!closed.get()) { "DadbSession is closed" }
            return primary
        }

        while (true) {
            check(!closed.get()) { "DadbSession is closed" }
            streaming?.let { return it }
            val attempt = synchronized(lock) {
                check(!closed.get()) { "DadbSession is closed" }
                streaming?.let { return it }
                streamingAttempt()
            }
            try {
                return attempt.get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AdbConnectException("Interrupted while waiting for the streaming ADB connection", error)
            } catch (error: ExecutionException) {
                synchronized(lock) {
                    if (streamingAttempt === attempt) streamingAttempt = null
                }
                val cause = error.cause ?: error
                if (cause is RuntimeException) throw cause
                if (cause is Error) throw cause
                throw AdbConnectException("Could not create the streaming ADB connection", cause)
            }
        }
    }

    /** Returns a ready route without starting or waiting for connection setup. */
    fun routeIfReady(route: DadbRoute): Dadb? =
        when (route) {
            DadbRoute.PRIMARY -> if (closed.get()) null else primary
            DadbRoute.STREAMING ->
                if (closed.get()) null else streaming ?: if (streamingFactory == null) primary else null
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        val secondary: Dadb?
        val attempt: CompletableFuture<Dadb>?
        synchronized(lock) {
            secondary = streaming
            streaming = null
            attempt = streamingAttempt
            streamingAttempt = null
        }
        attempt?.cancel(true)
        streamingExecutor?.shutdownNow()
        if (secondary != null && secondary !== primary) {
            runCatching { secondary.close() }
        }
        runCatching { primary.close() }
    }

    private fun streamingAttempt(): CompletableFuture<Dadb> {
        streamingAttempt?.takeUnless { it.isCancelled || it.isCompletedExceptionally }?.let { return it }
        val factory = checkNotNull(streamingFactory)
        val executor = checkNotNull(streamingExecutor)
        return CompletableFuture.supplyAsync(
            {
                val created = factory()
                synchronized(lock) {
                    if (closed.get()) {
                        runCatching { created.close() }
                        throw IllegalStateException("DadbSession closed during streaming connection setup")
                    }
                    streaming?.let { active ->
                        if (active !== created) runCatching { created.close() }
                        return@supplyAsync active
                    }
                    streaming = created
                    created
                }
            },
            executor,
        ).also { streamingAttempt = it }
    }
}
