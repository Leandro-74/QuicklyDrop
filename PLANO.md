# QuicklyDrop — Technical Plan

> Historical record of the Phase 0 validation and Phase 1 planning. For current product status, see [README.md](README.md).

> Planning document and measurement log from Phase 0 and early Phase 1. The protocol lab
> (`:lab`) was validated on both test devices; the product app was implemented later and
> is now an alpha. For current status, see [README.md](README.md).

---

## 1. Executive summary

QuicklyDrop is a proximity file-sharing app for Android. The minimum functional goal is
**QuicklyDrop ↔ QuicklyDrop** transfer, validated on a pair of test devices.

Interoperability with Motorola's Quick Drop: Quick Drop is Motorola's branding layer over
**Google Quick Share**, and Quick Share's network protocol can be implemented independently
(open source implementations that talk to Android already exist). The AirDrop/iPhone path via
`mosey` is not viable on stock Android, because it requires a specific kernel and radio stack.

Recommendation: **Phase 1 with our own protocol over NFC/BLE (discovery) + Wi-Fi
Direct/LocalOnlyHotspot (data); interoperability with Quick Share only after validating on
hardware; Apple is out of scope.**

> **Wi-Fi Aware:** it was not validated as the main transport for lack of hardware that exposes
> the `wifi_aware` service. It remains optional until validation. See section 3.3.

Decisions already closed:

- **Scope:** Android only. No iOS, no macOS, no AirDrop.
- **Phase 1:** QuicklyDrop ↔ QuicklyDrop only.
- **License:** GPL-3.0.

---

## 2. What Quick Drop is, in layers

| Layer | What it is | Relevance |
|---|---|---|
| `com.motorola.touchshare` app | ~101 KB stub. It only integrates Motorola's UI into the share sheet. | Low. Decompiling it reveals no protocol at all. |
| Google Quick Share (`com.google.android.gms` + Quick Share Extension) | The real protocol: discovery, pairing, encryption, transfer. | **High.** This is where interoperability happens. |
| Quick Share "mosey" (AirDrop interop) | Proprietary Rust stack, `mosey_server`, `wonder` interface, `wonder.ko`. | None. It is not accessible through a public API. |
| Manufacturer's Wi-Fi firmware/driver | `wondertap` on supported Broadcom chips. | None. We have no access. |

Practical conclusion: **interoperating with Quick Drop = interoperating with Quick Share.** Motorola's
"Drop" adds no protocol; it adds an NFC tap and group sharing.

---

## 3. Protocol landscape

### 3.1 Google Quick Share (the realistic target)

- Discovery via **mDNS** (`_FC9F5ED42C8A._tcp`) on the LAN, and via **BLE** (service UUID `fe2c`)
  when there is no common LAN.
- **UKEY2** handshake, **protobuf** frames (`OfflineFrames`), **AES-CBC + HMAC-SHA256** cipher,
  4-digit PIN for confirmation.
- TCP transport with length-based framing.
- Public reverse-engineering documentation: `NearDrop/PROTOCOL.md`.
- Existing implementations: **NearDrop** (macOS, Unlicense), **Packet** (Linux),
  **rquickshare** (Rust, GPL-3.0), **Bada** (Android/Kotlin, no GMS).

This is viable. It is what NearDrop and Packet already do.

### 3.2 AirDrop / AWDL / mosey (the unviable target)

- AirDrop runs over **AWDL**, an Apple radio layer that does not exist on Android.
- Google implemented interop via **mosey**, which requires:
  - `/vendor/bin/mosey_server` + `mosey.rc` + SELinux policy;
  - a virtual phy `wonder` and the `wonder.ko` module;
  - a Wi-Fi driver with `wondertap` support (Broadcom on Pixel 9/10 and some Samsung/BBK).
- The Edge 50 Ultra (Snapdragon 8s Gen 3) and the S21 (SD888/Exynos 2100) are **not** on the list
  of chips with `wondertap`. Without it, `wonder` is a virtual phy with no real RF.
