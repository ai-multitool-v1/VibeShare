# VibeShare

**SETBD · Privacy-first, high-speed, offline file transfer for Android.**

> *Your files stay on your devices.*

VibeShare transfers files directly between nearby Android devices — no cloud,
no accounts, no internet required. Open → Send/Receive → Select device → Select
files → Transfer.

| | |
|---|---|
| **Language** | Kotlin, Jetpack Compose, Material 3 |
| **Transports** | Wi-Fi Direct (P2P) · Local Wi-Fi (mDNS/NSD + TCP) · Auto |
| **Pairing** | Session QR code · 6-digit PIN · trusted-device auto-accept |
| **Protocol** | VTP v1 — framed TCP, streaming/chunked, AES-256-GCM after ECDH P-256 |
| **Min/Target SDK** | 24 / 35 (Android 7.0 → 15+) |
| **License** | MIT |

---

## Features

- **Two real transports**
  - *Local Wi-Fi*: peers advertise a `_vibeshare._tcp.` mDNS service; direct TCP
    between devices on the same network. No router changes, no internet.
  - *Wi-Fi Direct*: framework `WifiP2pManager` discovery + group formation; the
    transfer socket rides the P2P link. No access point at all.
  - *Auto mode* picks the best available transport and falls back gracefully.
- **QR / PIN pairing** — the QR carries only a session id and a short-lived
  random token (5 min TTL). A 6-digit PIN works when cameras are unavailable.
  Unknown devices require explicit approval; trusted devices can auto-accept.
- **Multi-device transfer** — one sender fans out to many receivers; every
  receiver gets its own progress, speed, ETA and retry/cancel controls.
- **Real progress, never simulated** — speeds and ETAs are computed from
  acknowledged bytes only (EMA-smoothed live speed + session average).
- **Robust transfer engine** — streaming I/O with bounded in-flight chunks,
  SHA-256 integrity verification, resume of interrupted transfers from the
  receiver's stored offset, pause/resume/cancel, duplicate-file policies
  (replace / keep both / skip), capacity pre-checks, atomic finalization via
  `.vibeshare_part` temp files.
- **APK sharing** — searchable installed-app list; base-APK export with honest
  handling of split/App Bundle apps ("APK extraction is unavailable…").
- **Transfer history** — local-only Room database; delete one, clear all.
- **In-app updater** — reads a configurable JSON manifest, verifies SHA-256
  before offering install, and always hands installation to the platform
  installer (never silently installs).
- **Foreground service** — active transfers survive screen lock where Android
  allows, with a progress notification (pause/resume/cancel actions).
- **Premium UI** — original VibeShare design: deep-space dark theme, true
  AMOLED theme, violet→cyan gradients, pulsing discovery radar, animated
  splash via the SplashScreen API, reduced-motion-friendly, TalkBack labels
  everywhere. State is never communicated by color alone.
- **Developer screen** — hidden behind 7 taps on Settings → About → version:
  transport, local IP, P2P state, lifecycle, throughput, chunk size, protocol
  version. No secrets are ever displayed.

## Architecture

Ten Gradle modules, MVVM + Clean Architecture. The entire protocol, crypto and
transfer engine are **pure Kotlin (JVM)** and unit-tested without an emulator.

```
app ─── ui ─── data ─── domain ─── core
 │        │      │        ▲
 │        │      └─ transfer ── pairing
 ├─ discovery    ├─ storage
 └─ apps         └─ (Android libraries)
```

Full details in [ARCHITECTURE.md](ARCHITECTURE.md).

## Building

Requirements: JDK 17, Android SDK (platform 35, build-tools 35).

```bash
git clone https://github.com/ai-multitool-v1/VibeShare.git
cd VibeShare
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest test # unit tests (no emulator needed)
./gradlew lint                   # Android lint
./gradlew assembleRelease bundleRelease  # release APK + AAB
```

Artifacts land in `app/build/outputs/`. CI does all of this automatically —
see [`.github/workflows/android.yml`](.github/workflows/android.yml).

