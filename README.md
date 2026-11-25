<h1 align="center">QuicklyDrop</h1>

<p align="center">
  Proximity file sharing between Android phones. No account, no cloud, no server.
</p>

<p align="center">
  <a href="https://github.com/Leandro-74/QuicklyDrop">English</a> | <a href="README.pt-BR.md">Português</a>
</p>

> ⚠️ Alpha release `0.1.0-alpha01`. The product has not been validated on physical devices
> yet.

## About

QuicklyDrop sends files between two Android phones by touching them together. NFC
introduces the receiver; Wi-Fi Direct carries the data. The transfer stays between the two
devices.

The project started as a Phase 0 hardware validation lab. The product alpha is the first
complete send and receive flow built on the measured transports.

### Known limitations

- The product has not been validated on physical devices yet.
- The receiver must be armed with the app open. Screen-off and background receive are
  unverified.
- The sender must keep the screen open during the transfer.
- BLE, hotspot and LAN fallback, resume, history, trusted devices and Quick Share
  interoperability are out of this release.
- Content URIs whose size cannot be resolved are rejected.

## Features

- Send one or more files from the Android share sheet or the file picker.
- Read the receiver's NFC payload with SSID, passphrase, frequency and Noise key.
- Join a Wi-Fi Direct group and connect over TCP on port `8992`.
- Protect the session with Noise NK (`Noise_NK_25519_ChaChaPoly_SHA256`).
- Verify each file with SHA-256 before committing it.
- Store files in `Download/QuicklyDrop` through MediaStore.
- Sanitize file names and resolve collisions.
- Keep the receiver alive with a foreground notification and a cancel action.
- Cancel a transfer by closing the active socket.

## Requirements

- Android 10 (API 29) or newer with NFC and Wi-Fi Direct.
- Both phones with QuicklyDrop installed.
- JDK 21 and Android SDK 35 to build.
- Android SDK build-tools 35.0.0.

## Installation

### Build from source

```bash
git clone https://github.com/Leandro-74/QuicklyDrop.git
cd QuicklyDrop
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
```

If multiple JDKs are installed, point `JAVA_HOME` at JDK 21. The build does not run on
JDK 27 because Kotlin 2.0.21 cannot parse that version.

### Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Tests

```bash
./gradlew :protocol:test :core:testDebugUnitTest :app:testDebugUnitTest \
  :lab:testDebugUnitTest :diag:testDebugUnitTest

./gradlew :app:lintDebug
```

The suite covers protocol round-trips, malformed frames, Noise loopback, full transfer
loopback over TCP, cancellation, hash mismatch, name sanitization, MediaStore, P2P
permissions, notification state and share-intent parsing. Instrumented tests and hardware
tests are still missing.

### Release APK

Copy `.env.example` to `.env` and fill in the keystore path, password and alias. The
keystore must live outside the repository.