- In other words: **you cannot do AirDrop on stock Android through a public API.** Not even with
  root, because the problem is the driver/radio, not permissions.

### 3.3 Wi-Fi Aware (NAN) — not validated

- Public API since Android 8 (`FEATURE_WIFI_AWARE`), with `publish`/`subscribe` and an IPv6
  link-local data path. Ranging (802.11mc/az) since Android 13.
- Tested on an S21: `isAvailable = true`, attach/publish/subscribe without error.
- Not validated on the other test device: `hasSystemFeature` = false, `getSystemService`
  returns `null`, and the `wifi_aware` binder does not appear in `service list` (only `wifi`,
  `wifip2p`, `wifirtt`, `wifiscanner`). The HAL reports `WIFI_FEATURE_AWARE` in `dumpsys wifi`,
  but the service is not registered in the framework.
- **Consequence:** NAN remains optional and off the main path until it is validated on
  hardware that exposes the service. There is no product decision against it.

### 3.4 Transport alternatives

| Transport | Advantage | Limitation |
|---|---|---|
| Wi-Fi Direct | High throughput, present on most Androids | Annoying pairing UX; conflicts with regular Wi-Fi |
| LocalOnlyHotspot | Simple, works on almost everything | No internet; takes down the sender's Wi-Fi on many devices |
| BLE | Cheap, ubiquitous discovery | Slow for data; background advertising is restricted |
| Wi-Fi Aware | Discovery + data + ranging, no infrastructure | **Not validated**: requires hardware that exposes the service; optional |
| NFC (HCE + reader mode) | Tap to connect | Bootstrap only; requires the screen on; not P2P (Beam is dead) |
| UWB | Centimeter-level ranging | **Not validated**: requires compatible hardware; optional |

### 3.5 Nearby Connections (Google Play Services API)

There is a ready-made API (`com.google.android.gms.nearby.connection`) that already handles
discovery, transport selection (BLE, Wi-Fi Direct, Wi-Fi LAN, Wi-Fi Aware) and encryption.

- **Pros:** prototype in days, transport solved, robust.
- **Cons:** it does not expose RSSI/distance (so no proximity gesture), ties the product to
  GMS, leaves little room to optimize throughput and resumption, and the payload format is rigid.

Recommended use: **benchmark spike**, not the product foundation.

---

## 4. Architecture options

| # | Option | Effort | Risk | Delivery |
|---|---|---|---|---|
| A | Own protocol, QuicklyDrop ↔ QuicklyDrop | Medium | Low | Full differentiator, total control |
| B | A + Quick Share/Quick Drop interop | High | Medium | Talks to any Android without the app |
| C | AirDrop/iPhone interop | — | — | **Out of scope** (product decision + hardware blocker) |
| D | Nearby Connections only | Low | Low | Works, but generic and GMS-dependent |

**Recommendation: A now, B later.** C is discarded for two independent reasons: the
user does not want Apple compatibility, and technically it would require a radio these
devices do not have.

---

## 5. Proposed architecture

### 5.1 Principles

1. **Cheap discovery, fast connection, fast transfer.** These are three different problems.
2. **No single path.** NFC (or BLE) triggers; if Wi-Fi Direct works, the receiver creates the
   group and the sender joins directly; if it fails, bring up a local hotspot; if both are
   already on a common LAN, use TCP over it; if everything fails, QR code.
3. **No server, no account, no cloud.** Everything local.
4. **Secure by default.** Nothing is transferred without explicit confirmation from the receiver.

### 5.2 Modules

