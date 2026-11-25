# QuicklyDrop — Analysis and evolution proposals

> Historical record of the Phase 0 lab work and corrections. For current product status, see [README.md](README.md).

> **01/11/2026 · analysis and record document.** The first round of corrections and
> experiments was run on both devices. What was accepted, what was rejected, and the
> measured number for each item are in section 1.1.

---

## 1. Quick overview

The lab has already proven the main path: NFC delivers the credentials, the receiver creates the
Wi-Fi Direct group at 5745 MHz, the sender joins directly with SSID/password, and TCP sustains
35–48 MB/s with a setup of ~0.86 s median. What is missing is not "making it work"; it is turning
the experiment into a product.

Priorities, in order:

1. **Measure better before optimizing.** Create a benchmark harness and fix `setupMs`,
   which today shows the total time instead of the connection time.
2. **Fix the `WifiLock`.** The `WAKE_LOCK` permission is missing; the lock was probably never
   acquired and the log says it is active. This can change the conclusion about the hotspot.
3. **Real cancellation.** "Stop" during the transfer does not close the active socket; the
   thread and the radio keep working.
4. **Unify the transfer path.** LAN and P2P use different code; one has ACK, the other does not,
   and the numbers are not comparable.
5. **Start the real MVP.** Real file, share sheet, MediaStore, hash, progress and
   cancellation — the 16 MB lab has served its purpose.
6. **Encryption before exposing it outward.** The lab transfers cleartext; the product needs a
   handshake, session key, and confirmation.

Status: items 1–4 were applied and validated; item 5 is the next step and 6 comes before
any use outside the lab.

### 1.1 Validation result

**Corrections.** All verified on the device, with an automated test when the logic is pure.

| Item | What was done | Result |
|---|---|---|
| C1/E1 | `WAKE_LOCK` declared and lock failure logged | lock appears in `dumpsys wifi`, but `mPowerSaveDisableRequests` remains 0 and the rate does not change (11.6 vs 11.5 MB/s). Kept: it was a silent failure. |
| C2 | `stop` closes the active socket | stopping mid-transfer frees port 8992 and leaves no group; loopback test. |
| C3 | ACK timeouts (10 s) and inactivity timeouts (30 s) | sender and receiver fail with a clear message; 2 loopback tests. |
| C4 | `setupMs` separated from total | both ends report the same setup; covered by test. |
| C5/E4 | LAN uses the same `TcpLink` | sender and receiver within 1% (e.g., 2.5 vs 2.5 MB/s); 1 mDNS failure in 6 runs. |
| C6 | chips accept switching after an error | state returns to `IDLE`; 3 tests. |
| C7 | `onDestroy` stops the controller | leaving through the system tears down the group and the port. |
| C8 | hotspot password masked | log shows `hotspot ready (AndroidShare_xxxx / ***)`. |
| C9 | per-version permissions | on API 35 only the "nearby" ones; location denied and P2P/BLE/hotspot work. |
| C11 | `P2pCleanup` always as fallback | "Stop" removes the group even when `removeGroup` fails; 2 s timeout. |

C10 (P2P without NFC) and C12 (documentation) remain: C10 is a product feature, C12 was done
in this round.

**Experiments.**

| ID | Hypothesis | Measurement | Decision |
|---|---|---|---|
| E1 | `WAKE_LOCK` improves the hotspot | 11.6 vs 11.5 MB/s; lock acquired, power save intact | fix kept, gain **rejected** |
| E2 | closing the active socket makes "Stop" immediate | stop < 1 s, port free, no stale group | **accepted** |
| E3 | separating `setupMs` fixes the reading | final setup equal to "connected in X ms" | **accepted** |
| E4 | unifying LAN equalizes both ends | difference < 1% between sender and receiver | **accepted** |
| E5 | P2P broadcast reduces setup | median 1.14 s → 0.86 s; worst case 2.16 s → 0.95 s | **accepted** |
| E7 | 8 MB socket buffer improves the rate | 40.0 vs 46.7 MB/s median | **rejected**; stays at 4 MB |
| E11 | channel 36 yields more than 149 | `createGroup` fails on the Edge and automatic picks 5745 MHz | **rejected**; stays at 149 |

Not run in this round: E6/E8 (real file and thermal require the product layer), E9
(persistent group), E10 (parallel streams), E12–E19.

---

