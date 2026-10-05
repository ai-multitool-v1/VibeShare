# VibeShare — Architecture

VibeShare is a 10-module Gradle project following MVVM + Clean Architecture.
Dependency direction always points inward: UI → app services → repositories →
domain ← (implementations). The transfer protocol, cryptography and engine are
**pure Kotlin** with no `android.*` imports, which makes them fully unit-testable
on the JVM in seconds.

```
:app        Compose UI, navigation, ViewModels, foreground service, DI wiring, updater
:ui         Theme (dark/AMOLED/light), shared Compose components (radar, glow cards…)
:apps       Installed-app discovery + APK export (PackageManager)
:data       Room (history) + DataStore (settings) + repository implementations
:storage    SAF/paths, capacity checks, temp files, atomic finalize, FileSource over Uri
:discovery  NSD/mDNS + Wi-Fi Direct + auto-transport selection
:pairing    Tokens, PINs, session registry, QR payload, both handshake implementations
:transfer   VTP protocol, framing, HKDF/ECDH/AES-GCM, sender & receiver engines
:domain     Models + repository contracts + use cases (pure Kotlin)
:core       Result/error taxonomy, logging, formatters, constants (pure Kotlin)

:core ← :domain ← (:transfer ← :pairing), (:discovery | :storage | :apps | :data | :ui) ← :app
```

## Layers

### Domain (`:domain`, pure Kotlin)
- **Models**: `DeviceInfo`, `DiscoveredDevice`, `TransferFile`,
  `ReceiverProgress`/`IncomingProgress`, `ShareSession`, `HistoryEntry`,
  `InstalledApp`, `UpdateManifest`, `SettingsState`, `DuplicatePolicy`,
  `ConnectionLifecycle` (the 10-state machine: DISCOVERING → PAIRING →
  CONNECTING → CONNECTED → TRANSFERRING ⇄ PAUSED → COMPLETED/FAILED/CANCELLED
  (+ RECONNECTING)).
- **Repository contracts**: `SettingsRepository`, `HistoryRepository`,
  `DiscoveryRepository`, `AppsRepository`, `StorageRepository`.
- **Use cases**: thin orchestrators (`ObserveNearbyDevicesUseCase`,
  `ManageHistoryUseCase`).

### Core (`:core`, pure Kotlin)
`VibeResult` (functional errors — no exceptions across module borders),
`AppError` taxonomy + `userMessage()` mapping (spec §32), `VibeLog` pluggable
sink, `DispatcherProvider`, `Formats`/`Ids`, protocol constants.

### Transfer (`:transfer`, pure Kotlin) — VTP v1

**Framing** — every message is a `Frame`:
```
[4B magic "VBSH"][1B version][1B type][1B flags][1B reserved][4B payload length][payload]
```
Flags bit 0 = encrypted. Payloads >4 MB are rejected (malice guard). Structured
payloads (HELLO, PAIR_*, FILE_META/ACK/REJECT/DONE, CHUNK_ACK, COMPLETE, ERROR)
are kotlinx-serialization JSON; CHUNK payloads are raw bytes with a 2-byte
file-id prefix for speed.

**Crypto** — after pairing, every non-empty payload is AES-256-GCM encrypted;
the 12-byte header is AAD, a fresh 12-byte nonce per frame. Keys derive from
ephemeral ECDH P-256; HKDF-SHA256 salts the shared secret with the pairing
credential (token/PIN) and binds both nonces as transcript info, so a passive
observer without the credential cannot derive the session key.

**Pairing handshakes** (implemented in `:pairing` against `:transfer`
interfaces):
1. Client → HELLO (identity, protocol version). Server answers HELLO.
2. Client → PAIR_INIT:
   - *Token* (QR): `HMAC-SHA256(token, clientNonce)` — the token itself never
     crosses the wire.
   - *PIN*: the 6-digit PIN (short-lived, LAN-only acceptable).
   - plus the client's ECDH public key.
3. Server validates against the in-memory `SessionRegistry` (TTL 5 min),
   enforces approval (explicit, or trusted-device auto-accept), answers
   PAIR_OK with its ECDH public key + server nonce, and switches the
   connection to encrypted mode. Rejects use typed codes (401/403/410) mapped
   to friendly errors.

