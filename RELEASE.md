# VibeShare — Release Process

This guide defines how VibeShare releases are built, signed and shipped so
that **every release uses the same signing key** and in-app updates keep
working as Android updates (not as new apps).

## 1. One-time: create the signing keystore

Generate **once**, then store safely. Never commit it, never regenerate.

```bash
keytool -genkeypair -v \
  -keystore vibeshare-release.keystore \
  -alias vibeshare \
  -keyalg RSA -keysize 2048 \
  -validity 10950 \
  -dname "CN=VibeShare, OU=SETBD, O=SETBD, C=BD"
```

Back up the keystore **and** the passwords in a password manager. Losing the
key means users can never update the installed app (Android signature check).

### 2. Store the key as GitHub Actions secrets

Repo → Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `VIBESHARE_KEYSTORE_BASE64` | `base64 -w0 vibeshare-release.keystore` |
| `VIBESHARE_KEYSTORE_PASSWORD` | keystore password |
| `VIBESHARE_KEY_ALIAS` | `vibeshare` |
| `VIBESHARE_KEY_PASSWORD` | key password |

The keystore is decoded into `build/` during CI and never written into the
repository or logs. Without the secrets, CI still builds (debug-signed
fallback) so the pipeline never breaks — but such builds are **not
shippable**.

## 3. Cut a release

1. Update `app/build.gradle.kts`:
   - `versionCode` ← previous + 1 (monotonic, never reuse)
   - `versionName` ← e.g. `1.1.0`
   - and mirror both in `app/.../ui/screens/SettingsScreen.kt` (`BuildVersion`).
2. Update `release.json` at the repo root (see section 4) — the updater reads
   it after the release exists.
3. Commit:
   ```bash
   git commit -am "release: v1.1.0"
   git tag v1.1.0
   git push origin main v1.1.0
   ```
4. GitHub Actions (`.github/workflows/android.yml`) then:
   - runs unit tests + lint,
   - builds the **signed release APK** and **AAB**,
   - generates `SHA256SUMS.txt`,
   - creates a GitHub Release with generated notes and attaches
     `app-release.apk`, `app-release.aab` and the checksums.

## 4. Publish the update manifest

Copy the released APK's checksum into `release.json` and commit:

```json
{
  "versionCode": 2,
  "versionName": "1.1.0",
  "apkUrl": "https://github.com/ai-multitool-v1/VibeShare/releases/download/v1.1.0/app-release.apk",
  "sha256": "<sha256 from SHA256SUMS.txt>",
  "releaseNotes": "What's new in 1.1.0…",
  "fileSize": 13501154
}
```

The app checks (Settings → About → Check for updates):
1. fetch manifest → 2. compare `versionCode` → 3. show notes/size →
4. download → 5. verify SHA-256 (mismatch ⇒ discard, never offer install) →
6. hand the APK to the platform installer with explicit user confirmation.

Self-hosting: point Settings → (dev builds) or the default endpoint to any
HTTPS URL serving the same JSON schema.

## 5. Verification checklist before publishing

- [ ] `./gradlew lint test` green locally
- [ ] Tag build green in Actions (test + lint + build + release)
- [ ] `apksigner verify --print-certs app-release.apk` shows the SETBD cert
      (`CN=VibeShare, OU=SETBD, O=SETBD, C=BD`)
- [ ] Install previous release → apply update → data preserved
- [ ] `release.json` committed with the new versionCode/sha256

## 6. Versioning policy

- `versionCode`: strictly increasing integer, +1 per release.
- `versionName`: semver-ish `MAJOR.MINOR.PATCH`.
- Protocol (`VTP`): bump `Constants.PROTOCOL_VERSION` on incompatible wire
  changes; older peers reject with "incompatible VibeShare version".
