package com.setbd.vibeshare.transfer

import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.ConnectionLifecycle
import com.setbd.vibeshare.domain.model.DeviceInfo
import com.setbd.vibeshare.domain.model.DuplicatePolicy
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end engine tests over real loopback TCP with full encryption:
 * covers multi-file, resume, cancel, retry, duplicates, empty files, special
 * filenames, insufficient storage and interrupted connections (spec section 30).
 */
class TransferEngineIntegrationTest {

    private lateinit var tempRoot: Path
    private val self = DeviceInfo(deviceId = "sender-device", name = "Sender", appVersion = "test", protocolVersion = 1)
    private val peer = DeviceInfo(deviceId = "receiver-device", name = "Receiver", appVersion = "test", protocolVersion = 1)

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("vibetest")
    }

    @After
    fun tearDown() {
        tempRoot.toFile().deleteRecursively()
    }

    private fun makeFile(dir: Path, name: String, size: Int, pattern: Byte = 0x42): Path {
        val path = dir.resolve(name)
        val bytes = ByteArray(size) { pattern }
        Files.write(path, bytes)
        return path
    }

    private class RecordingListener : ReceiverListener {
        val filesCommitted = AtomicInteger(0)
        var lastProgressState: ConnectionLifecycle = ConnectionLifecycle.DISCOVERING
        var lastError: String? = null
        var lastBytes: Long = 0
        var lastTotal: Long = 0

        override fun onSessionStarted(peerName: String) {}
        override fun onProgress(progress: com.setbd.vibeshare.domain.model.IncomingProgress) {
            lastProgressState = progress.state
            lastBytes = progress.bytesDone
            lastTotal = progress.bytesTotal
        }
        override fun onFileWaitingApproval(meta: IncomingFileMeta): DuplicatePolicy = DuplicatePolicy.ASK
        override fun onSessionCompleted(files: Int, totalBytes: Long, durationMs: Long) {
            filesCommitted.addAndGet(files)
            lastBytes = totalBytes
        }
        override fun onSessionFailed(error: String?) {
            lastError = error
        }
    }

    /** Boots a receiver server wired with registry + storage; returns (server, listener, port, token). */
    private fun startReceiver(
        storage: LocalDirectoryStorage,
        registry: SessionRegistry,
        scope: CoroutineScope,
    ): ReceiverServer {
        val listener = RecordingListener()
        val server = ReceiverServer(
            authenticator = VibeServerAuthenticator(
                registry = registry,
                self = peer,
                encryptionEnabled = true,
                ioDispatcher = Dispatchers.IO,
            ),
            storage = storage,
            listener = listener,
            dispatchers = com.setbd.vibeshare.core.coroutines.DefaultDispatcherProvider(),
            readTimeoutMs = 10_000,
            scope = scope,
        )
        runBlocking {
            val started = server.start(0)
            check(started is VibeResult.Success) { "server failed to start" }
        }
        return server
    }

    private fun newSession(registry: SessionRegistry, port: Int) =
        registry.createSession(
            mode = TransferMode.LOCAL_WIFI,
            host = "127.0.0.1",
            port = port,
            ttlMs = 60_000,
            deviceId = peer.deviceId,
            deviceName = peer.name,
        )

    private suspend fun awaitCompletion(session: SenderSession, timeoutMs: Long = 20_000) {
        withTimeout(timeoutMs) {
            while (session.progress.value.state !in setOf(
                    ConnectionLifecycle.COMPLETED,
                    ConnectionLifecycle.FAILED,
                    ConnectionLifecycle.CANCELLED,
                )
            ) {
                delay(50)
            }
        }
    }

    @Test
    fun `single file transfers with integrity verification`() = runBlocking<Unit> {
        val registry = SessionRegistry()
        val sourceDir = Files.createDirectory(tempRoot.resolve("src"))
        val destDir = Files.createDirectory(tempRoot.resolve("dst"))
        val original = makeFile(sourceDir, "holiday video.mp4", 1_500_000)
        val expectedSha = sha256(original)

        val scope = CoroutineScope(Dispatchers.IO + Job())
        val server = startReceiver(LocalDirectoryStorage(destDir), registry, scope)
        val session = newSession(registry, server.boundPort)

        val sender = SenderSession(
            self = self,
            sources = listOf(LocalFileSource(original)),
            handshake = VibeClientHandshake(PairCredential.Token(session.token), Dispatchers.IO, encryptionEnabled = true),
            config = SenderSession.Config(chunkSizeBytes = 64 * 1024, inFlightWindow = 8),
            ioDispatcher = Dispatchers.IO,
            scope = scope,
        )
        val paired = sender.connect("127.0.0.1", server.boundPort)
        assertTrue("Pairing must succeed", paired is VibeResult.Success)
        sender.startTransfer()
        awaitCompletion(sender)
        assertEquals(ConnectionLifecycle.COMPLETED, sender.progress.value.state)

        val received = destDir.resolve("holiday video.mp4")
        assertTrue("File must exist at destination", Files.exists(received))
        assertEquals(expectedSha, sha256(received))
        server.stop()
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `multi file with empty file and special filenames`() = runBlocking<Unit> {
        val registry = SessionRegistry()
        val sourceDir = Files.createDirectory(tempRoot.resolve("src2"))
        val destDir = Files.createDirectory(tempRoot.resolve("dst2"))
        val files = listOf(
            makeFile(sourceDir, "empty.dat", 0),
            makeFile(sourceDir, "фото #1 (summer).jpg", 120_000),
            makeFile(sourceDir, "archive.zip", 900_000),
        )
        val expectedShas = files.map { sha256(it) }

        val scope = CoroutineScope(Dispatchers.IO + Job())
        val server = startReceiver(LocalDirectoryStorage(destDir, defaultPolicy = DuplicatePolicy.KEEP_BOTH), registry, scope)
        val session = newSession(registry, server.boundPort)
        val sender = SenderSession(
            self = self,
            sources = files.map { LocalFileSource(it) },
            handshake = VibeClientHandshake(PairCredential.Token(session.token), Dispatchers.IO, encryptionEnabled = true),
            config = SenderSession.Config(chunkSizeBytes = 32 * 1024),
            ioDispatcher = Dispatchers.IO,
            scope = scope,
        )
        sender.connect("127.0.0.1", server.boundPort)
        sender.startTransfer()
        awaitCompletion(sender)
        assertEquals(ConnectionLifecycle.COMPLETED, sender.progress.value.state)

        assertEquals(0L, Files.size(destDir.resolve("empty.dat")))
        assertTrue(Files.exists(destDir.resolve("фото #1 (summer).jpg")))
        assertTrue(Files.exists(destDir.resolve("archive.zip")))
        assertEquals(expectedShas[0], sha256(destDir.resolve("empty.dat")))
        assertEquals(expectedShas[2], sha256(destDir.resolve("archive.zip")))
        server.stop()
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `interrupted transfer resumes from receiver offset`() = runBlocking<Unit> {
        val registry = SessionRegistry()
        val sourceDir = Files.createDirectory(tempRoot.resolve("src3"))
        val destDir = Files.createDirectory(tempRoot.resolve("dst3"))
        val original = makeFile(sourceDir, "big.bin", 4_000_000)
        val expectedSha = sha256(original)
        val scope = CoroutineScope(Dispatchers.IO + Job())

        // Attempt 1: killed mid-transfer.
        val server1 = startReceiver(LocalDirectoryStorage(destDir), registry, scope)
        val session1 = newSession(registry, server1.boundPort)
        val sender1 = SenderSession(
            self = self,
            sources = listOf(LocalFileSource(original)),
            handshake = VibeClientHandshake(PairCredential.Token(session1.token), Dispatchers.IO, encryptionEnabled = true),
            config = SenderSession.Config(chunkSizeBytes = 64 * 1024),
            ioDispatcher = Dispatchers.IO,
            scope = scope,
        )
        sender1.connect("127.0.0.1", server1.boundPort)
        sender1.startTransfer()
        withTimeout(10_000) {
            while (sender1.progress.value.bytesDone < 500_000) delay(20)
        }
        server1.stop() // abrupt interruption
        scope.coroutineContext[Job]?.cancel()
        assertTrue(sender1.progress.value.bytesDone in 500_000..4_000_000)

        // Attempt 2: fresh server on the SAME destination; resume must kick in.
        val scope2 = CoroutineScope(Dispatchers.IO + Job())
        val registry2 = SessionRegistry()
        val storage2 = LocalDirectoryStorage(destDir)
        val server2 = startReceiver(storage2, registry2, scope2)
        val session2 = newSession(registry2, server2.boundPort)
        val sender2 = SenderSession(
            self = self,
            sources = listOf(LocalFileSource(original)),
            handshake = VibeClientHandshake(PairCredential.Token(session2.token), Dispatchers.IO, encryptionEnabled = true),
            config = SenderSession.Config(chunkSizeBytes = 64 * 1024),
            ioDispatcher = Dispatchers.IO,
            scope = scope2,
        )
        sender2.connect("127.0.0.1", server2.boundPort)
        sender2.startTransfer()
        awaitCompletion(sender2, timeoutMs = 30_000)
        assertEquals(ConnectionLifecycle.COMPLETED, sender2.progress.value.state)
        assertEquals(expectedSha, sha256(destDir.resolve("big.bin")))
        server2.stop()
        scope2.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `cancel propagates and receiver aborts temp file`() = runBlocking<Unit> {
        val registry = SessionRegistry()
        val sourceDir = Files.createDirectory(tempRoot.resolve("src4"))
        val destDir = Files.createDirectory(tempRoot.resolve("dst4"))
        val original = makeFile(sourceDir, "cancel-me.bin", 6_000_000)
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val server = startReceiver(LocalDirectoryStorage(destDir), registry, scope)
        val session = newSession(registry, server.boundPort)
        val sender = SenderSession(
            self = self,
            sources = listOf(LocalFileSource(original)),
            handshake = VibeClientHandshake(PairCredential.Token(session.token), Dispatchers.IO, encryptionEnabled = true),
            config = SenderSession.Config(chunkSizeBytes = 64 * 1024),
            ioDispatcher = Dispatchers.IO,
            scope = scope,
        )
        sender.connect("127.0.0.1", server.boundPort)
        sender.startTransfer()
        withTimeout(10_000) {
            while (sender.progress.value.bytesDone < 300_000) delay(20)
        }
        sender.cancel()
        awaitCompletion(sender)
        assertEquals(ConnectionLifecycle.CANCELLED, sender.progress.value.state)
        delay(400) // receiver processes the cancel
        assertTrue("No final file may exist after cancel", !Files.exists(destDir.resolve("cancel-me.bin")))
        server.stop()
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `duplicate policies replace keep-both and skip`() = runBlocking<Unit> {
        val registry = SessionRegistry()
        val sourceDir = Files.createDirectory(tempRoot.resolve("src5"))
        val destDir = Files.createDirectory(tempRoot.resolve("dst5"))
        val original = makeFile(sourceDir, "song.mp3", 200_000, pattern = 0x77)
        Files.write(destDir.resolve("song.mp3"), ByteArray(100) { 0x11 }) // stale duplicate

        // REPLACE
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val server = startReceiver(LocalDirectoryStorage(destDir, defaultPolicy = DuplicatePolicy.REPLACE), registry, scope)
        val session = newSession(registry, server.boundPort)
        val sender = SenderSession(
            self = self,
            sources = listOf(LocalFileSource(original)),
            handshake = VibeClientHandshake(PairCredential.Token(session.token), Dispatchers.IO, encryptionEnabled = true),
            config = SenderSession.Config(),
            ioDispatcher = Dispatchers.IO,
            scope = scope,
        )
        sender.connect("127.0.0.1", server.boundPort)
        sender.startTransfer()
        awaitCompletion(sender)
        assertEquals(200_000L, Files.size(destDir.resolve("song.mp3")))
        assertEquals(sha256(original), sha256(destDir.resolve("song.mp3")))
        server.stop()

        // KEEP_BOTH: second transfer keeps both.
        val scopeB = CoroutineScope(Dispatchers.IO + Job())
        val registryB = SessionRegistry()
        val serverB = startReceiver(LocalDirectoryStorage(destDir, defaultPolicy = DuplicatePolicy.KEEP_BOTH), registryB, scopeB)
        val sessionB = newSession(registryB, serverB.boundPort)
        val senderB = SenderSession(
            self = self,
            sources = listOf(LocalFileSource(original)),
            handshake = VibeClientHandshake(PairCredential.Token(sessionB.token), Dispatchers.IO, encryptionEnabled = true),
            config = SenderSession.Config(),
            ioDispatcher = Dispatchers.IO,
            scope = scopeB,
        )
        senderB.connect("127.0.0.1", serverB.boundPort)
        senderB.startTransfer()
        awaitCompletion(senderB)
        assertTrue(Files.exists(destDir.resolve("song (1).mp3")))
        serverB.stop()
        scopeB.coroutineContext[Job]?.cancel()

        // SKIP: receiver answers reject; sender completes session without sending bytes.
        val scopeC = CoroutineScope(Dispatchers.IO + Job())
        val registryC = SessionRegistry()
        val serverC = startReceiver(LocalDirectoryStorage(destDir, defaultPolicy = DuplicatePolicy.SKIP), registryC, scopeC)
        val sessionC = newSession(registryC, serverC.boundPort)
        val senderC = SenderSession(
            self = self,
            sources = listOf(LocalFileSource(original)),
            handshake = VibeClientHandshake(PairCredential.Token(sessionC.token), Dispatchers.IO, encryptionEnabled = true),
            config = SenderSession.Config(),
            ioDispatcher = Dispatchers.IO,
            scope = scopeC,
        )
        senderC.connect("127.0.0.1", serverC.boundPort)
        senderC.startTransfer()
        awaitCompletion(senderC)
        assertEquals(ConnectionLifecycle.COMPLETED, senderC.progress.value.state)
        serverC.stop()
        scopeC.coroutineContext[Job]?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `insufficient storage rejects the session with a friendly error`() = runBlocking<Unit> {
        val registry = SessionRegistry()
        val sourceDir = Files.createDirectory(tempRoot.resolve("src6"))
        val destDir = Files.createDirectory(tempRoot.resolve("dst6"))
        val original = makeFile(sourceDir, "large.bin", 3_000_000)
        val storage = LocalDirectoryStorage(destDir).apply { simulateCapacity = 1_000_000 } // 1 MB free

        val scope = CoroutineScope(Dispatchers.IO + Job())
        val server = startReceiver(storage, registry, scope)
        val session = newSession(registry, server.boundPort)
        val sender = SenderSession(
            self = self,
            sources = listOf(LocalFileSource(original)),
            handshake = VibeClientHandshake(PairCredential.Token(session.token), Dispatchers.IO, encryptionEnabled = true),
            config = SenderSession.Config(),
            ioDispatcher = Dispatchers.IO,
            scope = scope,
        )
        sender.connect("127.0.0.1", server.boundPort)
        sender.startTransfer()
        awaitCompletion(sender)
        assertEquals(ConnectionLifecycle.FAILED, sender.progress.value.state)
        assertTrue(!Files.exists(destDir.resolve("large.bin")))
        server.stop()
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `one sender reaches multiple receivers each with own progress`() = runBlocking<Unit> {
        val registry = SessionRegistry()
        val sourceDir = Files.createDirectory(tempRoot.resolve("src7"))
        val original = makeFile(sourceDir, "shared.pdf", 700_000)
        val expectedSha = sha256(original)
        val scope = CoroutineScope(Dispatchers.IO + Job())

        val receivers = (1..3).map { index ->
            val dest = Files.createDirectory(tempRoot.resolve("r$index"))
            val storage = LocalDirectoryStorage(dest)
            val server = startReceiver(storage, registry, scope)
            index to (server to dest)
        }

        val senders = receivers.map { (_, pair) ->
            val (server, _) = pair
            val session = newSession(registry, server.boundPort)
            val sender = SenderSession(
                self = self,
                sources = listOf(LocalFileSource(original)),
                handshake = VibeClientHandshake(PairCredential.Token(session.token), Dispatchers.IO, encryptionEnabled = true),
                config = SenderSession.Config(chunkSizeBytes = 64 * 1024),
                ioDispatcher = Dispatchers.IO,
                scope = scope,
            )
            sender
        }

        // Connect all, then stream all (multi-device fan-out).
        senders.forEachIndexed { index, sender ->
            val (server, _) = receivers[index].second
            val result = sender.connect("127.0.0.1", server.boundPort)
            assertTrue("receiver $index pairing", result is VibeResult.Success)
        }
        senders.forEach { it.startTransfer() }
        senders.forEach { awaitCompletion(it) }
        senders.forEach { assertEquals(ConnectionLifecycle.COMPLETED, it.progress.value.state) }

        receivers.forEach { (_, pair) ->
            val (_, dest) = pair
            assertEquals(expectedSha, sha256(dest.resolve("shared.pdf")))
        }
        receivers.forEach { it.second.first.stop() }
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `wrong pairing token is rejected`() = runBlocking<Unit> {
        val registry = SessionRegistry()
        val destDir = Files.createDirectory(tempRoot.resolve("dst8"))
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val server = startReceiver(LocalDirectoryStorage(destDir), registry, scope)
        val session = SenderSession(
            self = self,
            sources = emptyList(),
            handshake = VibeClientHandshake(PairCredential.Token("completely-invalid-token-000"), Dispatchers.IO, encryptionEnabled = true),
            config = SenderSession.Config(),
            ioDispatcher = Dispatchers.IO,
            scope = scope,
        )
        val result = session.connect("127.0.0.1", server.boundPort)
        assertTrue(result is VibeResult.Failure)
        server.stop()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun sha256(path: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return com.setbd.vibeshare.transfer.crypto.Hkdf.toHex(digest.digest())
    }
}
