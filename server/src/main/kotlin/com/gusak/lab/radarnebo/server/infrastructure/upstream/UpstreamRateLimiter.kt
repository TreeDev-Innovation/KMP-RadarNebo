package com.gusak.lab.radarnebo.server.infrastructure.upstream

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sliding-window limiter guarding every request to alerts.in.ua. It enforces both a maximum number
 * of requests per rolling minute and an even spacing between them, so bursts can't happen even if
 * the poll loop misbehaves (e.g. on fast retries).
 */
class UpstreamRateLimiter(
    private val maxPerWindow: Int,
    private val windowMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val minSpacingMs = windowMs / maxPerWindow
    private val history = ArrayDeque<Long>()
    private val mutex = Mutex()

    /** Immutable copy for lock-free reads from health checks on other threads. */
    @Volatile
    private var recent: List<Long> = emptyList()

    suspend fun acquire() = mutex.withLock {
        while (true) {
            val now = clock()
            prune(now)
            val spacingWait = history.lastOrNull()?.let { it + minSpacingMs - now } ?: 0L
            val windowWait = if (history.size >= maxPerWindow) history.first() + windowMs - now else 0L
            val wait = maxOf(spacingWait, windowWait)
            if (wait <= 0) {
                history.addLast(now)
                recent = history.toList()
                return@withLock
            }
            sleep(wait)
        }
    }

    fun requestsInWindow(): Int {
        val now = clock()
        return recent.count { now - it < windowMs }
    }

    private fun prune(now: Long) {
        while (history.isNotEmpty() && now - history.first() >= windowMs) history.removeFirst()
    }
}
