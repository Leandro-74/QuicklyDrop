# QuicklyDrop Protocol v1

> Status: alpha implementation of the Phase 1 specification. Wire identifier:
> `quicklydrop/1`. The wire identifier is stable from the first release and does not
> change with the commercial name of the application.

## 1. Scope

This document defines the contract between sender and receiver:

1. **Discovery** — payload delivered over NFC (HCE).
2. **Handshake** — Noise NK over TCP.
3. **Transport** — encrypted frames with a length prefix.
4. **Messages** — offer, accept, data, end, acknowledgement, cancel and error.

The protocol does not depend on Android. The reference implementation lives in
`:protocol` (pure JVM) and is tested without a device.

## 2. Conventions

- Integers are **big-endian**.
- Strings are **UTF-8** with a byte-length prefix (not a character count).
- `u8`, `u16`, `u32`, `u64` are unsigned integers.
- Every decoder validates length, version and limits before allocating.
- A malformed frame ends the session with `Error` and closes the socket.

## 3. Discovery (NFC)

The receiver emulates an HCE card with AID `F05144524F50`. The response to `GET DATA` is
the payload below followed by status word `0x9000`.

```
offset  size  field
0       1     version = 1
1       1     flags
2       1     nameLen (0..64)
3       n     name (UTF-8)
...     1     ssidLen (0..32)
...     n     ssid (UTF-8)
...     1     passLen (0..64)
...     n     pass (UTF-8)
...     2     frequency (MHz, 0 = unknown)
...     32    noisePublicKey (X25519)
```

### 3.1 Flags

| Bits | Field | Values |
|---|---|---|
| 0–1 | transport | 1 = P2P, 2 = hotspot, 3 = LAN |
| 2 | credentials present | 0/1 |
| 3 | Noise key present | 0/1 |
| 4–7 | reserved | 0 |

For the product alpha, `flags = 0b00001101` (P2P + credentials + key).

### 3.2 Limits

- Maximum size: `2 + (1+64) + (1+32) + (1+64) + 2 + 32 = 199` bytes.
- With the status word, the APDU response is 201 bytes, within the short APDU limit
  (~255 bytes). The HCE limit is the C20; if the payload grows, use chunking or extended
  APDUs, not a different codec.

### 3.3 Rules

- `version != 1` → the sender aborts with "incompatible receiver".
- `nameLen == 0` is invalid.
- `ssidLen == 0` or `passLen == 0` is valid only when bit 2 is 0.
- `frequency == 0` means "unknown"; the sender joins without a channel hint.
- Newlines in `name`, `ssid` and `pass` are allowed: the format is binary and does not
  use a line delimiter.
- The Noise key is the receiver's **ephemeral** static key for that receive window.

## 4. Handshake (Noise)

- Protocol: `Noise_NK_25519_ChaChaPoly_SHA256`.
- Prologue: `quicklydrop/1` (ASCII, no NUL).
- The sender is the **initiator**; the receiver is the **responder**.
- The receiver generates an X25519 key pair **per receive window** and publishes the
  public key in the NFC payload.
- The sender uses the NFC public key as the remote static key. An attacker without the
  NFC read cannot complete the handshake.

Messages:

```
M1 (sender → receiver): e, es       48 bytes
M2 (receiver → sender): e, ee       48 bytes
```

After M2 both sides call `Split()` and switch to ChaCha20-Poly1305.

Each handshake message is sent over TCP as:

```
u32 messageLength (1..1024)
u8[messageLength] message
```

The length prefix is not encrypted. It is only used during the handshake; after
`Split()`, frames use the transport format in section 5.

### 4.1 Why NK instead of XX

NK has 1 RTT and authenticates the **receiver** to the sender. The sender has no
persistent identity: its authorization is possession of the NFC payload (credentials and
key), which requires physical contact. XX would add a third message and a sender identity
the MVP does not use. If mutual identity or trusted devices are needed, the protocol
moves to v2 with XX.

### 4.2 Receive window

- The window opens when the receiver is armed and closes after a timeout (default 120 s)
  or after one completed session.
- On close, the receiver clears the NFC payload, removes the P2P group and drops the keys.
- A window accepts **one** session. A second connection is refused.
- The NFC payload is valid only while the window is open.

### 4.3 Noise limits

- Maximum Noise packet: 65535 bytes.
- Maximum plaintext per frame: 65519 bytes.
- The product uses data frames of up to 32 KiB.

## 5. Transport

TCP port: **8992**.

Each frame is sent as:

```
u32 ciphertextLength (1..65535)
u8[ciphertextLength] ciphertext
```

