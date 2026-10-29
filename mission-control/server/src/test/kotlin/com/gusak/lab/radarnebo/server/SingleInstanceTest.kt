package com.gusak.lab.radarnebo.server

import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SingleInstanceTest {
    private val dir = Files.createTempDirectory("single-instance").toFile()
    private val acquired = mutableListOf<SingleInstance>()

    /** Acquires in [dir] and remembers the instance, so the test can't leave the lock held. */
    private fun acquire(timeout: Duration = 15.seconds, onShutdownRequest: () -> Unit = {}) =
        SingleInstance.acquire(dir, timeout, onShutdownRequest).also { acquired += it }

    @AfterTest
    fun cleanUp() {
        acquired.forEach { it.close() }
        dir.deleteRecursively()
    }

    @Test
    fun aNewerInstanceTakesOverFromTheRunningOne() {
        val asked = CountDownLatch(1)
        lateinit var first: SingleInstance
        first = acquire {
            asked.countDown()
            first.close()
        }
        acquire()
        assertTrue(asked.await(1, TimeUnit.SECONDS), "the running instance was asked to shut down")
    }

    @Test
    fun anInstanceThatWontGoBlocksTheNewOne() {
        val requests = AtomicInteger()
        acquire { requests.incrementAndGet() }
        assertFailsWith<IllegalStateException> { acquire(timeout = 500.milliseconds) }
        assertEquals(1, requests.get())
    }

    /**
     * Best-effort: System.gc() is only a hint, and the instance stays reachable by design, so nothing can assert that a
     * collection ran; a regression shows up only when one happens to.
     */
    @Test
    fun theLockOutlivesADroppedInstance() {
        val requests = AtomicInteger()
        // Dropped, as main() does: only a weak reference is kept, so the test can close it at the end.
        val dropped = WeakReference(SingleInstance.acquire(dir) { requests.incrementAndGet() })
        repeat(5) {
            System.gc()
            Thread.sleep(50)
        }
        assertFailsWith<IllegalStateException> { acquire(timeout = 500.milliseconds) }
        assertEquals(1, requests.get(), "still running, so it was asked to go")
        assertNotNull(dropped.get()).close()
    }

    @Test
    fun aRequestWithoutTheTokenIsIgnored() {
        val asked = CountDownLatch(1)
        val running = acquire { asked.countDown() }
        Socket(InetAddress.getLoopbackAddress(), controlPort()).use { it.getOutputStream().write("guess\n".toByteArray()) }
        assertFalse(asked.await(200, TimeUnit.MILLISECONDS), "a wrong token doesn't stop the server")
        running.close()
        acquire().close()
    }

    @Test
    fun aStaleOrGarbledControlFileOnlyCostsTheWaitForTheLock() {
        val holder = acquire()
        // As a crashed instance leaves it (its port dead), then unreadable: asking fails quietly and the wait times out.
        val deadPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        for (contents in listOf("$deadPort stale-token", "garbage")) {
            dir.resolve("server.control").writeText(contents)
            assertFailsWith<IllegalStateException> { acquire(timeout = 300.milliseconds) }
        }
        holder.close()
        acquire().close()
    }

    @Test
    fun aSilentPeerOnTheControlPortDoesNotBlockATakeover() {
        val asked = CountDownLatch(1)
        lateinit var running: SingleInstance
        running = acquire {
            asked.countDown()
            running.close()
        }
        Socket(InetAddress.getLoopbackAddress(), controlPort()).use {
            // Connected and sending nothing, while a newer instance asks the running one to go.
            acquire(timeout = 5.seconds)
            assertTrue(asked.await(1, TimeUnit.SECONDS), "the running instance was still asked to shut down")
        }
    }

    private fun controlPort() = dir.resolve("server.control").readText().substringBefore(" ").toInt()
}
