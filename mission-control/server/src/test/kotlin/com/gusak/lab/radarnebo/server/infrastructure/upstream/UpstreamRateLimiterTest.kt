package com.gusak.lab.radarnebo.server.infrastructure.upstream

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpstreamRateLimiterTest {

    @Test
    fun limiterNeverExceedsBudgetInAnyRollingMinute() = runTest {
        val limiter = UpstreamRateLimiter(maxPerWindow = 8, clock = { testScheduler.currentTime })
        val times = mutableListOf<Long>()
        // A caller hammering acquire() as fast as possible for 10 virtual minutes.
        val job = launch { while (true) { limiter.acquire(); times += testScheduler.currentTime } }
        testScheduler.advanceTimeBy(10 * 60_000L)
        job.cancel()
        assertTrue(times.size in 75..81, "expected ~80 requests in 10 min, got ${times.size}")
        times.forEach { start -> assertTrue(times.count { it in start until start + 60_000 } <= 8) }
    }

    @Test
    fun aBudgetOfNoRequestsIsRejected() {
        assertFailsWith<IllegalArgumentException> { UpstreamRateLimiter(0) }
    }
}