`ciphertext = Noise.encrypt(plaintextFrame)`, including the 16-byte tag.

The length prefix is **not** encrypted. It reveals only the size of each frame; content,
type and metadata are encrypted. There is no padding in v1.

Plaintext:

```
u8 type
u8 flags (0 in v1)
payload...
```

## 6. Messages

### 6.1 Offer (0x01)

```
u16 fileCount (1..1024)
repeat fileCount times:
  u16 nameLen (1..255)
  name (UTF-8)
  u64 size
  u16 mimeLen (0..127)
  mime (UTF-8)
```

`size` is the exact file size. The receiver rejects a size above the local limit
(default 10 GiB) or above the free space. The Android implementation sanitizes names and
limits them to 200 UTF-8 bytes before creating `FileMeta`.

### 6.2 Accept (0x02)

```
u16 fileIndex
u64 startOffset (0 in v1)
```

`startOffset` reserves resume support. In v1 the receiver always sends 0 and rejects
`Data` with an offset different from the expected one.

### 6.3 Reject (0x03)

```
u8 reason
u16 messageLen (0..512)
message (UTF-8)
```

Reasons: 1 = user, 2 = unsupported, 3 = busy, 4 = no space, 5 = invalid.

### 6.4 Data (0x04)

```
u16 fileIndex
u64 offset
u32 dataLen (1..32768)
data
```

### 6.5 End (0x05)

```
u16 fileIndex
u8[32] sha256
```

The SHA-256 covers the complete file content. The receiver computes it while writing and
compares. On mismatch it deletes the partial file and replies with `Ack` status 1.

### 6.6 Ack (0x06)

```
u16 fileIndex
u8 status (0 = ok, 1 = hash mismatch, 2 = write error, 3 = cancelled)
```

### 6.7 Cancel (0x07)

```
u8 reason (1 = user, 2 = timeout, 3 = error)
```

Either side may send it. The receiver deletes the partial file.

### 6.8 Error (0x08)

```
u16 code
u16 messageLen (0..512)
message (UTF-8)
```

Codes:

| Code | Meaning |
|---|---|
| 0x0001 | invalid version |
| 0x0002 | invalid frame |
| 0x0003 | unexpected frame |
| 0x0004 | file too large |
| 0x0005 | no space |
| 0x0006 | hash mismatch |
| 0x0007 | write error |
| 0x0008 | cancelled |
| 0x0009 | timeout |
| 0x000A | internal error |

Types 0x09–0xFF are reserved. An unknown type produces `Error 0x0002` and closes the
session.

## 7. Transfer state

`TransferStateMachine` validates one file at a time:

```
IDLE → OFFER_SENT → ACCEPTED → SENDING → AWAITING_ACK → COMPLETED
                         ↘ FAILED     ↘ CANCELLED
```

- `FAILED` and `CANCELLED` are terminal.
- The session owns the loop over files and decides whether a failure aborts the rest.
- The sender and receiver both use the same sequence; the receiver opens the sink before
  sending `Accept` and aborts it on every failure path.

## 8. Flow

```
receiver: open window (P2P group + keys + NFC + TCP server)
sender:   read NFC, join group, connect TCP
both:     Noise NK (M1, M2, Split)
sender:   Offer
receiver: validate metadata and storage, send Accept (startOffset = 0)
sender:   Data*
sender:   End(sha256)
receiver: write, verify hash, send Ack
repeat for each file
both:     close the session
receiver: close the window
```

In the MVP the receiver **accepts automatically** when the handshake completes. There is
no accept dialog. Security comes from physical contact: without reading the NFC, the
sender has neither the key nor the credentials. The user can cancel from the notification.

## 9. Compatibility

- The receiver accepts only the same major version (`quicklydrop/1`).
- The Noise prologue already binds the version: different versions fail the handshake
  with a clear message, without interpreting unknown bytes.
- New fields must be added in a new version, not as an ignored suffix.
- The v1 decoder rejects trailing bytes at the end of a frame.

## 10. Security invariants

1. No file byte or metadata travels outside Noise.
2. The receiver's public key comes from NFC and is the only key the sender accepts.
3. Keys are ephemeral per window; no session key is persisted.
4. The receiver accepts only one session per window.
5. File names are sanitized before touching MediaStore.
6. The hash is verified before a file is considered complete.

## 11. Pending

- NFC payload chunking if it grows beyond 255 bytes (C20).
- Resume by offset (field reserved; future implementation).
- Multiple simultaneous senders (out of MVP).
- Frame padding to reduce traffic analysis (out of MVP).
- Official Noise test vectors in CI.
- Hardware validation of the complete flow.
