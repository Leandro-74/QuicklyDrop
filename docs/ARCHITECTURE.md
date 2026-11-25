# Architecture

> Current state: product app (`:app`), Android core (`:core`), pure JVM protocol
> (`:protocol`), lab (`:lab`) and diagnostics (`:diag`). The product is an alpha and has
> not been validated on physical devices.

## 1. Modules

| Module | Type | Responsibility |
|---|---|---|
| `:protocol` | Pure JVM | Discovery payload, Noise NK, frame codec, secure channel, transfer state |
| `:core` | Android library | NFC, Wi-Fi Direct, send/receive sessions, MediaStore, foreground service |
| `:app` | Android app | Compose UI, share target, permissions, send flow, receive screen |
| `:lab` | Android app | Protocol experiments, transport benchmarks, radio tests |
| `:diag` | Android app | Capability diagnostics and Phase 0 report |

`:app` is the product. The former diagnostic app was renamed to `:diag`; the lab remains
a development tool.

## 2. Dependencies

```
:protocol  (no Android)
    ↑
:core  (Android)
    ↑
:app  (Android)

:lab   (standalone experiments)
:diag  (standalone diagnostics)
```

Rules:

- `:protocol` imports neither `android.*` nor `androidx.*`.
- `:core` knows no UI.
- `:app` does not open sockets or touch MediaStore directly; it talks to `:core`.
- `:lab` and `:diag` are not dependencies of `:core` or `:app`.
- `:core` exposes the protocol types used by its public API through an `api`
  dependency on `:protocol`.

## 3. Responsibilities by layer

### `:protocol`

- `DiscoveryPayload` — binary NFC payload encode/decode.
- `NoiseHandshake` / `NoiseTransport` — NK handshake and ChaCha20-Poly1305.
- `NoiseStream` — length-prefixed handshake messages over a stream.
- `FrameCodec` — frame encode/decode.
- `SecureChannel` — framing plus encryption over `InputStream`/`OutputStream`.
- `TransferState` / `TransferStateMachine` — per-file state and transitions.
- Tests are pure JVM, with a real TCP + Noise loopback for the secure channel.

### `:core`

- `TcpSockets` — server/client sockets, buffer tuning, connect/accept timeouts.
- `AndroidP2pTransport` — blocking Wi-Fi Direct create/join/remove.
- `P2pConfig` / `P2pPermissions` — credentials and per-API permissions.
- `NfcBeacon`, `ApduHandler`, `QuicklyDropApduService`, `NfcReader` — HCE and reader mode.
- `TransferSender` / `TransferReceiver` — offer, data, hash, ack, cancel and errors.
- `SendSession` / `ReceiveSession` — P2P + TCP + Noise orchestration.
- `MediaStoreFileSink`, `ContentUriFileSource`, `DisplayName` — storage and names.
- `ReceiveService` — foreground receiver, notification and cancellation.
- `TransferStatus` — observable state for the UI.

### `:app`

- Share target (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`).
- File picker for local files.
- Receive screen and arm/stop flow.
- Send screen with NFC reader and progress.
- Permission flow for nearby Wi-Fi, location on older APIs and notifications.

## 4. Transfer flow

```
[receiver] arm
    → generate ephemeral Noise NK key pair
    → create Wi-Fi Direct group (5 GHz 5745 MHz, fallback to auto)
    → open TCP server on 8992
    → publish name, SSID, passphrase, frequency and Noise key over NFC/HCE
    → start foreground service

[sender] share sheet or file picker
    → read NFC payload
    → join Wi-Fi Direct group with SSID/passphrase
    → connect to the group owner on port 8992
    → Noise NK over TCP

both
    → Offer (all files)
    → Accept (one file at a time)
    → Data* (up to 32 KiB per frame)
    → End(sha256)
    → Ack
    → repeat for each file
```

On the receiver:

```
:core opens the MediaStore sink
    → validates name, size and free space
    → writes Data* and updates the digest
    → compares the SHA-256 at End
    → commits on match, aborts the partial file otherwise
    → sends Ack
```

On the sender:

```
:core opens each ContentResolver stream
    → declares name, size and MIME in Offer
    → streams Data* and updates the digest
    → sends End
    → checks Ack
```

## 5. Threading and timeouts

- The receiver service runs its session on a single worker thread.
- `AndroidP2pTransport` blocks the worker and waits for main-looper callbacks.
- TCP accept timeout: 120 s. Handshake timeout: 15 s. Idle timeout: 30 s.
- Cancellation closes the active socket, which interrupts blocked reads and writes.
- The sender runs inside the app ViewModel on `Dispatchers.IO`; the screen must stay open
  in this alpha.

## 6. What stays in the lab

- BLE discovery and GATT credentials (not part of the product).
- LocalOnlyHotspot and LAN benchmarks.
- Throughput, channel, frequency and coexistence tests.
- Wi-Fi Aware and UWB.
- The lifecycle spike.

## 7. Migration history

1. Phase A: created `:protocol` with codec, Noise and tests.
2. Phase B: implemented the lifecycle spike in `:lab` (not executed).
3. Phase C: created `:core`, moved TCP and session logic.
4. Phase D: implemented NFC, Wi-Fi Direct, storage and the foreground receiver.
5. Phase E: created the product `:app` and renamed the diagnostic to `:diag`.

Each step was merged with a green test suite. Hardware validation of the product remains
the next milestone.
