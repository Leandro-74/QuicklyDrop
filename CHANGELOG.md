# Changelog

All notable changes to QuicklyDrop are documented here.

## 0.1.0-alpha01 — 2026-11-25

### Added

- Product Android app (`:app`) with a Compose receive/send UI and share target.
- NFC HCE beacon and reader mode for the product.
- Wi-Fi Direct transport with SSID/passphrase join and 5 GHz fallback.
- Noise NK handshake over TCP and encrypted frame channel.
- Sender and receiver engines with SHA-256 verification, cancellation and timeouts.
- MediaStore storage in `Download/QuicklyDrop` with name sanitization and collision
  handling.
- Receiver foreground service with progress notification and cancel action.
- Core, app and protocol test suites, including TCP + Noise loopback transfers.
- Release icon, signing configuration and GitHub Release workflow.

### Changed

- Renamed the project from EasyDrop to QuicklyDrop, including packages, AIDs, wire
  identifier, repository and documentation.
- Split the diagnostic app into `:diag` and introduced the `:core` Android library.
- Protocol wire identifier is now `quicklydrop/1`.

### Known limitations

- The product has not been validated on physical devices.
- Background receive with the screen off is unverified; the receiver must be armed with
  the app open.
- The sender must keep the screen open during the transfer.
- BLE, hotspot/LAN fallback, resume, history and Quick Share interoperability are out of
  this release.
