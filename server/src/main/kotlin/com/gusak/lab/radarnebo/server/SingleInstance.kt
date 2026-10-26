package com.gusak.lab.radarnebo.server

import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Keeps one RadarNebo server per machine, whether started headless (`:server:run`) or in the console. The newest
 * one wins: [acquire] asks a running instance to shut down, waits for it to go, and then holds the lock until
 * [close] or exit. The OS releases the lock when a process dies, so a crashed instance never blocks the next one.
 *
 * A shutdown request goes over a loopback socket and must carry the token from [CONTROL_FILE], which only this
 * user can read.
 */
class SingleInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val control: ServerSocket,
    private val controlFile: File,
    private val token: String,
) : AutoCloseable {

    override fun close() {
        synchronized(Companion) { if (held === this) held = null }
        runCatching { control.close() }
        if (runCatching { controlFile.readText().endsWith(token) }.getOrDefault(false)) controlFile.delete()
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SingleInstance::class.java)
        private const val LOCK_FILE = "server.lock"
        private const val CONTROL_FILE = "server.control"

        val defaultDir get() = File(System.getProperty("user.home"), ".radarnebo")

        // Kept reachable here: callers may drop the instance, and a collected FileChannel closes and frees the lock.
        private var held: SingleInstance? = null

        /**
         * Becomes the only server instance, first asking a running one to shut down. [onShutdownRequest] runs
         * (once, on a background thread) when a newer instance asks this one to go: it should stop the server and
         * exit, or [close] this. Throws if the running instance hasn't gone within [timeout].
         */
        fun acquire(dir: File = defaultDir, timeout: Duration = 15.seconds, onShutdownRequest: () -> Unit): SingleInstance {
            dir.mkdirs()
            val channel = FileChannel.open(dir.resolve(LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val controlFile = dir.resolve(CONTROL_FILE)
            val started = TimeSource.Monotonic.markNow()
            var asked = false
            while (true) {
                val lock = tryLock(channel)
                if (lock != null) return hold(channel, lock, controlFile, onShutdownRequest)
                if (!asked) {
                    log.info("Another RadarNebo server is running; asking it to shut down")
                    askToShutDown(controlFile)
                    asked = true
                }
                if (started.elapsedNow() > timeout) {
                    channel.close()
                    error("Another RadarNebo server is running and didn't shut down within $timeout")
                }
                Thread.sleep(100)
            }
        }

        private fun tryLock(channel: FileChannel): FileLock? = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null // held by this JVM, e.g. an earlier instance in the same process
        }

        private fun hold(channel: FileChannel, lock: FileLock, controlFile: File, onShutdownRequest: () -> Unit): SingleInstance {
            val token = UUID.randomUUID().toString()
            val control = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
            writePrivately(controlFile, "${control.localPort} $token")
            val requested = AtomicBoolean(false)
            thread(isDaemon = true, name = "single-instance") {
                while (!control.isClosed) {
                    val accepted = runCatching { control.accept() }.getOrNull() ?: continue
                    val line = accepted.use { socket -> runCatching { socket.getInputStream().bufferedReader().readLine() }.getOrNull() }
                    if (line == token && requested.compareAndSet(false, true)) {
                        log.info("A newer RadarNebo server asked this one to shut down")
                        onShutdownRequest()
                    }
                }
            }
            return SingleInstance(channel, lock, control, controlFile, token).also { synchronized(Companion) { held = it } }
        }

        private fun askToShutDown(controlFile: File) {
            val (port, token) = runCatching { controlFile.readText().trim().split(" ", limit = 2) }.getOrNull()
                ?.takeIf { it.size == 2 } ?: return
            runCatching {
                Socket(InetAddress.getLoopbackAddress(), port.toInt()).use { socket ->
                    socket.getOutputStream().write("$token\n".toByteArray())
                }
            }.onFailure { log.warn("Couldn't reach the running server to ask it to shut down: {}", it.message) }
        }

        private fun writePrivately(file: File, text: String) {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.delete()
            // Owner-only from the start, so the token is never readable by others, even briefly.
            runCatching { Files.createFile(tmp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
            tmp.writeText(text)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
