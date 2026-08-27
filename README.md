# Screen-off Airplane

Turns on airplane mode when the phone's screen has been off for 5 minutes, and
turns it back off when the screen comes on. Requires [Shizuku][shizuku]; does
**not** require root.

Built and tested against a Nothing Phone A024 on Android 16 (SDK 36).

[shizuku]: https://shizuku.rikka.app/

## Why Shizuku

Airplane mode cannot be toggled by a normal app. The obvious approach — grant
`WRITE_SECURE_SETTINGS` over adb and write `Settings.Global.AIRPLANE_MODE_ON` —
**does not work**: it flips the UI toggle but leaves the radios on. Telephony and
Wi-Fi do not observe that setting; they listen for the
`ACTION_AIRPLANE_MODE_CHANGED` broadcast, which is a protected broadcast that
only the system may send.

The working path is `ConnectivityManager.setAirplaneMode()`, which writes the
setting *and* sends the broadcast under system identity. It is gated on
`NETWORK_SETTINGS` / `NETWORK_AIRPLANE_MODE` / `NETWORK_SETUP_WIZARD`, all
signature-level and ungrantable to a normal app.

`com.android.shell` holds `NETWORK_SETTINGS`, which is why
`adb shell cmd connectivity airplane-mode enable` works. Shizuku runs commands
as the shell uid (2000), so the app shells out to that same command.

Verified on-device — the radios really do power down, this is not a cosmetic
toggle:

```
T0    setting=0
T+3s  setting=1 | Wifi is disabled | mRadioPowerState=0 | NET_DOWN
T+18s setting=1 | Wifi is disabled | mRadioPowerState=0 | NET_DOWN
disable ->
T+4s  setting=0 | mRadioPowerState=1 | NET_UP
```

## What it does not do

The original goal also included "turn off all CPUs except the first one". **This
is impossible without root** and was dropped:

- `/sys/devices/system/cpu/cpuN/online` is `root:root 0644` — shell uid cannot
  write it (verified: `Permission denied`).
- It is labelled `sysfs_devices_system_cpu`. Across the whole SELinux policy
  every domain gets read-only access (`domain.te`: `r_dir_file`); the only write
  grant is `recovery.te`. Not shell, not any app domain.
- No `cmd` surface or system API proxies CPU hotplug.

It would also not have helped. Idle cores already sit in deep cpuidle states,
and this device runs Qualcomm `core_ctl`, which had **2 of 8 cores active**
during normal use. Offlining cores mostly just slows wakeups.

## Hotspot guard

Enabling airplane mode tears down the softAP and disconnects every tethered
client. With the guard on (default), the toggle is skipped while clients are
connected.

`cmd wifi list-tethered-clients` would be the natural source but is denied even
to the shell uid (`SecurityException: Uid 2000 does not have access`). Instead
`Hotspot.kt` parses `dumpsys tethering` for interfaces in `TetheredState`, then
reads that interface's neighbour table:

```
$ ip neigh show dev wlan1
10.216.20.216 lladdr 5c:9b:a6:72:02:c7 REACHABLE
fe80::99:2096:1446:b0b3 lladdr 5c:9b:a6:72:02:c7 REACHABLE
```

Only `REACHABLE`/`STALE`/`DELAY`/`PROBE` count; `FAILED` and `INCOMPLETE` are
stale ARP noise. Entries are deduped by MAC, since one client appears as both v4
and v6. If the neighbour check fails the guard **fails safe** and assumes clients
are present rather than cutting someone off.

## Setup

1. Install Shizuku and start it (see below).
2. Install this app, then open it and tap **Grant Shizuku permission**.
3. Tap **Start watcher**. The watcher does nothing until started once.
4. Set both this app and Shizuku to **Battery: Unrestricted**
   (Settings -> Apps -> _app_ -> Battery). Without this, Nothing OS parks the
   app in standby bucket `RESTRICTED (5)` and the Shizuku binder connection
   times out.

Stop it via the **Stop watcher** button or the **Stop** action on the
notification. Both revert airplane mode if the app enabled it. `Force stop` does
**not** — it skips the revert and can leave the radios off.

## Starting Shizuku after every reboot

On a non-rooted device the Shizuku server dies on every reboot and must be
restarted over adb. Wireless debugging is unreliable for this: Android
[auto-disables it after a period of inactivity][aa-article], so the toggle
often flips back within seconds. A fix (auto-enable on trusted networks) is in
Android Canary, expected in 16 QPR3 or 17.

USB is the dependable route. With USB debugging enabled and a cable to a
computer:

```sh
adb shell "/data/app/~~<hash>/moe.shizuku.privileged.api-<hash>/lib/arm64/libshizuku.so"
```

Get the exact path from the Shizuku app. Note the app cannot start Shizuku
itself: enabling adb needs `WRITE_SECURE_SETTINGS`, which is the very privilege
Shizuku would grant. Until Shizuku is running the watcher stays inert — it will
not toggle anything.

[aa-article]: https://www.androidauthority.com/android-wireless-adb-auto-reconnect-3624945/

## Building

Needs JDK 17 and the Android SDK (API 36). No Android Studio required.

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleDebug
```

Installing from `/sdcard` fails — `system_server` cannot read `fuse`-labelled
files. Stage through `/data/local/tmp` instead:

```sh
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/soa.apk
adb shell pm install -r -g /data/local/tmp/soa.apk
```

`-g` pre-grants the Shizuku permission.

## Layout

| File | Role |
| --- | --- |
| `WatcherService.kt` | Foreground service; screen receivers, alarm, start/stop |
| `Airplane.kt` | `cmd connectivity airplane-mode` wrapper |
| `Hotspot.kt` | Tethered-client detection (the guard) |
| `Shell.kt` | Runs commands as shell uid via Shizuku |
| `ShizukuGate.kt` | Availability and permission checks |
| `Prefs.kt` | Delay, guard toggle, enabled state, "did we enable it" |
| `AlarmReceiver.kt` | Fires when the screen-off delay elapses |
| `BootReceiver.kt` | Restarts the watcher, only if it was running before |
| `MainActivity.kt` | Status display and buttons |

`ACTION_SCREEN_ON`/`OFF` cannot be declared in the manifest — they are only
delivered to receivers registered at runtime — hence the foreground service.
The delay uses `setExactAndAllowWhileIdle`, the only alarm variant that fires
through Doze, which is exactly the state being waited through.

Screen-on only reverts airplane mode if *this app* turned it on
(`Prefs.weEnabledIt`), so a manual toggle by the user is left alone.

## Testing status

Verified on-device:

- Airplane mode enable/disable, with radios confirmed down
  (`mRadioPowerState=0`, pings failing) and cleanly restored.
- Hotspot parsing, unit-tested against real captured `dumpsys tethering` and
  `ip neigh` output: interface extraction with both cellular and Wi-Fi upstream,
  hotspot-off, v4+v6 dedupe to one MAC, `FAILED` entries ignored.
- Builds, installs, launches without crashing; Shizuku grants the binder.

**Not yet verified end-to-end:** the screen-off -> 5 min -> toggle path, the
stop/revert path, and boot restart. A non-exported service cannot be started
from a shell, so these need the on-screen buttons and have not been exercised.
