# Receiver lifecycle spike

> Phase B. Measures what Android allows when the receiver is touched by NFC without
> depending on the app being open. The result decides the receiver UX in the product.
>
> **Status:** the spike code exists in `:lab`, but the matrix has not been executed. The
> product alpha therefore takes the conservative path: the receiver must be armed with
> the app open, and the receive session runs in a foreground service. Execute this spike
> before promising background receive.

## 1. Questions

1. Is HCE invoked with the app in the foreground, in the background, killed and
   `force-stop`?
2. Is HCE invoked with the screen off and with the screen locked?
3. Is the process created from scratch? How long does it take until the first APDU?
4. Can the app start a foreground service (`connectedDevice`) from the tap?
5. Does the `ACTION_TRANSACTION_DETECTED` broadcast arrive? (It is documented for Secure
   Element; the spike checks whether it also applies to HCE.)
6. Can the Wi-Fi Direct group be created from the tap?
7. What is the difference between `requireDeviceScreenOn="false"` and `"true"`?

## 2. Components

| Component | Role |
|---|---|
| `SpikeApduService` | Permissive HCE, AID `F05144524F51`, `requireDeviceScreenOn="false"` |
| `SpikeScreenOnApduService` | Strict HCE, AID `F05144524F52`, `requireDeviceScreenOn="true"` |
| `SpikeForegroundService` | Minimal FGS, type `connectedDevice`, test notification |
| `SpikeReceiver` | Receiver for `android.nfc.action.TRANSACTION_DETECTED` |
| `SpikeReader` | Reader mode on the sender, chooses the permissive or strict AID |
| `SpikeStore` | Persists payload and options so they survive process death |
| `SpikeLog` | Logcat tag `QuicklyDropSpike` + `filesDir/spike.log` |
| `SpikePresence` | PID, process age, visible activity, FGS, screen, keyguard, Secure NFC |

The spike payload is test text. Real keys stay in memory only; the spike persists the
payload only to measure the dead-process scenario.

## 3. Commands

Install:

```bash
adb -s <serial> install -r lab/build/outputs/apk/debug/lab-debug.apk
```

Arm the receiver:

```bash
adb -s <serial> shell am start -n dev.leandro.quicklydrop.lab/.MainActivity \
  --es action spike-arm --es payload "SPIKE-RECV-1" --ez fgs true --ez group true
```

Read on the sender (permissive AID):

```bash
adb -s <serial> shell am start -n dev.leandro.quicklydrop.lab/.MainActivity \
  --es action spike-read --ez screenOn false
```

Read on the sender (strict AID):

```bash
adb -s <serial> shell am start -n dev.leandro.quicklydrop.lab/.MainActivity \
  --es action spike-read --ez screenOn true
```

Auxiliary commands:

```bash
# process and device state
adb -s <serial> shell am start -n dev.leandro.quicklydrop.lab/.MainActivity --es action spike-info

# baseline: FGS from a foreground activity
adb -s <serial> shell am start -n dev.leandro.quicklydrop.lab/.MainActivity --es action spike-fgs

# baseline: P2P group from the activity
adb -s <serial> shell am start -n dev.leandro.quicklydrop.lab/.MainActivity --es action spike-group

# clear logs
adb -s <serial> shell am start -n dev.leandro.quicklydrop.lab/.MainActivity --es action spike-reset

# disarm
adb -s <serial> shell am start -n dev.leandro.quicklydrop.lab/.MainActivity --es action spike-disarm
```

Collect:

```bash
adb -s <serial> logcat -d -s QuicklyDropSpike
adb -s <serial> shell run-as dev.leandro.quicklydrop.lab cat files/spike.log
```

Expected log events:

| Event | Meaning |
|---|---|
| `activity.start` / `activity.stop` | Visible activity changed |
| `hce.onCreate` | Android created the HCE service (the process may have started here) |
| `hce.apdu` | An APDU arrived |
| `hce.tap` | GET DATA with a valid payload |
| `hce.fgs` | FGS start attempt and result |
| `fgs.onCreate` / `fgs.started` / `fgs.failed` | FGS lifecycle |
| `receiver.transaction` | Transaction broadcast arrived |
| `hce.p2p` | `createGroup` attempt and result |
| `reader.start` / `reader.payload` / `reader.stop` | Sender side |

## 4. Matrix

For each row: arm, clear the log, prepare the state, tap, collect. Repeat with the
permissive and the strict AID.

| # | App | Screen | Secure NFC | FGS on tap | Group on tap | HCE? | APDU? | FGS? | Broadcast? | Group? | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | foreground | on, unlocked | off | yes | yes | | | | | | baseline |
| 2 | foreground | on, unlocked | on | yes | yes | | | | | | |
| 3 | background (home) | on, unlocked | off | yes | yes | | | | | | |
| 4 | background (home) | on, unlocked | on | yes | yes | | | | | | |
| 5 | process killed (`am kill`) | on, unlocked | off | yes | yes | | | | | | |
| 6 | process killed (`am kill`) | on, unlocked | on | yes | yes | | | | | | |
| 7 | `force-stop` | on, unlocked | off | yes | yes | | | | | | |
| 8 | foreground | off | off | yes | yes | | | | | | |
| 9 | foreground | off | on | yes | yes | | | | | | |
| 10 | background | off | off | yes | yes | | | | | | |
| 11 | background | off | on | yes | yes | | | | | | |
| 12 | foreground | locked | off | yes | yes | | | | | | |
| 13 | foreground | locked | on | yes | yes | | | | | | |
| 14 | background | locked | off | yes | yes | | | | | | |
| 15 | background | locked | on | yes | yes | | | | | | |

Each row is executed twice: permissive AID and strict AID. The "FGS on tap" and "Group on
tap" columns come from `--ez fgs` / `--ez group`; to isolate one variable, turn the other
off.

### 4.1 Preparing the states

```bash
# foreground: open the activity
adb -s <serial> shell am start -n dev.leandro.quicklydrop.lab/.MainActivity

# background: go to the home screen
adb -s <serial> shell input keyevent KEYCODE_HOME

# process killed: kills background processes (not the same as removing from recents,
# but it recreates the process)
adb -s <serial> shell am kill dev.leandro.quicklydrop.lab

# force-stop: disables the app and HCE until the app is opened again
adb -s <serial> shell am force-stop dev.leandro.quicklydrop.lab

# screen off / on
adb -s <serial> shell input keyevent KEYCODE_POWER

# unlock (device without a PIN)
adb -s <serial> shell wm dismiss-keyguard
```

For a screen locked with a PIN, the test needs the device already locked; the next unlock
is left to the user. Do not change the device password over adb.

## 5. Results

> Fill in after execution. One row per scenario and per AID.

### 5.1 Summary

| Question | Answer |
|---|---|
| HCE with the app in the foreground | |
| HCE with the app in the background | |
| HCE with the process killed | |
| HCE with `force-stop` | |
| HCE with the screen off | |
| HCE with the screen locked | |
| FGS from HCE | |
| FGS from the broadcast | |
| `createGroup` from HCE | |
| Does `requireDeviceScreenOn` make a difference? | |

### 5.2 Observations

-

## 6. UX decision

Criteria:

- If HCE and FGS work with the screen off or locked, the receiver can stay armed with the
  app closed and the tap is enough.
- If HCE works but FGS is blocked, the session can live in a bound service while NFC is
  active, but a long transfer needs another anchor (notification, tile or open app).
  Measure the process lifetime before deciding.
- If HCE works only with the screen on, the product requires the screen on to receive;
  the notification/tile becomes the way to arm.
- `requireDeviceScreenOn="true"` is more restrictive and does not protect against an
  attacker holding the phone; it only avoids spending power with the screen off. The
  measured result decides.

Decision: **pending execution. The current alpha requires the app open and armed.**