```
app/                     Compose, share sheet, receive screen, progress
core/discovery/          BLE advertise+scan, mDNS, NFC (HCE + reader mode), QR
core/transport/          common LAN (TCP), LocalOnlyHotspot, Wi-Fi Direct, optional Aware
core/crypto/             ephemeral X25519, HKDF-SHA256, ChaCha20-Poly1305, 6-digit SAS
core/transfer/           framing, 1 MiB chunking, per-file SHA-256, resumption, backpressure
core/proximity/          BLE RSSI (+ UWB when available) → "how close" → UI trigger
```

### 5.3 Flow of a transfer

1. **Sender** advertises over BLE (and over mDNS, if already on a LAN) with an ephemeral
   identifier and the hash of its own public key.
2. **Receiver** listens; upon detecting sufficient proximity, it shows the sender's card.
3. **Handshake:** X25519 → HKDF → session key. Both derive a **6-digit SAS**.
4. **Confirmation:** the receiver confirms on screen; the SAS is checked (or the link is
   established by NFC tap, which removes the need to type).
5. **Transport:** the receiver creates the Wi-Fi Direct group (5 GHz, channel 149) and publishes
   name, SSID, password and frequency over the NFC tap; the sender joins directly with
   SSID/password, with no discovery, WPS or provision discovery. If P2P is unavailable, the
   receiver brings up a LocalOnlyHotspot and the sender joins via `WifiNetworkSpecifier`. If both
   are already on the same LAN, it opens TCP over it.
6. **Transfer:** 1 MiB frames, per-file SHA-256, per-window ACK, resumption by offset.
7. **End:** writes to `Downloads/QuicklyDrop/` via MediaStore, notifies, wipes buffers.

### 5.4 Security

- Always E2E. Never plaintext on the wire, not even on a trusted LAN.
- **Mandatory** confirmation from the receiver. No silent receiving.
- Ephemeral keys per session; nothing persisted beyond an eventual "trusted devices".
- 6-digit SAS compared visually, or NFC as proof of physical presence (stronger than any PIN,
  because it requires being centimeters away).

### 5.5 Proximity UX

The "bring closer" gesture has three quality levels:

1. **NFC tap** — touch the devices together. Best UX, most restrictive (screen on, sensor
   position varies greatly between models).
2. **UWB** — centimeter distance. Requires compatible hardware; it is an upgrade, not the
   baseline.
3. **BLE RSSI** — coarse, but it works on everything. Good enough for "it is near" (≈1 m),
   bad for "it is 30 cm away".

Ranging via Wi-Fi Aware was not validated (see §3.3) and does not count toward the product at
this phase.

Plan: RSSI as the universal baseline, UWB as an upgrade when both devices have it, NFC as a
premium shortcut.

### 5.6 Stack

- Kotlin, Jetpack Compose, Coroutines/Flow.
- `minSdk 29` (Android 10) for reach; fine-grained proximity features degrade gracefully.
- Foreground service with the `connectedDevice` + `dataSync` type for background discovery
  (mandatory on Android 14+).
- No Hilt at first — modules with manual injection are enough.
- **GPL-3.0 license** — which allows reusing GPL code (Bada, rquickshare, OpenDrop) in Phase 3,
  as long as the whole project stays open and copyright notices are kept.

---

## 6. Roadmap

### Phase 0 — Hardware validation (1–2 days)

Diagnostic app, no pretty UI, that reports and tests:

- per-device capabilities (`WIFI_AWARE`, `WIFI_DIRECT`, `NFC`, `BLE`, `UWB`);
- Aware: attach/publish/subscribe and data path with throughput;
- LocalOnlyHotspot: does it come up? which SSID? does the other device join?
- BLE: do advertise and scan work together?
- Wi-Fi Direct: discovery and group formation;
- coexistence with Wi-Fi connected to an AP.

**Exit criterion:** we know exactly which transport exists on each device.
Without that, everything else is guesswork.

**Results — S21 (SM-G991B, Exynos 2100, Android 15 / SDK 35):**

