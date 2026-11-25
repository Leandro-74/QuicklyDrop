# QuicklyDrop

[Português (Brasil)](README.pt-BR.md)

Proximity file sharing between Android devices. The transfer happens entirely between
the two phones: no account, no cloud, no server.

> **Status:** `0.1.0-alpha01`. The product app (`:app`) implements NFC discovery,
> Wi-Fi Direct, Noise NK, file transfer with SHA-256, MediaStore storage and a receiver
> foreground service. The protocol and core logic have automated tests, but the product
> has **not been validated on physical devices yet**. Treat this release as an alpha.

## What works

- **Receiver:** arms a Wi-Fi Direct group, publishes SSID, passphrase, frequency and
  Noise public key over NFC, waits for one sender and stores files in
  `Download/QuicklyDrop` through MediaStore.
- **Sender:** accepts files from the Android share sheet or the file picker, reads the
  receiver's NFC tag, joins the group, connects over TCP and sends one or more files.
- **Security:** Noise NK (`Noise_NK_25519_ChaChaPoly_SHA256`) over TCP, with the
  receiver's ephemeral public key delivered by the NFC payload.
- **Integrity:** SHA-256 per file; a file is committed only after the hash matches.
- **Names:** sanitized against path traversal and control characters; collisions get a
  `(1)`, `(2)` suffix.
- **Receiver UI:** foreground service with a progress notification and a cancel action.
- **Product UI:** share target, receive screen, send screen, progress and result.
- **Cancellation:** closing the active socket interrupts a blocked read or write.

## Known limitations

- **No hardware validation yet.** Phase 0 validated the transports in the lab, but the
  product integration has not run on a phone.
- The receiver must be armed with the app open. HCE and foreground-service behavior with
  the screen off or the app in the background is unverified.
- The sender must keep the screen open during the transfer; sending runs in the
  activity/ViewModel, not in a foreground service.
- No BLE, LocalOnlyHotspot, LAN fallback, Wi-Fi Aware or UWB in the product.
- No resume, history, trusted devices or Quick Share interoperability.
- Content URIs whose size cannot be resolved are rejected.
- The NFC payload and credentials live only in memory; if the process dies, arm again.
- This is an alpha: expect failures and report the device model, Android version and
  logcat output.

## Requirements

- Android 10 (API 29) or newer, with NFC and Wi-Fi Direct.
- Both phones need QuicklyDrop.
- JDK 21 and Android SDK 35 to build.

## Architecture

| Module | Type | Responsibility |
|---|---|---|
| `:app` | Android app | Compose UI, share target, permissions, send flow, receive screen |
| `:core` | Android library | NFC, Wi-Fi Direct, send/receive sessions, MediaStore, foreground service |
| `:protocol` | Pure JVM | Discovery payload, Noise NK, frame codec, secure channel, transfer state |
| `:lab` | Android app | Transport benchmarks and protocol experiments |
| `:diag` | Android app | Radio and capability diagnostics from Phase 0 |

The receiver arms a Noise responder key pair, creates the Wi-Fi Direct group, opens a
server socket and publishes the payload over HCE. The sender reads the payload, joins the
group, connects to the group owner and performs the Noise handshake. Files are sent as
`Offer`, `Accept`, `Data*`, `End` and `Ack`; the receiver commits to MediaStore only after
the hash matches.

- TCP port: `8992`.
- HCE AID: `F05144524F50`.
- Wire identifier: `quicklydrop/1`.
- Protocol details: [docs/PROTOCOL.md](docs/PROTOCOL.md).
- Architecture details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
- Security model: [docs/SECURITY.md](docs/SECURITY.md).

## Build

Requirements: JDK 21 and Android SDK 35 (platform + build-tools).

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If multiple JDKs are installed, point `JAVA_HOME` at JDK 21. The build currently does
not run on JDK 27 because Kotlin 2.0.21 cannot parse that version.

### Tests

```bash
./gradlew :protocol:test :core:testDebugUnitTest :app:testDebugUnitTest \
  :lab:testDebugUnitTest :diag:testDebugUnitTest

./gradlew :app:lintDebug
```

The suite includes protocol round-trips, malformed frames, Noise loopback, full transfer
loopback over TCP, cancellation, hash mismatch, storage sanitization, MediaStore,
P2P permissions, notification state and share-intent parsing. Instrumented tests and
hardware tests are still missing.