## 2. What is already solid

- **Separation of responsibilities in the lab.** `NfcBeam`, `TcpLink`, `HotspotLink`,
  `P2pCleanup` and the modes have clear roles; the same NFC tap serves different transports.
- **Fast P2P path well documented.** Choosing SSID/password instead of `connect` by
  address + WPS eliminated `UserAuthorizingJoinState` and the intermittent dialog.
- **Group on fixed 5 GHz with fallback.** 5745 MHz is non-DFS and the code falls back to automatic if the
  device refuses.
- **ACK in TCP.** The sender waits for the receiver to drain the 16 MB; both ends report the same
  rate.
- **Old group cleanup.** `P2pCleanup` handles the case of a group that survives a force-stop.
- **Regression test for `P2pConfig`.** Small, but it protects the configuration that avoids the
  dialog.
- **Observable log and state.** `LabLog` + `StateFlow` + per-phase colors make testing over adb
  simple and repeatable.

---

## 3. Corrections and risks

### 3.1 High priority

#### C1. `WifiLock` without `WAKE_LOCK` — the lock was never acquired — **applied**

**Where:** `lab/src/main/AndroidManifest.xml` (missing permission) and
`HotspotLink.kt` (`acquireWifiLock`).

**Problem:** any use of `WifiManager.WifiLock` requires
`android.permission.WAKE_LOCK`. The lab manifest does not declare this permission. The code does
`runCatching { acquire() }` and then logs "Wi-Fi: low-latency lock active"
regardless of the result. In other words: the failure is swallowed and the log lies.

**Why it matters:** `PLANO.md` concluded that the low-latency lock does not disable power
save because `mPowerSaveDisableRequests` was at 0. If the lock was never acquired, that
conclusion may be wrong. The hotspot path may yield more than the 12.7 MB/s
measured, and the decision "P2P primary, hotspot backup" deserves to be revisited with the lock
working.

**Proposed fix:**

- declare `<uses-permission android:name="android.permission.WAKE_LOCK" />`;
- replace the silent `runCatching` with something that logs success or failure;
- optionally, also acquire the lock on the receiver during the transfer.

**How to test:** run the hotspot in both directions, 3 times each, before and after; check
`adb shell dumpsys wifi | grep -i -E "mPowerSaveDisableRequests|WifiLock"`; compare MB/s.
Criterion: if the rate rises consistently (>15%), update `PLANO.md` and reopen the
transport decision. If it does not change, the lock was not the bottleneck.

**Effort:** low. **Risk:** low.

#### C2. `stop()` does not interrupt an ongoing transfer — **applied**

**Where:** `TcpLink.kt` (`stop`), `LanMode.kt` (`stop`), and consequently `HotspotLink`,
`BleMode`, `NfcMode` and `WifiDirectMode`.

**Problem:** `finished` is only checked between phases. During `read`, `write`, `accept` or the
ACK wait, the thread is blocked. `pool.shutdownNow()` interrupts the thread, but socket I/O
does not respond to `interrupt`; `server.close()` releases `accept`, but not the already
accepted socket. The active socket is not stored in any field, so `stop()` cannot close it.

**Symptom:** pressing "Stop" mid-transfer leaves the thread and the radio working; on
restart, the test may find a busy port, an old connection or a stale group. In a product,
this is worse: the user cancels and the file keeps being sent/received.

**Proposed fix:**

- store the active socket (`@Volatile private var active: Socket?`);
- in `stop()`, close the active socket in addition to the `ServerSocket`;
- use a short `soTimeout` (e.g., 1 s) and check `finished` in the loops;
- in `send`, use a timeout when reading the ACK; in `receive`, an inactivity timeout.

**How to test:** start a transfer, call `stop` after 1–2 s, restart immediately;
repeat 5 times. Criterion: no stuck thread, no "Address already in use", the new run
works and the stop time is under 1 s.

**Effort:** low/medium. **Risk:** low.

#### C3. ACK read without timeout — **applied**

**Where:** `TcpLink.kt`, `send()`: `socket.getInputStream().read()` after sending.

**Problem:** if the receiver crashes, hangs or closes without sending the ACK, the sender is blocked
forever. The `Socket` has no `soTimeout` for that read.

**Proposed fix:** `socket.soTimeout = 10_000` before the ACK; on timeout, fail with a clear
message ("receiver did not confirm within 10 s").

