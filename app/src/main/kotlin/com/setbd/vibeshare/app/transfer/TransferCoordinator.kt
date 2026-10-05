package com.setbd.vibeshare.app.transfer

import com.setbd.vibeshare.core.Constants
import com.setbd.vibeshare.core.coroutines.DispatcherProvider
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.core.error.AppError
import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.ConnectionLifecycle
import com.setbd.vibeshare.domain.model.DeviceInfo
import com.setbd.vibeshare.domain.model.DeviceType
import com.setbd.vibeshare.domain.model.DuplicateDecision
import com.setbd.vibeshare.domain.model.DuplicatePolicy
import com.setbd.vibeshare.domain.model.HistoryEntry
import com.setbd.vibeshare.domain.model.IncomingProgress
import com.setbd.vibeshare.domain.model.ReceiverProgress
import com.setbd.vibeshare.domain.model.SettingsState
import com.setbd.vibeshare.domain.model.ShareSession
import com.setbd.vibeshare.domain.model.TransferDirection
import com.setbd.vibeshare.domain.model.TransferMode
import com.setbd.vibeshare.domain.repository.HistoryRepository
import com.setbd.vibeshare.domain.repository.SettingsRepository
import com.setbd.vibeshare.discovery.AndroidDiscoveryRepository
import com.setbd.vibeshare.pairing.handshake.PairCredential
import com.setbd.vibeshare.pairing.handshake.PendingApprovalBus
import com.setbd.vibeshare.pairing.handshake.VibeClientHandshake
import com.setbd.vibeshare.pairing.handshake.VibeServerAuthenticator
import com.setbd.vibeshare.pairing.qr.QrPayload
import com.setbd.vibeshare.pairing.session.SessionRegistry
import com.setbd.vibeshare.transfer.engine.ClientHandshake
import com.setbd.vibeshare.transfer.engine.ReceiverListener
import com.setbd.vibeshare.transfer.engine.ReceiverServer
import com.setbd.vibeshare.transfer.engine.SenderSession
import com.setbd.vibeshare.transfer.model.FileSource
import com.setbd.vibeshare.transfer.model.IncomingFileMeta
import com.setbd.vibeshare.transfer.model.IncomingStorage
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide orchestrator bridging the transfer engine, pairing, discovery,
 * UI and the foreground service. One instance per app process (Koin singletons).
 */