| Item | Result |
|---|---|
| Wi-Fi Aware | available (`isAvailable = true`) |
| attach / publish / subscribe | work, no error |
| Wi-Fi Direct | available |
| Wi-Fi RTT (802.11mc) | available |
| NFC + HCE | hardware present, HCE supported |
| BLE | present |
| UWB | absent |
| Root | KernelSU (`su -c id` → uid=0) |

**Results — Edge 50 Ultra (motorola edge 50 ultra, Android 17 / SDK 37):**

| Item | Result |
|---|---|
| Wi-Fi Aware | **absent from the framework** (`feature=false`, `wifi_aware` service not registered) |
| Wi-Fi Direct | available |
| Wi-Fi RTT (802.11mc) | available |
| NFC + HCE | hardware present, HCE supported |
| BLE | present, on |
| UWB | **present** |
| Root | no |

**Phase 0 decision:** Wi-Fi Aware stays off the main path for lack of compatible hardware
to validate it; it remains optional. The baseline transport is **NFC (trigger) + Wi-Fi Direct
(data)**, with LocalOnlyHotspot as a fallback and common LAN when one already exists.

**Measured transport (30 Oct 2026, devices ~20 cm apart):**

| Path | How | Result | Note |
|---|---|---|---|
| BLE (discovery) | Edge advertises, S21 scans | 13 advertisements in 10 s, RSSI −34 to −38 dBm | symmetric; service data arrives intact |
| Hotspot + WNS | Edge hosts, S21 joins | 9.1–10.6 MB/s (~85 Mbps) | 1 system dialog on the joining side |
| Common LAN | both on the home Wi-Fi | 4.0–4.4 MB/s | limited by the AP (−66 dBm, Rx 6 Mbps), not by the protocol |

Conclusions:

- **The minimum path closes:** BLE discovers, hotspot connects, TCP transfers.
- The `WifiNetworkSpecifier` dialog ("Connect to device") is acceptable and can be
  folded into the receiver's confirmation flow.
- Wi-Fi Direct stops being a necessity and becomes a throughput optimization.
- The Edge's hotspot coexists with regular Wi-Fi (`wlan1` stays connected while
  `wlan2` serves the AP) — the risk of taking down internet did not materialize on that device.

### Phase 1 — QuicklyDrop ↔ QuicklyDrop MVP (3–4 weeks)

- NFC discovery (tap) with BLE as an alternative; connection via Wi-Fi Direct; LocalOnlyHotspot
  or common LAN fallback.
- Handshake, SAS, confirmation, transfer with hash and resumption.
- UI: share sheet, receive card, progress, history.
- 1 file → N files → folder → large file (>2 GB).

### Phase 2 — Proximity and shortcuts (2 weeks)

- BLE RSSI calibrated for the bring-closer gesture; UWB as an upgrade when hardware is available.
- NFC tap-to-share (HCE `D2760101` + reader mode) as bootstrap.
- QR code as a universal fallback.
- "Trusted devices" to skip confirmation.

### Phase 3 — Quick Share / Quick Drop interop (optional, 4–6 weeks)

Only after Phase 1 is solid.

- Validate black-box: can Bada/NearDrop/Packet send to the Edge 50 Ultra?
- If so: port/reuse the protocol layer (NearDrop is Unlicense — the friendliest).
- Implement Quick Share's UKEY2 + protobuf + mDNS/BLE in the QuicklyDrop stack.
- Do **not** redistribute anything from Motorola or Google. Our own code or compatible OSS.

### Out of scope — Apple

No iOS, no macOS, no AirDrop. It is not only a product preference: it would require our own app
on iOS and validation on hardware that is not available. If that ever changes, the path would be
Wi-Fi Aware on iOS 26+, never AWDL.

---

## 7. Risks