**How to test:** loopback test where the "receiver" accepts the connection, reads the 16 MB and does not
respond; the sender must fail in ~10 s, not hang.

**Effort:** low. **Risk:** low.

#### C4. Final `setupMs` is the total time, not the setup — **applied**

**Where:** `TcpLink.kt` (final status of `serve`/`send`) and `LanMode.kt` (final status of
`sendPayload`/`receivePayload`).

**Problem:** `startedAt` is set at the start of the server/send; the final status uses
`System.currentTimeMillis() - startedAt`, which includes the entire transfer. The UI shows that
value next to MB/s, so after a 4 s transfer the displayed "setup" is ~4 s.

**Proposed fix:** store `setupMs` at connection time and reuse it in the final status;
separate `setupMs` from `totalMs` in `LabStatus`.

**How to test:** unit test with a fake clock; check on the device that the final value is the
same "TCP: connected in X ms" from the log.

**Effort:** low. **Risk:** low.

### 3.2 Medium priority

#### C5. LAN and P2P measure different things — **applied**

**Where:** `LanMode.kt` vs `TcpLink.kt`; `app/src/main/java/dev/leandro/quicklydrop/diag/LanTransfer.kt`
is a third copy.

**Problem:** `LanMode` has no ACK, uses default buffers, has a `Thread.sleep(500)` and computes the
send time without waiting for the receiver to drain. `TcpLink` has ACK, 4 MB buffers and no
sleep. The LAN and P2P numbers are not comparable, and the diagnostic `LanTransfer` repeats the
same logic with another payload size (32 MB).

**Proposed fix:** a single `Transfer` (socket + ACK + measurement + cancellation), used by
LAN, hotspot and P2P. `LanMode` only discovers and connects; `TcpLink` becomes the common
transport. The diagnostic `LanTransfer` can be removed or made to use the same code.

**How to test:** run LAN 3 times in each direction before/after; compare sender and
receiver rates. Criterion: both ends stay within ~5% and the completion time does not get worse.

**Effort:** medium. **Risk:** low.

#### C6. Mode/role chips enabled after an error, but with no effect — **applied**

**Where:** `LabController.kt` (`selectMode`/`selectRole` only accept `Phase.IDLE`) and
`MainActivity.kt` (`enabled = !running`, and `ERROR` does not count as "running").

**Problem:** after an error, the chips look clickable, but the click is ignored. The user
has to figure out on their own that they must press "Stop" first.

**Proposed fix:** accept selection in `IDLE` or `ERROR` (resetting the status to `IDLE`), or
explicitly disable the chips in `ERROR`.

**How to test:** unit test of `LabController` with a status in `ERROR`; clicking another
mode must be reflected in the state.

**Effort:** low. **Risk:** low.

#### C7. `MainActivity` does not stop the controller when destroyed — **applied**

**Where:** `MainActivity.kt` — there is no `onDestroy`.

**Problem:** leaving the app (back) during a test leaves `Handler`, sockets, hotspot and P2P group
alive; `LabController` holds the `Activity`. On rotation/multiwindow, the leak repeats.

**Proposed fix:** `onDestroy { controller.stop(quiet = true) }` or move the state to a
`ViewModel`/service. For the lab, `onDestroy` is enough.

**How to test:** start P2P, press back, check `dumpsys wifip2p` and `ss -ltn` that the group and
the port are gone.

**Effort:** low. **Risk:** low.

#### C8. Hotspot password in the log — **applied**

**Where:** `HotspotLink.kt`: `LabLog.add("hotspot ready ($ssid / $pass)")`.

**Problem:** the password goes to logcat and to the screen. In the lab it is acceptable; in the product,
it is not. The log also shows up in screenshots and bug reports.

**Proposed fix:** log only the SSID and mask the password (`***`); if debugging is needed,
use `BuildConfig.DEBUG` and a separate channel.

**How to test:** grep the logcat for "hotspot ready" must not contain the password.

**Effort:** low. **Risk:** low.

#### C9. Permissions requested in excess on Android 13+ — **applied**

**Where:** `MainActivity.kt` (`requiredPermissions`).

**Problem:** `ACCESS_FINE_LOCATION` is requested even on API 33+, where P2P/BLE use
`NEARBY_WIFI_DEVICES` and `BLUETOOTH_SCAN` with `neverForLocation`. This produces an unnecessary
location dialog and scares the user.