## Android requirements & permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Local TCP sockets and network monitoring |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Wi-Fi Direct management |
| `NEARBY_WIFI_DEVICES` (33+, neverForLocation) | Local Wi-Fi discovery without location |
| `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` (≤ Android 12) | Platform requirement for P2P/NSD on older OS versions |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` | Progress notification for long transfers |
| `WAKE_LOCK` | Keep radios awake while streaming |
| `WRITE_EXTERNAL_STORAGE` (≤ Android 9) | Legacy public-Downloads destination |
| `QUERY_ALL_PACKAGES` | App sharing is a core feature (see SECURITY.md) |
| `REQUEST_INSTALL_PACKAGES` (optional) | In-app updater hand-off to the platform installer |

## Transfer modes

| | Local Wi-Fi (Mode B) | Wi-Fi Direct (Mode A) |
|---|---|---|
| Setup | Same Wi-Fi network | None (device-to-device link) |
| Discovery | mDNS/NSD (`_vibeshare._tcp.`) | `WifiP2pManager` peer scan |
| Internet needed | No | No |
| Best for | Home/office LANs, multi-device | Travel, outdoors, isolated |

`Settings → Transfer mode`: **Auto** (default), **Wi-Fi Direct**, **Local Wi-Fi**.
Auto prefers Local Wi-Fi when a local network is present and falls back to
Wi-Fi Direct where the hardware supports it.

## GitHub Actions & releases

- **Every push/PR**: unit tests, lint, debug APK artifact.
- **Tag `v*`**: signed release APK + AAB + `SHA256SUMS.txt` attached to an
  automatically created GitHub Release.

Signing uses repository secrets (never a per-run generated key):

```
VIBESHARE_KEYSTORE_BASE64
VIBESHARE_KEYSTORE_PASSWORD
VIBESHARE_KEY_ALIAS
VIBESHARE_KEY_PASSWORD
```

Setup and the release process are documented in [RELEASE.md](RELEASE.md).
Keep the same keystore forever: Android refuses updates signed with a
different key.

## Updater configuration

The updater polls a JSON manifest (default: `release.json` at the repo root;
overridable in Settings for self-hosted distribution):

```json
{
  "versionCode": 2,
  "versionName": "1.1.0",
  "apkUrl": "https://github.com/ai-multitool-v1/VibeShare/releases/download/v1.1.0/vibeshare-1.1.0.apk",
  "sha256": "<sha256 of the apk>",
  "releaseNotes": "…",
  "fileSize": 13501154
}
```

Checksum mismatch ⇒ the download is deleted and no install is offered.
See [RELEASE.md](RELEASE.md) for the release checklist.

## Security notes

- Session keys: ephemeral ECDH P-256 + HKDF-SHA256 (salted with the pairing
  credential) → AES-256-GCM per frame with header AAD. Can be disabled in
  Settings for maximum throughput on trusted networks.
- Pairing tokens are random 128-bit values, registry-scoped, one-time expiry;
  nothing permanent ever enters a QR code or the wire.
- Filenames are sanitized (path-traversal safe); downloads finalize atomically
  only after SHA-256 verification; corrupt partials are always discarded.
- No analytics. No cloud endpoints. History and settings never leave the
  device. Read the full threat model in [SECURITY.md](SECURITY.md).

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Could not connect to the device." | Both devices on the same Wi-Fi, or use Wi-Fi Direct; check the receiver is on the Receive screen. |
| Devices don't appear | Grant Nearby devices permission; Android 12- devices also need Location (OS requirement for discovery). |
| "Pairing code expired." | PIN/QR live for 5 minutes — generate a new one on the receiver. |
| Transfer paused forever | Both screens must stay reachable; enable "Keep screen awake". |
| Resume didn't kick in | The receiver keeps partial data for 24 h; transferring a *renamed* file restarts from zero by design. |
| "APK extraction is unavailable for this application." | The app is a split/App Bundle app; a single APK cannot represent it. |
| Slow speed | Disable per-frame encryption only on trusted networks; Wi-Fi Direct is typically 20–40 MB/s, local Wi-Fi depends on your router. |

Benchmarks (loopback, AES-256-GCM, CI-class hardware): 64 MB in ~2 s
(~31.7 MB/s sustained with framing + crypto). On real devices the LAN is the
bottleneck, not the engine.

---

VibeShare is an original work by SETBD. It is not affiliated with, and does
not copy the branding, assets or code of SHAREit, Xender, AirDrop or LocalSend.