**Sender engine** (`SenderSession`) — one instance per receiver:
- `connect()` → handshake → `startTransfer()` streams all files.
- Per file: `FILE_META` → receiver acks with **resume offset** (bytes already
  in its temp file) → chunk stream with a bounded in-flight window (16 by
  default) → `FILE_DONE(sha256)` → receiver verifies and atomically finalizes →
  ack. Digest is computed while streaming; sources are read via
  `SeekableByteChannel`-style readers, so RAM use is O(chunk).
- Pause: stops sending; TCP backpressure blocks the receiver naturally.
  Cancel: CANCEL + SHUTDOWN frames, temp cleanup on both sides.

**Receiver engine** (`ReceiverServer` → `ReceiverSession`) — one session per
accepted connection; storage behind the `IncomingStorage`/`IncomingFileSink`
interfaces (temp file + live digest + `commit()` that verifies SHA-256 and
atomically renames / streams into SAF).

**Progress** (`ProgressTracker`) — EMA live speed over ≥400 ms samples, session
average, ETA from smoothed speed; emitted as `StateFlow` per receiver. Every
number is derived from acknowledged bytes; nothing is simulated.

### Android integration modules
- `:discovery` — `NsdDiscoveryManager` (register + browse `_vibeshare._tcp.`,
  TXT: deviceId/type/version/protocol), `WifiDirectManager` (peer scan, group
  formation, group-owner address resolution), `AutoTransportSelector`
  (connectivity state → mode), merged into `AndroidDiscoveryRepository` with
  20 s peer staleness pruning.
- `:storage` — `VibeStorageRepository` (safe filenames, duplicate resolution,
  usable-space checks), `AndroidFileSource` (ParcelFileDescriptor channel with
  seekable-else-drain-skip resume), `AndroidIncomingStorage` (deterministic
  temp names → resume across restarts; File rename in app dirs, streamed copy
  into SAF trees; 24 h temp cleanup).
- `:apps` — launchable user apps via `PackageManager` (QUERY_ALL_PACKAGES),
  split-APK detection via `ApplicationInfo.splitSourceDirs`, base-APK export to
  cache + FileProvider URI.
- `:data` — Room `transfer_history` (500-entry window) + Preferences DataStore;
  both repository implementations.

### App (`:app`)
- `TransferCoordinator` — the process-wide orchestrator: hosts the
  `ReceiverServer`, creates sessions/QR payloads, fans out `SenderSession`s,
  surfaces `StateFlow`s (hosting, incoming progress, per-receiver progress,
  pending approvals, duplicate decisions) and writes history.
- `TransferForegroundService` — `dataSync` foreground service; notification
  with real progress and pause/resume/cancel actions.
- UI — Compose screens (Home, Send, Receive, Apps, History, Settings, Dev)
  with Navigation-Compose transitions; `SplashScreen` API with a short logo
  sequence (no artificial delay); Koin DI; `UpdateManager` (manifest check →
  version compare → download with progress → SHA-256 verify → platform
  installer hand-off).

## Threading
- Blocking socket I/O on `Dispatchers.IO`; one thread per connection direction.
- Single-writer sends (`synchronized` on the connection), lock-free reads via
  frame loop per connection.
- UI state via `StateFlow` only; no shared mutable state without atomics.

## Failure handling matrix

| Failure | Behavior |
|---|---|
| Receiver offline mid-transfer | Sender `IOException` → FAILED + "Receiver disconnected. Retry?" |
| App killed on receiver | Temp `.vibeshare_part` remains; next attempt resumes from stored offset |
| Checksum mismatch | Temp discarded; both sides fail with checksum error |
| Insufficient storage | Session rejected at FILE_META with required/available sizes |
| Duplicate name | ASK/REPLACE/KEEP_BOTH/SKIP — global choice, UI dialog on receiver |
| Pairing timeout | Typed 410 → "Pairing code expired" |
| Wi-Fi off / mode unsupported | Pre-flight check → friendly error, never a stack trace |

## Testing strategy
`:transfer` and `:pairing` run on the JVM against real loopback TCP sockets
with encryption enabled: multi-file, empty files, special filenames, resume
after hard server kill, cancel propagation, all duplicate policies,
insufficient-storage rejection, 3-receiver fan-out, wrong-token rejection, and
a loopback throughput benchmark. Protocol/crypto/pairing units cover framing
corruption, HKDF/ECDH vectors, token expiry and QR payload safety.
