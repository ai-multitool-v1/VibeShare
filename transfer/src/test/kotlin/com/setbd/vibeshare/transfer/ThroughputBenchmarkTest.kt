package com.setbd.vibeshare.transfer

import com.setbd.vibeshare.core.Formats
import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.ConnectionLifecycle
import com.setbd.vibeshare.domain.model.DeviceInfo
import com.setbd.vibeshare.domain.model.TransferMode
import com.setbd.vibeshare.pairing.handshake.PairCredential
import com.setbd.vibeshare.pairing.handshake.VibeClientHandshake
import com.setbd.vibeshare.pairing.handshake.VibeServerAuthenticator
import com.setbd.vibeshare.pairing.session.SessionRegistry
import com.setbd.vibeshare.transfer.engine.ReceiverListener
import com.setbd.vibeshare.transfer.engine.ReceiverServer
import com.setbd.vibeshare.transfer.engine.SenderSession
import com.setbd.vibeshare.transfer.model.IncomingFileMeta
import com.setbd.vibeshare.transfer.model.LocalDirectoryStorage
import com.setbd.vibeshare.transfer.model.LocalFileSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loopback throughput benchmark (spec section 31). Measures real end-to-end
 * speed of the engine (framing + AES-GCM + chunking) on the host machine.
 * CI-safe: 64 MB payload finishes in a few seconds on commodity hardware.
 */
class ThroughputBenchmarkTest {

    private class QuietListener : ReceiverListener {
        override fun onSessionStarted(peerName: String) {}
        override fun onProgress(progress: com.setbd.vibeshare.domain.model.IncomingProgress) {}
        override fun onFileWaitingApproval(meta: IncomingFileMeta) = com.setbd.vibeshare.domain.model.DuplicatePolicy.REPLACE
        override fun onSessionCompleted(files: Int, totalBytes: Long, durationMs: Long) {}
        override fun onSessionFailed(error: String?) {}
    }

    @Test
    fun loopbackThroughput() = runBlocking {
        val root: Path = Files.createTempDirectory("vibe-bench")
        val size = 64 * 1024 * 1024
        val payload = root.resolve("payload.bin")
        Files.write(payload, ByteArray(1024 * 1024) { (it % 251).toByte() }.let { chunk ->
            ByteArray(size).also { out ->
                for (i in 0 until size step chunk.size) System.arraycopy(chunk, 0, out, i, minOf(chunk.size, size - i))
            }
        })

        val registry = SessionRegistry()
        val dest = Files.createDirectory(root.resolve("dst"))
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val server = ReceiverServer(
            authenticator = VibeServerAuthenticator(
                registry = registry,
                self = DeviceInfo("r", "Receiver", protocolVersion = 1),
                encryptionEnabled = true,
                ioDispatcher = Dispatchers.IO,
            ),
            storage = LocalDirectoryStorage(dest),
            listener = QuietListener(),
            dispatchers = com.setbd.vibeshare.core.coroutines.DefaultDispatcherProvider(),
            readTimeoutMs = 30_000,
            scope = scope,
        )
        val started = server.start(0)
        check(started is VibeResult.Success)

        val session = registry.createSession(
            TransferMode.LOCAL_WIFI, "127.0.0.1", started.data, deviceId = "r", deviceName = "Receiver"
        )
        val sender = SenderSession(
            self = DeviceInfo("s", "Sender", protocolVersion = 1),
            sources = listOf(LocalFileSource(payload)),
            handshake = VibeClientHandshake(PairCredential.Token(session.token), Dispatchers.IO, encryptionEnabled = true),
            config = SenderSession.Config(chunkSizeBytes = 1024 * 1024, inFlightWindow = 32),
            ioDispatcher = Dispatchers.IO,
            scope = scope,
        )
        sender.connect("127.0.0.1", started.data)
        val t0 = System.currentTimeMillis()
        sender.startTransfer()
        withTimeout(120_000) {
            while (sender.progress.value.state != ConnectionLifecycle.COMPLETED) {
                delay(50)
                if (sender.progress.value.state == ConnectionLifecycle.FAILED) error("transfer failed: " + sender.progress.value.error)
            }
        }
        val elapsed = System.currentTimeMillis() - t0
        val avg = size.toLong() * 1000 / elapsed.coerceAtLeast(1)
        println("BENCH: ${Formats.bytes(size.toLong())} in ${elapsed} ms -> ${Formats.speed(avg)} (loopback, AES-256-GCM)")
        assertTrue("throughput sanity", avg > 1_000_000) // > 1 MB/s sanity floor
        server.stop()
        scope.coroutineContext[Job]?.cancel()
        root.toFile().deleteRecursively()
        Unit
    }
}