| Risk | Prob. | Impact | Mitigation |
|---|---|---|---|
| Wi-Fi Aware unavailable on part of the hardware | Confirmed on the test devices | Medium | Does not depend on it; future validation on compatible hardware |
| LocalOnlyHotspot takes down the sender's Wi-Fi | High | Medium | Prefer common LAN when it exists; warn in the UI |
| Hotspot throughput below expectations | Medium | Medium | Measure in Phase 0; Wi-Fi Direct is the main path |
| BLE advertising restricted in background | High | Medium | Foreground service + persistent notification |
| Hotspot × Wi-Fi STA × P2P coexistence | Medium | Medium | Test in Phase 0; pick a free channel |
| Manufacturer fragmentation (Motorola/Samsung kill services) | High | Medium | Test with battery saver, Doze, OEM restrictions |
| Quick Share changes its protocol | Medium | Low (Phase 3) | Isolate the interop layer |
| Legal risk (decompiling/redistributing binaries) | Low if disciplined | High | Only our own code or OSS with a compatible license |

---

## 8. Test plan

**Minimum matrix:** Edge 50 Ultra ↔ S21 (root), in both directions.

Cases:

- 1 small file (1 KB), 1 medium (10 MB), 1 large (4 GB);
- 200 files at once;
- interrupted transfer (screen turns off, app goes to background, Wi-Fi turns off) → resumption;
- Bluetooth off, Wi-Fi off, airplane mode, battery saver, VPN active;
- hotspot active on the sender;
- 3+ devices in the same room (there must be no wrong pairing);
- receipt denied (nothing must be written).

Metrics: time to appear, time to connect, average throughput, success rate,
battery consumption per transfer.

---

## 9. Out of scope

- Decompiling and redistributing the Motorola app, the Quick Share Extension or components of
  `mosey`. Besides being legally risky, it does not help: the stub has no protocol.
- Promising AirDrop on Android. It is not a matter of effort, it is a matter of radio.
- Depending on cloud, account or server.
- Receiving a file without user confirmation.

---

## 10. Decisions

**Closed:**

1. Scope: **Android only**, no Apple.
2. Phase 1: **QuicklyDrop ↔ QuicklyDrop only**.
3. License: **GPL-3.0**.
4. Repository and lab already created; `:lab` is the validated codebase.

**Pending (before the product app):**

1. **Application ID** — `dev.leandro.quicklydrop` (the lab uses `.lab` and the diagnostic app uses `.diag`).
2. **Visual identity** — the app name is already closed; icon and palette are missing.
3. **Product layer** — share sheet, MediaStore, progress, hash and foreground service.
4. **Cryptography** — X25519 + HKDF + AEAD handshake before any use outside the lab.

---

## 11. Protocol lab (`:lab`)

Separate app from the diagnostic one, made to test one protocol at a time. The whole screen changes
color with the state: gray idle, blue searching, amber peer found, orange
connecting, green connected, dark green transferring, red error. Each test transfers
16 MB and shows setup time and MB/s.

### Modes

| Mode | How it identifies | How it connects | State |
|---|---|---|---|
| LAN + mDNS | mDNS | TCP on the common network | **works** |
| BLE + Hotspot | BLE (UUID + RSSI) | GATT delivers SSID/password, `WifiNetworkSpecifier` joins | **works** |
| NFC + Hotspot | NFC tap (own AID) | APDU delivers SSID/password, `WifiNetworkSpecifier` joins | **works** |
| Wi-Fi Direct | NFC tap delivers name + SSID/password | P2P group: receiver is owner, sender joins | **works** |

All four modes use the same `TcpLink` for the 16 MB: final ACK, inactivity timeout,
real cancellation and the same measurement. That is why the numbers are comparable to each other.

### Measurements (1 Nov 2026)

