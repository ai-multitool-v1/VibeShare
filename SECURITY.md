# VibeShare — Security Policy

**VibeShare (SETBD)** · Your files stay on your devices.

## Design goals

1. **No cloud.** Core transfer works fully offline. There are no analytics, no
   telemetry, no remote logging, no accounts. The only optional network
   endpoint is the in-app updater's user-configured manifest URL, which is
   completely separate from file transfer.
2. **No permanent secrets.** Nothing long-lived is embedded in QR codes, PINs,
   the wire protocol or the app binary.
3. **User consent.** Unknown devices can never connect without explicit
   approval; Android platform dialogs (permissions, install confirmation) are
   never bypassed.

## Pairing & session security

- Pairing tokens are cryptographically random 128-bit values (SecureRandom,
  base64url). PINs are uniform 6-digit values. Both:
  - live in an in-memory registry only (app restart ⇒ invalid),
  - expire after 5 minutes,
  - are purged eagerly on every lookup and on session end.
- The QR payload contains only: session id, device id/name, transport hint,
  host/port (local mode), the session token and expiry. No personal data
  beyond the user-chosen device name.
- Token proof: the joining client transmits `HMAC-SHA256(token, clientNonce)`
  instead of the token, so passive network observation never reveals the
  token. The PIN path necessarily transmits the PIN (short-lived, LAN-only
  trade-off documented here).
- Explicit approval is required for unknown devices. Trusted-device
  auto-accept is opt-in (Settings) and scoped to the user's device-id list.
- Approval requests time out (90 s) and reject by default.

## Transport encryption

- Ephemeral **ECDH P-256** key exchange per connection; shared secret derived
  with **HKDF-SHA256** using the pairing credential as salt and both handshake
  nonces as transcript info → **AES-256-GCM** (128-bit tags) for every
  non-empty frame, with the 12-byte frame header as AAD.
- A fresh 12-byte nonce per frame from SecureRandom.
- Tampering ⇒ `AEADBadTagException` ⇒ session torn down.
- Encryption can be disabled by the user (Settings) for maximum throughput on
  trusted networks; pairing approval still applies. Default: **on**.
- No private keys or passwords are hardcoded; no key material is logged.

## Data protection

- Received files stream into `.vibeshare_part` temp files and are finalized
  **atomically only after SHA-256 verification**; corrupt partials are deleted.
- Received-data paths are app-scoped by default (no storage permission).
  A user-selected SAF tree is optional and requested via the platform picker.
- Filenames are sanitized against path traversal (`/../`, separators, control
  characters); frame payload sizes are hard-capped (4 MB) against
  denial-of-service; chunk lengths are validated against remaining file size.
- Transfer history lives in a local Room database and never leaves the device.
- Device identity is a random install-scoped UUID — not any platform
  identifier.

## Data collection

None. No data leaves the device except file bytes and handshake metadata
transmitted **directly to the paired device** over the local network.

## Permissions justification

| Permission | Justification |
|---|---|
| `QUERY_ALL_PACKAGES` | App sharing requires listing other apps; without it the Apps category is unusable on Android 11+. VibeShare reads names/versions/paths only to export APKs the user explicitly picks. |
| `REQUEST_INSTALL_PACKAGES` | In-app updates. VibeShare verifies SHA-256, then delegates to the platform installer; it never installs silently. |
| `NEARBY_WIFI_DEVICES` (neverForLocation) | Discovery on Android 13+ without location access. |
| `WRITE_EXTERNAL_STORAGE` (≤ Android 9) | Legacy public Downloads destination on old OS versions. |

## In-app updater

- Manifest: user-configurable URL (default: the project's `release.json`).
- Only HTTPS manifests and APK URLs are expected; downloads land in cache,
  SHA-256 is verified against the manifest **before** any install offer;
  mismatches delete the file and surface an error.
- Installation always goes through Android's package installer with explicit
  user confirmation.

## Reporting a vulnerability

Open a private security advisory via GitHub → **Security → Advisories**, or
open an issue marked `security` with minimal detail and we will follow up
privately. Please include reproduction steps and affected versions.

## Scope

- In scope: VibeShare Android app, VTP protocol, pairing, updater.
- Out of scope: third-party Gradle dependencies (report upstream, but also
  notify us), physical-layer attacks against Wi-Fi itself (WPA3/802.11
  issues), and devices already compromised by the user's adversary.

## Known limitations (honest disclosure)

- PIN pairing transmits the PIN during the (pre-encryption) pairing phase;
  the 6-digit value is short-lived but brute-forceable on-link within its TTL
  by an attacker who captures PAIR_INIT. Prefer QR pairing in hostile
  environments.
- Wi-Fi Direct security inherits the platform's P2P group handling; VibeShare
  always layers its own session key on top.
- ECDH public keys are not authenticated by a certificate chain; security
  rests on the out-of-band credential (QR/PIN) — the standard TOFU+OOB model
  used by local-transfer tools.