class TransferCoordinator(
    private val registry: SessionRegistry,
    private val approvalBus: PendingApprovalBus,
    private val incomingStorage: IncomingStorage,
    private val discovery: AndroidDiscoveryRepository,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository,
    private val dispatchers: DispatcherProvider,
    private val selfIdentity: () -> DeviceInfo,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    // ---- hosting (receiver) ----
    private var receiverServer: ReceiverServer? = null

    data class HostingState(
        val active: Boolean = false,
        val session: ShareSession? = null,
        val qrPayload: QrPayload? = null,
        val port: Int = 0,
    )

    private val _hosting = MutableStateFlow(HostingState())
    val hosting: StateFlow<HostingState> = _hosting.asStateFlow()

    private val _incoming = MutableStateFlow<IncomingProgress?>(null)
    val incoming: StateFlow<IncomingProgress?> = _incoming.asStateFlow()

    /** Pending duplicate-policy decision requested by the storage pipeline. */
    private val _pendingDuplicate = MutableStateFlow<DuplicateDecision?>(null)
    val pendingDuplicate: StateFlow<DuplicateDecision?> = _pendingDuplicate.asStateFlow()

    // ---- sending ----
    private val senderSessions = ConcurrentHashMap<String, SenderSession>()

    private val _senders = MutableStateFlow<List<ReceiverProgress>>(emptyList())
    val senders: StateFlow<List<ReceiverProgress>> = _senders.asStateFlow()

    private val _activeConnectionState = MutableStateFlow(ConnectionLifecycle.DISCOVERING)
    val activeConnectionState: StateFlow<ConnectionLifecycle> = _activeConnectionState.asStateFlow()

    /** Pairing approval request surfaced to the receiver UI. */
    val pendingApproval = approvalBus.request

    // ---- hosting lifecycle ----

    fun startHosting(resolvedMode: TransferMode, hostAddress: String?): VibeResult<HostingState> {
        stopHosting()
        val self = selfIdentity()
        val settings = currentSettings()

        val server = ReceiverServer(
            authenticator = VibeServerAuthenticator(
                registry = registry,
                self = self,
                encryptionEnabled = settings.encryptionEnabled,
                trustedDeviceIds = settings.trustedDeviceIds,
                autoAcceptTrusted = settings.autoAcceptTrusted,
                approvalGate = { peer, session ->
                    val trusted = peer.deviceId in settings.trustedDeviceIds
                    if (trusted && settings.autoAcceptTrusted) {
                        true
                    } else {
                        awaitApproval(peer, session)
                    }
                },
                ioDispatcher = dispatchers.io,
            ),
            storage = incomingStorage,
            listener = receiverListener,
            dispatchers = dispatchers,
            readTimeoutMs = Constants.SOCKET_READ_TIMEOUT_MS,
            scope = scope,
        )
        return when (val started = runBlockingIo { server.start(Constants.DEFAULT_PORT) }) {
            is VibeResult.Failure -> started
            is VibeResult.Success -> {
                receiverServer = server
                val session = registry.createSession(
                    mode = resolvedMode,
                    host = hostAddress,
                    port = started.data,
                    deviceId = self.deviceId,
                    deviceName = self.name,
                )
                val qr = QrPayload(
                    sessionId = session.sessionId,
                    deviceId = self.deviceId,
                    deviceName = self.name,
                    mode = resolvedMode.name,
                    host = hostAddress,
                    port = started.data,
                    token = session.token,
                    expiresAtMs = session.expiresAtMs,
                )
                // Advertise on the local network so no scanning is needed in Mode B.
                if (resolvedMode != TransferMode.WIFI_DIRECT) {
                    discovery.advertise(self.name, started.data, self.deviceId, self.type.name, self.appVersion)
                }
                _activeConnectionState.value = ConnectionLifecycle.CONNECTED
                val state = HostingState(active = true, session = session, qrPayload = qr, port = started.data)
                _hosting.value = state
                VibeLog.i(TAG, "Hosting on port ${started.data} mode=$resolvedMode")
                VibeResult.success(state)
            }
        }
    }

    fun stopHosting() {
        receiverServer?.stop()
        receiverServer = null
        discovery.stopAdvertising()
        registry.revokeAll()
        _hosting.value = HostingState()
        if (_activeConnectionState.value == ConnectionLifecycle.CONNECTED) {
            _activeConnectionState.value = ConnectionLifecycle.DISCOVERING
        }
    }

    fun approveIncoming(request: PendingApprovalBus.Request, approved: Boolean) {
        approvalBus.emit(null)
        request.responder(approved)
    }

    fun respondDuplicate(decision: DuplicateDecision) {
        _pendingDuplicate.value = null
        duplicateResponse?.complete(decision.chosen ?: DuplicatePolicy.KEEP_BOTH)
        duplicateResponse = null
    }

    @Volatile
    private var duplicateResponse: CompletableDeferred<DuplicatePolicy>? = null

    private suspend fun awaitDuplicateDecision(meta: IncomingFileMeta): DuplicatePolicy {
        val deferred = CompletableDeferred<DuplicatePolicy>()
        duplicateResponse = deferred
        _pendingDuplicate.value = DuplicateDecision(fileId = meta.fileId, fileName = meta.name)
        return withTimeoutOrNull(120_000) { deferred.await() } ?: DuplicatePolicy.KEEP_BOTH
    }

    private suspend fun awaitApproval(peer: DeviceInfo, session: ShareSession): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        approvalBus.emit(PendingApprovalBus.Request(peer, session) { approved -> deferred.complete(approved) })
        return withTimeoutOrNull(90_000) { deferred.await() } ?: false
    }

    // ---- sending lifecycle ----

    fun sendTo(
        host: String,
        port: Int,
        credential: PairCredential,
        sources: List<FileSource>,
        onSessionReady: (String) -> Unit = {},
    ): VibeResult<String> {
        val self = selfIdentity()
        val settings = currentSettings()
        val handshake: ClientHandshake = VibeClientHandshake(
            credential = credential,
            ioDispatcher = dispatchers.io,
            encryptionEnabled = settings.encryptionEnabled,
        )
        val session = SenderSession(
            self = self,
            sources = sources,
            handshake = handshake,
            config = SenderSession.Config(
                chunkSizeBytes = (settings.chunkSizeKiB * 1024).coerceIn(
                    Constants.MIN_CHUNK_SIZE_BYTES,
                    Constants.MAX_CHUNK_SIZE_BYTES,
                ),
            ),
            ioDispatcher = dispatchers.io,
            scope = scope,
        )
        val tag = "peer-${System.nanoTime()}"
        senderSessions[tag] = session

        scope.launch {
            _activeConnectionState.value = ConnectionLifecycle.CONNECTING
            when (val paired = session.connect(host, port)) {
                is VibeResult.Failure -> {
                    _activeConnectionState.value = ConnectionLifecycle.FAILED
                    recordFailure(sources, paired.error)
                    senderSessions.remove(tag)
                }
                is VibeResult.Success -> {
                    _activeConnectionState.value = ConnectionLifecycle.CONNECTED
                    onSessionReady(tag)
                    session.startTransfer()
                }
            }
        }
        return VibeResult.success(tag)
    }

    fun pauseSend(sessionTag: String) = senderSessions[sessionTag]?.pause()

    fun resumeSend(sessionTag: String) = senderSessions[sessionTag]?.resume()

    fun cancelSend(sessionTag: String) {
        senderSessions.remove(sessionTag)?.cancel()
    }

    fun cancelAllSends() {
        senderSessions.values.forEach { it.cancel() }
        senderSessions.clear()
    }

    fun refreshSenderList() {
        _senders.value = senderSessions.values.map { it.progress.value }
    }

    // ---- receiver listener ----

    private val receiverListener = object : ReceiverListener {
        override fun onSessionStarted(peerName: String) {
            _activeConnectionState.value = ConnectionLifecycle.PAIRING
            _incoming.value = IncomingProgress(
                sessionId = "incoming",
                peerName = peerName,
                state = ConnectionLifecycle.PAIRING,
            )
        }

        override fun onProgress(progress: IncomingProgress) {
            _incoming.value = progress
            _activeConnectionState.value = progress.state
        }

        override fun onFileWaitingApproval(meta: IncomingFileMeta): DuplicatePolicy {
            return kotlinx.coroutines.runBlocking {
                awaitDuplicateDecision(meta)
            }
        }

        override fun onSessionCompleted(files: Int, totalBytes: Long, durationMs: Long) {
            _activeConnectionState.value = ConnectionLifecycle.COMPLETED
            _incoming.value?.let { last ->
                scope.launch {
                    historyRepository.add(
                        HistoryEntry(
                            direction = TransferDirection.RECEIVE,
                            peerName = last.peerName,
                            fileNames = last.currentFileName,
                            fileCount = files,
                            totalBytes = totalBytes,
                            success = true,
                            durationMs = durationMs,
                            averageSpeedBps = if (durationMs > 0) totalBytes * 1000 / durationMs else 0,
                            timestampMs = System.currentTimeMillis(),
                        )
                    )
                }
            }
            _incoming.value = _incoming.value?.copy(state = ConnectionLifecycle.COMPLETED)
        }

        override fun onSessionFailed(error: String?) {
            _activeConnectionState.value = ConnectionLifecycle.FAILED
            _incoming.value = _incoming.value?.copy(state = ConnectionLifecycle.FAILED, error = error)
        }
    }

    private fun recordFailure(sources: List<FileSource>, error: AppError) {
        scope.launch {
            historyRepository.add(
                HistoryEntry(
                    direction = TransferDirection.SEND,
                    peerName = "—",
                    fileNames = sources.firstOrNull()?.file?.displayName ?: "",
                    fileCount = sources.size,
                    totalBytes = sources.sumOf { it.file.sizeBytes },
                    success = false,
                    durationMs = 0,
                    averageSpeedBps = 0,
                    timestampMs = System.currentTimeMillis(),
                )
            )
        }
    }

    private val settingsCache = MutableStateFlow<SettingsState?>(null)

    fun seedSettings(state: SettingsState) {
        settingsCache.value = state
    }

    private fun currentSettings(): SettingsState = settingsCache.value ?: SettingsState()

    private fun runBlockingIo(block: suspend () -> VibeResult<Int>): VibeResult<Int> =
        kotlinx.coroutines.runBlocking { block() }

    companion object {
        private const val TAG = "Coordinator"
    }
}