| Path | Setup | Throughput |
|---|---|---|
| LAN + mDNS | 0.3–3.6 s to connect | 1.6–5.7 MB/s (limited by the AP) |
| BLE: advertisement → RSSI | 0.1–0.6 s | — |
| BLE: GATT with credentials | 0.7–1.3 s | — |
| NFC: tap → credentials | **0.02 s** | — |
| Hotspot: Edge hosts, S21 sends | 7–10 s (system dialog) | **10.2–11.8 MB/s** |
| P2P: Edge owner, S21 sends (5 GHz) | tap → group **0.86 s** (median) | **41.3–47.9 MB/s** |
| P2P: S21 owner, Edge sends (5 GHz) | tap → group ~1.2 s | **35.5–36.2 MB/s** |

P2P is measured from the tap until the group is formed; TCP adds 18–150 ms. The old path
(discovery + WPS) took 3.8–4.8 s and delivered 5.0–14.5 MB/s.

### Validation of fixes and experiments (1 Nov 2026)

Fixes applied and verified on the device:

| Item | Result |
|---|---|
| `WAKE_LOCK` declared | the lock now appears in `dumpsys wifi`; `mPowerSaveDisableRequests` remains 0 and the rate does not change (11.6 vs 11.5 MB/s). Fix kept because the failure was silent. |
| `TcpLink` with active socket, 10 s ACK and 30 s idle | `Stop` mid-transfer closes the socket, frees port 8992 and leaves no group; 4 loopback tests. |
| `setupMs` separate from the total | both ends report the same setup; the total appears in MB/s. |
| LAN on the same `TcpLink` | sender and receiver stay within 1% (e.g., 2.5 vs 2.5 MB/s); 1 mDNS failure in 6 runs. |
| Chips after an error | switching mode returns the state to `IDLE`; covered by test. |
| `onDestroy` for the controller | going back via the system tears down the group and the port. |
| Hotspot password masked | log shows `hotspot ready (AndroidShare_xxxx / ***)`. |
| Permissions by version | on API 35 it only requests the "nearby" ones; location denied and P2P/BLE/hotspot keep working. |
| P2P cleanup on stop | `P2pCleanup` always runs as a fallback, with a 2 s timeout. |

Experiments decided:

| Item | Result | Decision |
|---|---|---|
| E5 — connection broadcast in P2P | tap → group dropped from ~1.14 s to ~0.86 s median; worst case from 2.16 s to 0.95 s | **accepted** |
| E7 — 8 MB socket buffer | median 40.0 MB/s versus 46.7 MB/s with 4 MB | **rejected**; 4 MB stays |
| E11 — channel 36 (5180 MHz) | `createGroup` fails on the Edge and the automatic fallback chooses 5745 MHz | **rejected**; 149 stays |

### Wi-Fi Direct: where the dialog came from

The intermittent dialog was not from `WifiNetworkSpecifier`; it came from P2P itself. In AOSP, when
the group owner receives a *provision discovery* (`P2P_PROV_DISC_PBC_REQ_EVENT`),
`WifiP2pServiceImpl` enters `UserAuthorizingJoinState` and calls `notifyInvitationReceived()`,
which opens the confirmation dialog. The lab's old path used exactly that:
`connect(deviceAddress)` with WPS PBC, without SSID/password. Sometimes the framework found a
persistent group and skipped discovery; that is why the dialog was intermittent.

The fix was to change the path:

- the receiver creates the group with a known SSID and password (`DIRECT-ED-xxxx` + random password)
  and publishes **name, SSID, password and frequency** over NFC;
- the sender calls `connect` with SSID/password, with no peer address. The framework recognizes
  `isConfigValidAsGroup()` and uses `p2pGroupAdd(config, true)`: it joins the group directly,
  without provision discovery, without WPS and without `UserAuthorizingJoinState`;
- the published frequency makes the sender scan only the group's channel (5745 MHz), instead of
  scanning all bands.

Result: 6 runs (3 in each direction), no dialog and tap → group in ~1.1 s. With the
`WIFI_P2P_CONNECTION_CHANGED_ACTION` broadcast, the median dropped to ~0.86 s (the 500 ms poll
remains as a safety net).

### Wi-Fi Direct: stale group and 5 GHz

