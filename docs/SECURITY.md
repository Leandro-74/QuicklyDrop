# Security

> MVP threat model. Anything not listed here is not a security promise.

## 1. Decision

- Handshake: **Noise NK** (`Noise_NK_25519_ChaChaPoly_SHA256`).
- Implementation: `org.signal.forks:noise-java:0.1.1` (MIT, Signal fork, pure Java,
  no JNI). It is not an audited library; the mitigation is to test against official
  vectors and keep the surface small.
- OOB: the receiver's ephemeral public key travels in the NFC payload.
- Transport: ChaCha20-Poly1305, frames with a length prefix.
- BLE: out of the product. It stays in the lab.

### 1.1 Why Noise

Noise NK has 1 RTT, carries no certificate, does not depend on PKI and fits in a 96-byte
handshake. It is lighter than TLS for this case and does not require an external trust
chain: trust comes from the NFC tap.

### 1.2 What Noise does not solve

- **Sender identity.** NK authenticates the receiver. The sender is authorized by
  possession of the NFC payload, not by a persistent identity.
- **Length confidentiality.** The frame length prefix is visible.
- **Availability.** An attacker on the same network can open connections and consume
  resources; the receiver accepts one session per window and closes by timeout.
- **Malicious content.** The app is not an antivirus. The hash guarantees integrity, not
  safety of the file.

## 2. Threat model

| Threat | Scenario | Mitigation |
|---|---|---|
| Passive eavesdropping | Someone on the same channel reads traffic | Noise ChaCha20-Poly1305 |
| MITM | Attacker intercepts and swaps keys | Receiver key pinned by NFC; handshake fails |
| Unauthorized sender | Device on the network without touching the receiver | Without the NFC payload there is no key or credentials |
| Replay | Repeating a captured NFC payload | Keys and credentials rotate per window; short window |
| Path traversal | Name `../../etc/passwd` | Sanitization before MediaStore |
| Malicious name | NUL, control characters, 500 characters | Replacement, limit and truncation |
| Huge file | False `size` or 1 TB | Local limit + free space before Accept |
| Hash mismatch | Corruption or tampering | SHA-256 in `End`; partial file deleted |
| Second session | Another device tries to join | One session per window; closes after completion |
| Simple DoS | Many connections | Handshake timeout, one session, window closes |

## 3. Automatic accept

The receiver accepts without a dialog when **all** conditions hold:

1. The receive window is open.
2. The Noise NK handshake completed with the key published over NFC.
3. It is the first session in the window.
4. Metadata passes validation (name, size, free space).
5. The file hash matches at `End`.

The justification is physical: to complete the handshake, the sender had to read the
receiver's NFC. Someone who did not touch the phone has neither the key nor the
credentials. The user still has a **Cancel** action in the notification.

Explicit consequence: **anyone who can touch the receiver can send a file.** This is the
intended behavior, equivalent to "receive from anyone nearby", but with proof of touch.
It is not suitable for receiving from strangers in a public place without supervision.

## 4. Key lifecycle

- X25519 key pair generated for each receive window.
- Public key in the NFC payload; private key only in the receiver's memory.
- No session key on disk.
- On window close: clear the payload, discard the keys and remove the P2P group.
- No trusted devices in the MVP. If they are added, store only a public-key hash in the
  Keystore, never the private key.

## 5. Storage

- Files are written through `MediaStore.Downloads` into `Download/QuicklyDrop`.
- Names are sanitized against path separators and control characters and limited to 200
  UTF-8 bytes.
- Existing names get a `(1)`, `(2)` suffix.
- Files are inserted as pending and published only after the SHA-256 matches.
- Partial files are deleted on any failure path.

## 6. BLE

The lab BLE mode publishes SSID and password in the GATT characteristic. That is
acceptable only in the lab. For the product:

- BLE is not in the MVP.
- If it enters, it does discovery only; credentials and keys are exchanged after a
  handshake.
- It needs a SAS or another OOB mechanism, because BLE does not prove touch.

## 7. Alpha caveats

- The product has not been validated on physical devices.
- Official Noise test vectors are not in CI yet.
- No MITM test with a real proxy between two phones.
- The Android HCE and foreground-service behavior with the screen off is unverified.
- Dependency and license review is documented in `THIRD_PARTY_NOTICES.md`.

## 8. Pending

- Official Noise vectors in CI.
- MITM test with a real proxy between two devices.
- Review dependency and license metadata before store distribution.
- Log retention policy: never log SSID, passphrase, file name or key material.
- Review `allowBackup` and data extraction rules.
- Hardware validation of the receive service and MediaStore paths.