```bash
cp .env.example .env
# edit .env
./gradlew :app:assembleRelease
```

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`. Without
signing values, Gradle writes an unsigned APK and the release workflow fails. The GitHub
`Release` workflow builds and publishes the APK when a `v*` tag is pushed. It expects the
same values in repository secrets:

- `QUICKLYDROP_KEYSTORE_BASE64`
- `QUICKLYDROP_KEYSTORE_PASSWORD`
- `QUICKLYDROP_KEY_ALIAS`
- `QUICKLYDROP_KEY_PASSWORD`

Never commit `.env` or the keystore. Back up both. Losing the keystore prevents updates to
an already published app.

## Usage

### Receive

1. Open QuicklyDrop and tap **Receive files**.
2. Tap the sender's phone against this phone to start.
3. The receiver creates the Wi-Fi Direct group and shows a foreground notification.
4. The files appear in `Download/QuicklyDrop`.

### Send

1. Share files from any app and choose QuicklyDrop, or tap **Send files** and pick files.
2. Tap **Scan receiver** and touch the receiver's phone.
3. Keep this screen open while the transfer runs.

### First run

1. Grant the nearby Wi-Fi permission. On Android 10 to 12, the app also asks for
   location permission, which Wi-Fi Direct requires.
2. Grant the notification permission. Without it the receiver still runs, but the
   progress notification may not appear.
3. Turn on NFC.
4. On Android 10 to 12, turn on location services for Wi-Fi Direct.

### Options

- **Receive files**: arms the receiver, creates the Wi-Fi Direct group, publishes the NFC
  payload and starts the foreground service.
- **Send files**: opens the system file picker.
- **Scan receiver**: enables NFC reader mode on the sender.
- **Cancel**: stops the active session and closes the socket.

## Project structure

```
QuicklyDrop/
├── app/          # product app: Compose UI, share target, permissions
├── core/         # NFC, Wi-Fi Direct, sessions, MediaStore, foreground service
├── protocol/     # pure JVM: discovery payload, Noise NK, frames, transfer state
├── lab/          # transport benchmarks and lifecycle spike
├── diag/         # Phase 0 capability diagnostics
├── docs/         # protocol, architecture, security, naming and spike docs
├── README.md
├── README.pt-BR.md
└── CONTRIBUTING.md
```

## How it works

The receiver arms one window at a time. It generates a Noise responder key pair, creates
a Wi-Fi Direct group and opens a TCP server on port `8992`. It publishes the group
credentials and the Noise public key in an HCE payload under AID `F05144524F50`.

The sender reads the payload over NFC, joins the group and connects to the group owner.
Both sides complete a Noise NK handshake and then exchange frames: `Offer`, `Accept`,
`Data*`, `End` and `Ack`.

The wire identifier is `quicklydrop/1`. The receiver accepts one session per window and
commits each file to MediaStore only after the SHA-256 matches. A partial file is deleted
on every failure path.

- Protocol details: [docs/PROTOCOL.md](docs/PROTOCOL.md)
- Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Security model: [docs/SECURITY.md](docs/SECURITY.md)
- Naming decision: [docs/NAMING.md](docs/NAMING.md)

### Validation from Phase 0

Measurements from 2026-11-01, 16 MB transfer, devices about 20 cm apart. The numbers come
from the lab, not from the product app.

| Path | Setup | Throughput |
|---|---|---|
| BLE (discovery) | 0.1–0.6 s | — |
| NFC (tap to credentials) | 0.02 s | — |
| Wi-Fi Direct (tap to group) | 0.86 s (median) | 35–48 MB/s |
| LocalOnlyHotspot + `WifiNetworkSpecifier` | 7–10 s (system dialog) | 10–12 MB/s |
| Common LAN | 0.3–3.6 s | 1.6–5.7 MB/s |

Test devices:

| Device | Android | Notes |
|---|---|---|
| Motorola Edge 50 Ultra | 17 (SDK 37) | Wi-Fi Direct; no `wifi_aware` service; UWB present |
| Samsung Galaxy S21 (SM-G991B) | 15 (SDK 35) | Wi-Fi Direct; Wi-Fi Aware available |

### Laboratory and diagnostics

The lab tests one connection path at a time: `LAN` (mDNS + TCP), `BLE` (BLE + GATT +
hotspot), `NFC` (tap + hotspot) and `P2P` (tap + Wi-Fi Direct). Each run transfers 16 MB
and reports setup time and MB/s.

```bash
PKG=dev.leandro.quicklydrop.lab
adb shell am start -n $PKG/.MainActivity --es action start --es mode P2P --es role SENDER
adb shell am start -n $PKG/.MainActivity --es action start --es mode P2P --es role RECEIVER
adb shell am start -n $PKG/.MainActivity --es action stop
adb logcat -s QuicklyDropLab:I
```

The lifecycle spike and its commands are in
[docs/SPIKE_LIFECYCLE.md](docs/SPIKE_LIFECYCLE.md). It has not been executed.

```bash
PKG=dev.leandro.quicklydrop.diag
adb shell am start -n $PKG/.MainActivity
adb logcat -s QuicklyDropDiag:I
```

### Roadmap

- [x] Phase 0: validate hardware, choose transports, measure throughput
- [x] Protocol v1: discovery payload, Noise NK, framing, transfer state
- [x] Lifecycle spike code: execution still pending
- [x] Product alpha: NFC and Wi-Fi Direct send and receive
- [ ] Hardware validation of the product
- [ ] Hotspot and LAN fallback, resume, history and trusted devices
- [ ] Quick Share interoperability (optional)

## Troubleshooting

- **NFC is off**: turn on NFC and try again.
- **Wi-Fi permission denied**: allow nearby Wi-Fi devices. On Android 10 to 12, allow
  location permission.
- **Location services are off**: turn them on for Wi-Fi Direct on Android 10 to 12.
- **The sender rejects a file**: the provider did not report the file size. Copy the file
  to local storage and share it again.
- **The receiver stops**: keep the app armed. Background receive with the screen off is
  unverified in this alpha.
- **The build fails on JDK 27**: use JDK 21 and set `JAVA_HOME`.
- **The release APK is unsigned**: fill `.env` or set the four release variables.
- **Lost the keystore**: you cannot update an installed app signed with the old key.
  Restore the backup or publish under a new package.
- **Any alpha failure**: open an issue with the device model, Android version and logcat.

## Contributing

Found a bug or have an idea? Open an
[issue](https://github.com/Leandro-74/QuicklyDrop/issues) or send a pull request. Read
[CONTRIBUTING.md](CONTRIBUTING.md) for the branch, test and commit workflow.

## License

This project is licensed under the GPL-3.0 license. See the [LICENSE](LICENSE) file for
details. Derivative projects must remain open.