**Proposed fix:** request location only on API < 33; on API 33+, request only the
"nearby" permissions. Test on a clean install.

**How to test:** uninstall, install, open and check how many dialogs appear; the app must
work without location on API 35.

**Effort:** low. **Risk:** low.

#### C10. `WifiDirectMode` requires NFC even when P2P does not need it — **pending (product)**

**Where:** `WifiDirectMode.start()` calls `beam.problem()` and fails if NFC is off.

**Problem:** Wi-Fi Direct works without NFC; the lab only uses the tap as a trigger. A device
without NFC (or with NFC off) cannot test P2P.

**Proposed fix:** allow an alternative trigger (BLE, QR or a "connect to the last
peer" button) for P2P. For the product, NFC is the premium shortcut, not the only path.

**How to test:** turn NFC off, start P2P through the alternative trigger and measure the setup.

**Effort:** medium. **Risk:** low.

#### C11. Stale group can survive if `removeGroup` fails — **applied**

**Where:** `WifiDirectMode.stop()` and `LabController.stop()`.

**Problem:** `P2pCleanup` is only called when there is no `WifiDirectMode` in memory. If the
mode's `removeGroup` fails (framework busy), the group remains and the next run may
form another group.

**Proposed fix:** always schedule `P2pCleanup` as a fallback after `stop`, with a timeout;
or retry `removeGroup` with backoff on `BUSY`.

**How to test:** force a group and kill the app; restart and check that only one group exists and
that TCP connects to the right owner.

**Effort:** low/medium. **Risk:** low.

#### C12. Outdated documentation — **applied**

**Where:** `PLANO.md` and `README.md`.

**Concrete problems:**

- `PLANO.md` still says "Nothing has been implemented yet. No repository has been created."
- `PLANO.md` §5.3 says that "the sender brings up a LocalOnlyHotspot", but the measurement showed that the
  receiver should host.
- `PLANO.md` §6 (Phase 1) treats Wi-Fi Direct as a fallback, while §11 places it as primary.
- `PLANO.md` §10 talks about "before creating the repository", which already exists.
- `README.md` still shows P2P with "3.8–4.8 s" and "5–14.5 MB/s", numbers from the old path; the
  fast path is at ~0.86 s and 35–48 MB/s.
- `README.md` and `PLANO.md` do not record the 2.4 GHz experiment (already documented in §11, but the
  README does not point to it).

**Proposed fix:** update both documents and add a "Current state" with date,
numbers and a link to this file. It is cheap and avoids decisions based on stale data.

**Result:** `PLANO.md` and `README.md` were updated with the 01/11/2026 measurements and
`README.md` points to this document.

**Effort:** low. **Risk:** none.

### 3.3 Minor, but worth noting

| ID | Where | Problem | Fix |
|---|---|---|---|
| C13 | `TcpLink.send` | ~~`sendBufferSize`/`receiveBufferSize` outside `runCatching`~~ — **applied** | buffers now inside `runCatching` |
| C14 | `TcpLink` | ~~`server` and `startedAt` without `@Volatile`~~ — **applied** | fields annotated with `@Volatile` |
| C15 | `LabScreen` | `LaunchedEffect(lines.size)` stops scrolling when the log reaches 400 lines | use the last line or a monotonic counter |
| C16 | `BleMode` | `onCharacteristicReadRequest` ignores `offset` and the MTU; a payload larger than the MTU breaks | respond from the offset and truncate at the MTU |
| C17 | `BleMode` | picks the first advertisement above the threshold, not the strongest | 1–2 s window + pick the highest RSSI |
| C18 | `LanMode` | concurrent `resolveService` can return `FAILURE_ALREADY_ACTIVE` (the connection timeout was already fixed by `TcpLink`) | serialize resolves with retry |
| C19 | `NfcBeam.present` | does not check whether NFC is still on; silent failure | check `nfc.isEnabled` and report |
| C20 | `QuicklyDropApduService` | short HCE response limits the payload to ~255 bytes; no chunking | define a limit and, if it grows, use extended APDU/chunking |
| C21 | `NfcMode`/`WifiDirectMode`/`BleMode` | payload delimited by `\n`; an SSID/name with a newline breaks parsing | binary/TLV codec with per-field length (see §4) |
| C22 | `LabLog` | each line copies the entire list (`it + line`); under bursts, it allocates a lot | `ArrayDeque` + snapshot or line batching |
| C23 | `P2pConfigTest` | the frequency test works because API 35 writes the frequency into `groupOwnerBand`; it is fragile | keep it, but add a `toString`/reflection test and review when raising the SDK |

---

## 4. Structure: the lab must use the product code

Today there are **two** transfer implementations:

- `lab/TcpLink.kt` (LAN, P2P and hotspot, with ACK, timeouts and cancellation);
- `app/diag/LanTransfer.kt` (diagnostic, 32 MB, another copy).

And **three** credential parsers:

- `BleMode` (`ssid\npass`);
- `NfcMode` (`ssid\npass`);
- `WifiDirectMode` (`name\nssid\npass\nfrequency`).

This means that "the lab validated it" is not the same as "the product will behave like this". The
recommendation is to extract the shared core **when the MVP starts**, without creating ten modules
now:

```
:core:protocol   NFC/BLE payload, TCP framing, manifest, hash (pure Kotlin, testable on JVM)
:core:transport  P2P, LAN, hotspot, transfer, cancellation
:core:crypto     X25519/HKDF/AEAD/SAS (Tink)
:lab             test UI, consuming :core:transport
:app             product: share sheet, service, MediaStore, UI
```

Advantages:

- the lab measures exactly the code the product uses;
- a bug fixed in the transport applies to all modes;
- protocol tests run on the JVM, without a device;
- `:lab` can remain a separate app, but a thin one.

Splitting everything before the MVP is not worth it. Start with `:core:protocol` (pure) and
`:core:transport` (P2P + TCP). The diagnostic `:app` can be retired or become
`:tools:diag` when the product is born.

### Payload codec

Proposal for a versioned binary format, instead of lines:

```
magic "EDP1" | version (1 byte) | type (1 byte)
field: size (2 bytes) + UTF-8/bytes
...
```

Fields: `name`, `ssid`, `pass`, `frequency`, `token` (optional, for the product). Advantages:
it accepts SSIDs with arbitrary characters, has an explicit limit, allows evolving without breaking
the other side and is trivial to test. The same codec serves NFC, BLE and QR.

### TCP framing

For the product, the fixed 16 MB `TcpLink` becomes a protocol with:

- `HELLO` (version, capabilities);
- `MANIFEST` (number of files, name, size, MIME, SHA-256);
- `CHUNK` (offset/sequence + bytes);
- `ACK` (window/offset);
- `DONE`/`ERROR`;
- resume by offset and per-file hash.

Test the codec on the JVM with `PipedInputStream`/`PipedOutputStream` or loopback sockets.

---

## 5. Optimizations and experiments

### 5.1 First, the harness

Without repeatable measurement, every optimization becomes opinion. The 01/11/2026 round used ad
hoc `adb` scripts; the versioned harness remains the next step. Proposal:

- the lab accepts `--es runId <id>` and `--es payloadMB <n>` and emits one JSON line per run:
  `BENCH {"runId":...,"mode":"P2P","role":"SENDER","setupMs":...,"mbps":...,"groupMhz":...}`;
- a `scripts/bench.sh` script runs N iterations per direction, clears logcat, swaps the roles,
  collects the JSONs and generates a CSV with median and p95;
- the CSV also stores `sta_band`, `group_mhz`, temperature and app version;
- results live in `bench/results/` (versioned summaries; raw logs ignored).

Useful commands to follow along:

```bash
adb shell cmd wifi status
adb shell dumpsys wifip2p | grep -i -E "frequency|group|channel"
adb shell dumpsys wifi | grep -i -E "mPowerSaveDisableRequests|WifiLock"
adb shell dumpsys thermalservice | grep -i -E "status|temperature"
adb shell dumpsys batterystats --charged | grep -i quicklydrop
```

### 5.2 Experiment table

| ID | Hypothesis | How to measure | Criterion to accept | Effort |
|---|---|---|---|---|
| E1 | With `WAKE_LOCK`, the hotspot improves and the lock actually acts | 3 runs/direction before/after; `dumpsys wifi` | +15% rate or `mPowerSaveDisableRequests` > 0 | S |
| E2 | Closing the active socket makes "Stop" immediate | stop mid-transfer, restart 5x | stop < 1 s, no stuck port | S |
| E3 | Separating `setupMs` from `totalMs` fixes the reading | fake-clock test + 3 runs | final setup = "connected in X ms" | S |
| E4 | Unifying LAN on `TcpLink` equalizes both ends | 3 LAN runs/direction | sender and receiver within 5% | M |
| E5 | P2P broadcasts reduce setup vs 500 ms polling | 10 runs/direction | median < 1.0 s and better p95 | M |
| E6 | Zero-copy (`FileChannel.transferTo`) helps with a real file | 1 GB synthetic vs real file | +10% with no regression | M |
| E7 | 8 MB socket buffer improves throughput | 1/4/8 MB, 3 runs each | +10% | S |
| E8 | 16 MB hides thermal throttling | 4 GB continuous, rate every 10 s | stable rate > 15 MB/s | M |
| E9 | Persistent P2P group reduces setup | pre-create group, 10 taps | setup < 0.5 s without failures | M |
| E10 | Two TCP streams help with many files | 1 vs 2 vs 4 streams | +15% total | M |
| E11 | Channel 36/44 yields more than 149 in the environment | 3 runs per channel | difference > 10% | M |
| E12 | Choosing the strongest peer avoids wrong connections | 3 devices, 20 runs | 0 wrong connections | M |
| E13 | P2P without NFC broadens reach | NFC off, BLE/QR trigger | tap/BLE → TCP < 2 s | M |
| E14 | Receiving in the background works with a foreground service | screen off, app in background | > 90% success | L |
| E15 | Encryption costs little | handshake + 1 GB, AES-GCM vs ChaCha20 | < 100 ms setup, no rate drop | L |
| E16 | One sender serves two receivers | 3 devices, 1→2 | both complete; measure the drop | M |
| E17 | Target SDK 36/37 does not break the app | bump and run the matrix | no crash, correct permissions | M |
| E18 | CI catches regressions | GitHub Actions with build + tests + lint | green pipeline | S |
| E19 | Quick Share interop is viable | spike with Bada/NearDrop | send to the Edge without GMS | L |

**Result:** E1 kept the fix with no rate gain (11.6 vs 11.5 MB/s); E2, E3, E4 and E5
were accepted; E7 was rejected (40.0 vs 46.7 MB/s with 8 MB); E11 was rejected (the Edge refuses
5180 MHz and automatic picks 5745). Details are in section 1.1.

### 5.3 Details of the experiments I would run first

**E1 — `WAKE_LOCK` on the hotspot.** It has the highest leverage because it can invalidate a
conclusion from the plan. Run the hotspot with the permission and the fixed log; see whether
`mPowerSaveDisableRequests` moves from 0 and whether the rate rises. If it does, the hotspot stops being just a
backup and the transport decision must be reviewed with new numbers.

**E5 — broadcasts in P2P.** Today the sender calls `requestConnectionInfo` every 500 ms and
`requestPeers` every 500 ms. A `BroadcastReceiver` for `WIFI_P2P_CONNECTION_CHANGED_ACTION`
and `WIFI_P2P_PEERS_CHANGED_ACTION` tends to react faster and spend less. Measure the setup across 10
runs; if the median drops 100–300 ms, it is worth it.

**E6/E7/E8 — real throughput.** The current 35–48 MB/s are with 16 MB and no disk write. The
number that matters is a real 1–4 GB file, with writing to `MediaStore`, screen off and
continuous transfer. Before optimizing, find the real ceiling. Test `FileChannel.transferTo`,
a 1 MB buffer and an 8 MB socket; if nothing changes, the limit is the radio or the storage, not the
code.

**E9 — persistent group.** The ~0.86 s setup is dominated by group formation. Keeping the group
alive between transfers may bring it down to <0.5 s, but it costs battery, exposure and the risk of a
stale group. Test with a pre-created group and 10 taps; decide with battery and failure data.

**E13 — P2P without NFC.** NFC is great, but it does not exist on every device and can be
off. A BLE trigger (advertisement + GATT) or QR keeps P2P usable. Measure the setup and the
hit rate.

**E14 — background.** The lab keeps the screen on; the product must receive with the app in the
background and the screen off. Foreground service with type `connectedDevice`/`dataSync`,
notification, `WifiLock` and tests with battery saver/Doze on both devices. It is the experiment
that most exposes OEM fragmentation.

**E15 — encryption.** Before any external use, X25519 + HKDF + AEAD + SAS handshake.
Measure the cost in setup and throughput; if AEAD costs >10%, test AES-GCM with hardware
acceleration against ChaCha20-Poly1305. Do not roll your own crypto: use Tink (Apache 2.0, compatible
with GPL-3.0).

---

## 6. Product next steps

### 6.1 Recommended order

1. **Harness + cheap fixes** (C1–C9, C12). One to two weeks of work, but it unlocks
   all subsequent decisions.
2. **Protocol + codec + tests** (`:core:protocol`). Versioned payload, framing, manifest,
   hash. No UI.
3. **Real-file MVP.** Share sheet → P2P → `MediaStore/Downloads/QuicklyDrop`, progress,
   cancellation, hash, duplicate name. First milestone a human can use.
4. **Encryption + SAS.** Handshake, session key, confirmation; NFC as proof of presence.
5. **Foreground service + background.** Receive with the screen off; notification; cancel;
   survive Doze/battery saver.
6. **Proximity and shortcuts.** Card per RSSI with hysteresis, NFC tap, QR fallback, quick
   settings tile.
7. **Performance and robustness.** Zero-copy, 4 GB, resume, multi-file, multi-receiver.
8. **Polish and distribution.** Icon, theme, accessibility, signing, R8, CI, F-Droid.

### 6.2 MVP — what is in and what is out

**In:**

- share 1..N files through the Android share sheet;
- receive via P2P (receiver hosts), with the NFC tap as trigger;
- progress, cancel, clear error;
- write to `Downloads/QuicklyDrop/` via MediaStore, with `.part` and rename at the end;
- SHA-256 per file and verification on the receiver;
- receive screen with "Accept/Decline" (explicit confirmation remains mandatory).

**Out of the first MVP:**

- hotspot as fallback (only after E1);
- LAN/mDNS;
- QR;
- multiple receivers;
- resume across restarts;
- Quick Share;
- UWB/Aware.

### 6.3 Security — minimum design

- ephemeral X25519 on both sides; HKDF-SHA256 to derive the session key;
- AEAD (AES-GCM or ChaCha20-Poly1305) on all frames;
- 6-digit SAS derived from the transcript; both sides compare;
- NFC carries a single-use token that enters HKDF (proof of physical presence);
- P2P group with a random password per session; close the group after the transfer;
- never log password, token or key;
- threats: passive eavesdropper, active MITM, replay, malicious device nearby. P2P WPA2
  protects the link, but does not replace application authentication.

### 6.4 Background — what to test

- foreground service with `connectedDevice` + `dataSync` and notification;
- permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`;
- `WifiLock` (with `WAKE_LOCK`) during the transfer;
- screen off, app in background, battery saver, Doze, OEM restrictions;
- cancellation from the notification;
- process killed mid-transfer: what happens to the `.part` and to the P2P group.

### 6.5 Proximity — what to test

- BLE RSSI with moving average and hysteresis (show above −60 dBm, hide below −70 dBm);
- distances of 0.2 / 1 / 3 / 10 m, with the device in the pocket and in the hand;
- multiple devices: sort by RSSI and never connect to the first one that appears;
- NFC: antenna position varies a lot; show a visual hint and have QR as a plan B;
- UWB: optional, requires compatible hardware on both sides;
- Wi-Fi Aware: optional, requires hardware that exposes the service on both sides.

### 6.6 Performance — what to actually measure

- 1 KB, 10 MB, 1 GB and 4 GB; 1 and 200 files; folder;
- throughput on the sender and the receiver, setup, total, CPU, battery and temperature;
- writing to `MediaStore` vs direct file;
- screen off and app in background;
- cancel mid-transfer and resume;
- Wi-Fi STA at 2.4 GHz and 5 GHz on both devices;
- group channel (149 vs 36/44) and bandwidth;
- 1 vs 2 vs 4 TCP streams;
- hash at the end: how much it costs on 4 GB.

### 6.7 Distribution

- release signing with a keystore outside the repository;
- R8/minify and testing the release APK;
- CI with build + tests + lint;
- F-Droid is a good target (GPL-3.0, no GMS, no cloud);
- short privacy policy: nothing leaves the device;
- third-party license notices (Tink etc.);
- icon, theme, accessibility and texts in pt-BR/en.

---

## 7. Interesting ideas for later

- **Single-use token in NFC.** Instead of publishing static SSID/password, publish a token that
  the receiver validates and burns. The tap becomes a physical session key.
- **Trusted devices.** Store the other device's public key in the Keystore and skip
  confirmation on subsequent transfers, keeping encryption.
- **Receive without opening the app.** Quick settings tile + foreground service + HCE; the
  user just taps and the card appears.
- **Direct share sheet.** `ACTION_SEND`/`ACTION_SEND_MULTIPLE` with `ClipData` and temporary
  permissions; QuicklyDrop appears as a target without opening the app.
- **One to many.** The P2P group accepts more than one client; test 1 sender → 2 receivers,
  with per-receiver rate.
- **Real resume.** Manifest + persisted offset; if the app dies, it continues from the point
  where it stopped.
- **Text/links over BLE.** For small payloads, there is no need to bring up Wi-Fi; useful for
  clipboard and links.
- **QR as universal fallback.** When NFC/BLE fail, the receiver shows a QR with the token;
  the sender reads it and joins.
- **Smart group channel.** Measure 5 GHz channel occupancy and pick the least congested
  (requires location; test the UX cost).
- **UWB.** "Point and share" with centimeter ranging; optional, when hardware is available.
- **Wi-Fi Aware.** Discovery + ranging without infrastructure; optional, when both devices
  expose the service.
- **History with retry.** Transfer list, resend, open folder, share again.
- **"Receive only" mode.** Keep the device visible for X minutes, with a notification and a stop
  button.

---

## 8. What not to do now

- **Do not split the project into many modules.** `:core:protocol` and `:core:transport` are enough at the
  start; the rest can wait.
- **Do not adopt Hilt/Koin.** Manual injection remains sufficient for the current size.
- **Do not implement Quick Share before the MVP.** It is Phase 3 for a reason: high effort and a
  third-party protocol.
- **Do not invest in UWB/Aware as a base.** The hardware of the two devices does not line up.
- **Do not optimize the synthetic 16 MB transfer beyond what is needed.** The number that matters is
  a real file written to disk.
- **Do not use homemade cryptography.** Tink or an audited library.
- **Do not promise AirDrop.** It is already out of scope for hardware and product reasons.
- **Do not keep three copies of the transfer.** Unify before growing.

---

## 9. Decision criteria

For each experiment, record:

| Field | Example |
|---|---|
| ID | E1 |
| Hypothesis | with `WAKE_LOCK`, the hotspot goes from 12.7 to >15 MB/s |
| Change | permission + failure log |
| Measurement | 3 runs/direction, `dumpsys wifi`, MB/s |
| Criterion | +15% or `mPowerSaveDisableRequests` > 0 |
| Result | (to fill) |
| Decision | accept / reject / repeat |
| Commit | (if accepted) |

Keeping rejected ones documented (like the 2.4 GHz group) avoids repeating the same experiment.

---

## 10. Reference files

- `lab/src/main/java/dev/leandro/quicklydrop/lab/HotspotLink.kt` — `WifiLock`, hotspot, WNS.
- `lab/src/main/java/dev/leandro/quicklydrop/lab/TcpLink.kt` — transfer, ACK, buffers.
- `lab/src/main/java/dev/leandro/quicklydrop/lab/LanMode.kt` — mDNS + TCP (separate path).
- `lab/src/main/java/dev/leandro/quicklydrop/lab/WifiDirectMode.kt` — group, NFC, discovery.
- `lab/src/main/java/dev/leandro/quicklydrop/lab/P2pConfig.kt` — SSID/password/frequency.
- `lab/src/main/java/dev/leandro/quicklydrop/lab/P2pCleanup.kt` — stale group.
- `lab/src/main/java/dev/leandro/quicklydrop/lab/BleMode.kt` — BLE + GATT + hotspot.
- `lab/src/main/java/dev/leandro/quicklydrop/lab/NfcBeam.kt` — HCE + reader mode.
- `lab/src/main/java/dev/leandro/quicklydrop/lab/LabController.kt` — states and modes.
- `lab/src/main/java/dev/leandro/quicklydrop/lab/MainActivity.kt` — UI, permissions, adb.
- `app/src/main/java/dev/leandro/quicklydrop/diag/LanTransfer.kt` — third copy of the transfer.
- `PLANO.md` — technical plan and measurements.
- `README.md` — product vision and commands.