### Release APK

Copy `.env.example` to `.env` and fill in the keystore path, password and alias. The
keystore must live outside the repository.

```bash
cp .env.example .env
# edit .env
./gradlew :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. The GitHub
`Release` workflow builds and publishes the APK when a `v*` tag is pushed. It expects the
same values in repository secrets:

- `QUICKLYDROP_KEYSTORE_BASE64`
- `QUICKLYDROP_KEYSTORE_PASSWORD`
- `QUICKLYDROP_KEY_ALIAS`
- `QUICKLYDROP_KEY_PASSWORD`

Never commit `.env` or the keystore. Back up both; losing the keystore prevents updates
to an already published app.

## Using the app

### Receive

1. Open QuicklyDrop and tap **Receive files**.
2. Keep the receiver armed and tap the sender's phone to this phone.
3. The receiver creates the group and shows a foreground notification.
4. Files appear in `Download/QuicklyDrop`.

### Send

1. Share files from any app and choose QuicklyDrop, or tap **Send files** and pick files.
2. Tap **Scan receiver** and touch the receiver's phone.
3. Keep this screen open while the transfer runs.

## Laboratory

The lab still tests one connection path at a time: `LAN` (mDNS + TCP), `BLE`
(BLE + GATT + hotspot), `NFC` (tap + hotspot) and `P2P` (tap + Wi-Fi Direct). Each run
transfers 16 MB and reports setup time and MB/s.

```bash
PKG=dev.leandro.quicklydrop.lab
adb shell am start -n $PKG/.MainActivity --es action start --es mode P2P --es role SENDER
adb shell am start -n $PKG/.MainActivity --es action start --es mode P2P --es role RECEIVER
adb shell am start -n $PKG/.MainActivity --es action stop
adb logcat -s QuicklyDropLab:I
```

The lifecycle spike and its commands are documented in
[docs/SPIKE_LIFECYCLE.md](docs/SPIKE_LIFECYCLE.md). It has not been executed.

## Diagnostics

```bash
PKG=dev.leandro.quicklydrop.diag
adb shell am start -n $PKG/.MainActivity
adb shell am start -n $PKG/.MainActivity --es action bleadv
adb shell am start -n $PKG/.MainActivity --es action blescan
adb shell am start -n $PKG/.MainActivity --es action hotspot
adb shell am start -n $PKG/.MainActivity --es action join --es ssid "..." --es pass "..."
adb shell am start -n $PKG/.MainActivity --es action serve
adb shell am start -n $PKG/.MainActivity --es action send:192.168.0.10
adb logcat -s QuicklyDropDiag:I
```

## Validation from Phase 0

Measurements from 2026-11-01, 16 MB transfer, devices about 20 cm apart. These numbers
come from the lab, not from the product app.

| Path | Setup | Throughput |
|---|---|---|
| BLE (discovery) | 0.1–0.6 s | — |
| NFC (tap → credentials) | 0.02 s | — |
| Wi-Fi Direct (tap → group) | 0.86 s (median) | 35–48 MB/s |
| LocalOnlyHotspot + `WifiNetworkSpecifier` | 7–10 s (system dialog) | 10–12 MB/s |
| Common LAN | 0.3–3.6 s | 1.6–5.7 MB/s |

Test devices:

| Device | Android | Notes |
|---|---|---|
| Motorola Edge 50 Ultra | 17 (SDK 37) | Wi-Fi Direct; no `wifi_aware` service; UWB present |
| Samsung Galaxy S21 (SM-G991B) | 15 (SDK 35) | Wi-Fi Direct; Wi-Fi Aware available |

## Roadmap

- [x] Phase 0 — validate hardware, choose transports, measure throughput
- [x] Protocol v1 — discovery payload, Noise NK, framing, transfer state
- [x] Lifecycle spike code — execution still pending
- [x] Product alpha — NFC + Wi-Fi Direct send/receive
- [ ] Hardware validation of the product
- [ ] Hotspot/LAN fallback, resume, history and trusted devices
- [ ] Quick Share interoperability (optional)

## License

[GPL-3.0](LICENSE). Derivative projects must remain open.
