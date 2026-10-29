package com.gusak.lab.radarnebo.server.infrastructure.upstream

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpstreamRateLimiterTest {

    @Test
    fun limiterNeverExceedsBudgetInAnyRollingMinute() = runTest {
        val limiter = UpstreamRateLimiter(maxPerWindow = 8, clock = { testScheduler.currentTime })
        val times = mutableListOf<Long>()
        // A caller hammering acquire() as fast as possible for 10 virtual minutes.
        val job = launch { while (true) { limiter.acquire(); times += testScheduler.currentTime } }
        // One past the 10 minutes, so the request due at exactly 10:00 has run and the window is full.
        testScheduler.advanceTimeBy(10 * 60_000L + 1)
        assertEquals(8, limiter.requestsInWindow())
        job.cancel()
        assertTrue(times.size in 75..81, "expected ~80 requests in 10 min, got ${times.size}")
        times.forEach { start -> assertTrue(times.count { it in start until start + 60_000 } <= 8) }
        assertTrue(times.zipWithNext { a, b -> b - a }.all { it >= 7_500 }, "requests are spaced evenly through the minute")
    }

    @Test
    fun requestsLeaveTheWindowOnceItHasPassed() = runTest {
        var now = 0L
        val limiter = UpstreamRateLimiter(maxPerWindow = 8, clock = { now }, sleep = { now += it })
        repeat(3) { limiter.acquire() }
        assertEquals(3, limiter.requestsInWindow())
        now += 60_000
        assertEquals(0, limiter.requestsInWindow())
    }

    @Test
    fun aClockSteppedBackDoesNotStallTheNextRequest() = runTest {
        var offset = 0L
        val limiter = UpstreamRateLimiter(maxPerWindow = 6, clock = { testScheduler.currentTime + offset })
        limiter.acquire()
        offset = -3_600_000
        val started = testScheduler.currentTime
        limiter.acquire()
        assertTrue(testScheduler.currentTime - started <= 10_000, "waited ${testScheduler.currentTime - started} ms")
    }

    @Test
    fun aBudgetOfNoRequestsIsRejected() {
        assertFailsWith<IllegalArgumentException> { UpstreamRateLimiter(0) }
    }
}
