package com.gusak.lab.radarnebo.server

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SingleInstanceTest {
    private val dir = Files.createTempDirectory("single-instance").toFile().apply { deleteOnExit() }

    @Test
    fun aNewerInstanceTakesOverFromTheRunningOne() {
        val asked = CountDownLatch(1)
        lateinit var first: SingleInstance
        first = SingleInstance.acquire(dir) {
            asked.countDown()
            first.close()
        }
        val second = SingleInstance.acquire(dir) {}
        assertTrue(asked.await(1, TimeUnit.SECONDS), "the running instance was asked to shut down")
        second.close()
    }

    @Test
    fun anInstanceThatWontGoBlocksTheNewOne() {
        var requests = 0
        val stubborn = SingleInstance.acquire(dir) { requests++ }
        assertFailsWith<IllegalStateException> { SingleInstance.acquire(dir, timeout = 500.milliseconds) {} }
        assertEquals(1, requests)
        stubborn.close()
    }

    @Test
    fun theLockOutlivesADroppedInstance() {
        var requests = 0
        SingleInstance.acquire(dir) { requests++ } // dropped, as main() does
        repeat(5) {
            System.gc()
            Thread.sleep(50)
        }
        assertFailsWith<IllegalStateException> { SingleInstance.acquire(dir, timeout = 500.milliseconds) {} }
        assertEquals(1, requests, "still running, so it was asked to go")
    }

    @Test
    fun aRequestWithoutTheTokenIsIgnored() {
        var requests = 0
        val running = SingleInstance.acquire(dir) { requests++ }
        val port = dir.resolve("server.control").readText().substringBefore(" ").toInt()
        java.net.Socket("127.0.0.1", port).use { it.getOutputStream().write("guess\n".toByteArray()) }
        Thread.sleep(200)
        assertEquals(0, requests)
        running.close()
        SingleInstance.acquire(dir) {}.close()
    }
}