- **A stale group survives force-stop.** After an interrupted test, both devices could end up
  owning different groups; the sender saw `isGroupOwner: true` and TCP connected to
  itself (`ECONNREFUSED`). `P2pCleanup` removes the group before starting and on stop, with a
  2 s timeout so the framework does not stall the test.
- **5 GHz on channel 149 (5745 MHz).** It is non-DFS, so it does not go through CAC. If the device
  cannot create the group on that frequency, it falls back to automatic. The actual group was
  5745 MHz in all runs.
- **The actual frequency only appears after `createGroup()`.** `requestGroupInfo()` is repeated
  every 500 ms until it returns the frequency; that is what goes in the NFC payload, not the
  requested one.
- **The sender STA's band is what matters.** The old variance with the Edge as receiver (11–26 MB/s)
  was STA+P2P contention: its STA was on 5 GHz (channel 36) and shared the radio with the group
  at 5745 MHz. With both STAs on 2.4 GHz, the 5 GHz group stays clean and the same pair delivers
  35–48 MB/s in both directions. It is not a Wi-Fi 6 vs Wi-Fi 7 difference.
- **A 2.4 GHz group does not solve the contention.** Forcing the group to 2412 MHz with the Edge as
  receiver dropped the rate to 2.8–3.9 MB/s in all three runs. The problem is the **sender's** STA:
  the S21 sends with its STA on 2.4 GHz and starts competing for the same band as the group. The
  receiver's STA band does not predict performance, and the receiver does not know it before the
  tap. That is why the group stays fixed at 5745 MHz; the heuristic based on the receiver's band
  was discarded.
- **TCP now has an ACK.** The sender used to send 16 MB, the socket buffer would accept it, and it
  announced "sent" before the receiver drained it. A confirmation byte at the end makes the sender
  wait for the receiver; both ends started reporting the same rate. The two 500 ms waits in
  `TcpLink` also went away: tap → transfer complete went to 1.8–2.6 s.

### What was discovered about the hotspot

**1. The receiving side should host the hotspot.** The `WifiNetworkSpecifier` client is in
power saving and **receives** slowly, but **transmits** well (229 Mbps Tx versus 17 Mbps
Rx at RSSI -10). Reversing hosting, the client only sends: the worst case rose from 2.7 to
6.0 MB/s and the best stayed at 13.0 MB/s.

**2. `removeCapability(NET_CAPABILITY_INTERNET)` was part of the problem.** Without it the network is
treated as "local-only", which drops Rx to 17 Mbps. Removing it, the same test rose from
0.3 to 2.0 MB/s. Sockets are bound to the network explicitly, so nothing leaks to the internet.

**3. The bottleneck is not TCP.** In 16 MB there were 11,764 segments and only 3 retransmissions; a
4 MB window did not change the rate. The limit is the radio waking and sleeping, not the window.

**4. `WifiNetworkSuggestion` does not work on the S21.** `addNetworkSuggestions` returns success
(status 0), but `cmd wifi list-suggestions` lists nothing and the network never connects.

**5. Hotspot setup is dominated by the system dialog.** `NetworkRequestDialogActivity`
("Connect to device?") waits for a tap; that is what makes setup vary from 5 to 47 s. The
low-latency `WifiLock` does not turn off power save on that network (`mPowerSaveDisableRequests 0`).
The lock was also never acquired for lack of `WAKE_LOCK`; with the permission it appears in
`dumpsys wifi`, but the rate stays the same (11.6 vs 11.5 MB/s median).

Consequence for Phase 1: **Wi-Fi Direct as the main transport** — no manual confirmation,
~0.86 s setup and 35–48 MB/s when the group does not compete with the STA for the band — and local
hotspot as a fallback for devices without P2P. The NFC tap remains the trigger in both. The group
stays fixed at 5 GHz (channel 149): the band that matters is the sender's STA, so there is no way to
choose the channel based on the receiver before the tap.
