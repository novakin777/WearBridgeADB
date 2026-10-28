# WearBridgeADB

English · [Русский](README.md)

Local Android/Wear OS project for running commands on a paired watch through Shizuku and Wear OS Data Layer. **The app does not assess command safety:** it runs the commands you enter on the watch, so review the command, mode, and access profile before running it.

## Current implementation

- Current APK versions: Phone 1.45 and Wear 1.33. The phone header displays the connected watch name; the app has Terminal, Files, and Settings sections with a manual language switch. Fullscreen STREAM uses the project-owned `BridgeTerminalView` over the existing Wear Channel; terminal size changes are sent to the watch PTY through `TIOCSWINSZ`.
- NORMAL sends one command through MessageClient and Shizuku UserService, and displays its output. Commands are not classified: `logcat` in NORMAL does not switch modes.
- STREAM is enabled manually and opens one interactive `/system/bin/sh -i` session with a PTY through ChannelClient. The native PTY is built for arm64 and armeabi-v7a. Ctrl+C/Z/D, Ctrl+\\, Enter, Tab, and ANSI arrow sequences are sent through the PTY line discipline. Closing the channel ends the shell session.
- SHELL and ROOT are selected manually. SHELL uses the active Shizuku identity. ROOT uses the separately approved `/system/bin/su` path on the watch through KernelSU. ROOT is available only if the watch grants the app access. The selected profile is not inferred from the command.
- The optional Termux API listens only on phone loopback (`127.0.0.1:18765`) and authenticates requests with a private bearer token.
- While the STREAM PTY is open, the watch service uses a foreground notification and a partial `PARTIAL_WAKE_LOCK`, released when the session closes. Watch settings include a user-triggered battery-optimization exemption request and report its status. This lowers the chance of CPU suspension during a session, but Android/Wear OS may still restrict networking or terminate the process; Doze cannot be disabled absolutely.
- Select a single APK in the watch file browser and choose **⋮ → Install selected APK**, or tap **Install** in the bottom selection bar. It runs `pm install -r` through Shizuku SHELL. The local API also provides `./scripts/wadb install /sdcard/Download/app.apk`.

## Important: commands run directly

The user chooses NORMAL or STREAM and SHELL or ROOT. Commands run directly on the watch with the selected privileges and can change or delete files, stop services, or modify settings. In ROOT mode, commands run with root privileges. WearBridge does not sandbox commands or assess their effects, so review the command, mode, and profile before running it.

The phone-side Termux API also grants command access through its bearer token. Keep the token private and enable the API only while using it. The API binds to `127.0.0.1`, but another local process on the phone that has the token can use it.

## Build locally in Termux

The Gradle project is in `source/WearBridgeADB`. With JDK, Android SDK, and clang installed in Termux:

```sh
./build.sh all       # or phone / wear
./sign.sh all        # or phone / wear
./rebuild-all.sh     # build and sign both APKs
```

Outputs:

- `phone/output/wearbridgeadb-phone.apk`
- `wear/output/wearbridgeadb-wear.apk`

Local signing uses `apksigner.jar` and `keys/wearbridge-project.p12`. The password is in `keys/signing.env` (file mode 600). Never commit the keystore or password.

## GitHub Actions

`.github/workflows/android-build.yml` builds both debug APKs on pushes to `main`, pull requests, or manual dispatch. Artifacts are retained for 14 days. CI uses a temporary Android debug signing key. These APKs are for build verification and cannot update a locally signed install without uninstalling it first. Local signing credentials are not stored in the repository.

## Install and first run

1. Install Shizuku on the watch and start it through Wireless Debugging / Wireless ADB. WearBridge does not include Shizuku.
2. Install the phone APK on the phone and the watch APK on the watch using any available method.

3. On the watch, open WearBridgeADB, tap **Request Shizuku permission**, grant it in Shizuku, then tap **Test id**. With Shizuku started through ADB, expect `uid=2000(shell)`.
4. Open WearBridgeADB on the phone and select NORMAL or STREAM manually. SHELL/ROOT is also a manual choice; approve the root grant on the watch when prompted.

## Set up the Termux API, step by step

Follow these steps in order.

### 1. Download the client in Termux

Open Termux on your phone and paste this whole block:

```sh
pkg update
pkg install git curl
cd ~
git clone https://github.com/novakin777/WearBridgeADB.git
cd WearBridgeADB
```

If `~/WearBridgeADB` already exists, skip the `git clone` line and run `cd ~/WearBridgeADB` instead.

### 2. Copy the token in the phone app

Open WearBridgeADB on your phone → **Settings** → turn **TERMUX API** on → tap **Copy API token**. The token is now in your phone clipboard.

### 3. Save the token in Termux

Return to Termux. If you closed Termux, first return to the client folder:

```sh
cd ~/WearBridgeADB
./scripts/wadb configure
```

When Termux shows `Paste the API token copied from WearBridge Settings:`, long-press and tap **Paste**, then press Enter. **The pasted characters stay invisible; this is expected.** Termux will print the saved file path. `wadb` reads the token from `~/.config/wearbridgeadb/token` automatically. You do not need to open or edit that file.

Do not send the token in chat, put it in a command, or commit it to Git. The user must copy and paste the token. Phone root and the separate Termux:API app are not required.

### 4. Check the connection

```sh
./scripts/wadb status
./scripts/wadb --shell id
```

If `status` says the API is unavailable, return to WearBridge on the phone and make sure **TERMUX API** is on. Turn it off in the app when finished. Choose `--shell` or `--root` and `--normal` or `--stream` explicitly; the mode is never inferred from the command.

File `push/pull` uses Wear Data Layer by default. Add `--wifi` to explicitly use Wi-Fi for file bytes. Examples:

```sh
./scripts/wadb --shell --normal id
./scripts/wadb --root --stream logcat
./scripts/wadb --shell --wifi push ./app.apk /sdcard/Download/app.apk
./scripts/wadb --shell --datalayer pull /sdcard/Download/log.txt ./log.txt
```

Single requests use `/v1/exec`; STREAM opens ChannelClient only while a PTY session is active. Commands are limited to 65536 UTF-8 bytes. Run `./scripts/wadb install /sdcard/Download/app.apk` to install a watch-side APK through Shizuku SHELL regardless of the selected ROOT profile. Stop the API from phone Settings when finished. For NORMAL, wadb prints a requestId before execution; Ctrl+C sends a watch cancellation request. If the client was interrupted separately, run ./scripts/wadb cancel REQUEST_ID. For --async, use ./scripts/wadb job cancel JOB_ID. Job status includes `stage` and `lastActivityAt` alongside `updatedAt`, so clients can see the bridge phase and last phase transition; this does not guarantee that a command is producing streaming output. A cancel_sent/cancel_requested response confirms dispatch, not process termination: check the final response or job state. Cancellation is best effort and may not stop shell descendants.

**Settings → Diagnostics** provides opt-in local request tracing on the phone and watch. It records Data Layer phases, response waits, cancellation, async-job stages, and watch power state. It never records command text or sends telemetry off-device. Enable it on both devices before reproducing a delay, then copy both traces. Watch Shizuku UserService binding now runs on a dedicated single-flight worker; callers wait at most 15 seconds for the backend. If the Binder call itself stalls, the request times out while the single bind attempt remains in the background; further commands do not queue duplicate bind calls.

The service is not exported, binds only to IPv4 loopback, and requires the bearer token. It is not available over Wi-Fi or LAN.

## Files over Wi-Fi

Tap **FILES · DIRECT WI-FI** on the phone. The phone and watch must be on the same local Wi-Fi network with client-to-client communication enabled. Unlike the Wear Channel, direct file bytes use Wi-Fi. The phone file picker selects upload/download locations; the watch browser supports directories, multi-select, sorting, and batch operations. ROOT file access requires the selected watch-side root profile and confirmation.

Each transfer uses a temporary listener bound only to the watch Wi-Fi address and a random port. A one-time key is delivered over the paired Wear Data Layer; the TCP session uses HMAC authentication and AES-GCM encrypted file blocks. There is no open shell endpoint.

## Manual checks

- **NORMAL:** select NORMAL and run `id`, `getprop`, `dumpsys battery`, or `settings get system screen_off_timeout`. `logcat` stays a normal command and does not change modes.
- **STREAM:** enable STREAM, run `id`, `cd /data/local/tmp`, `pwd`, then `logcat`. Output streams over ChannelClient. STOP sends Ctrl+C and should leave the shell prompt active. **CLOSE TERMINAL / DISCONNECT** closes the channel and PTY.
- **Wi-Fi-off:** after Shizuku is running, disable Wireless Debugging and Wi-Fi on the watch while keeping Bluetooth pairing active; repeat `id`. Wear Data Layer can use Bluetooth. Direct Wi-Fi file transfer requires Wi-Fi.
- **Storage:** enable the requested Nearby Wi-Fi and shared storage permissions on the watch. The phone may ask for Nearby devices permission. The browser supports `/`, `/sdcard`, parent navigation, sorting, multi-select, downloads, uploads, and recursive folder deletion. Delete confirmation is enabled by default and its setting persists. The filesystem root `/` is protected from deletion.

Fullscreen STREAM uses project-owned terminal code; it preserves the active PTY when entering or leaving fullscreen. The terminal font (12–22 sp, default 15 sp) is configurable in Settings. Clear affects only the local display. Phone v33+ and Wear v17+ support PTY resize messages.

## Sources, licenses, and AI assistance

- The initial Wear Data Layer structure and files retaining upstream copyright headers derive from the official Android Wear OS Data Layer sample by the Android Open Source Project, Apache License 2.0: <https://github.com/android/wear-os-samples>.
- Shizuku API (`dev.rikka.shizuku:api` and `provider`) is from RikkaApps, MIT License: <https://github.com/RikkaApps/Shizuku-API>.
- Kotlin Coroutines is Apache License 2.0: <https://github.com/Kotlin/kotlinx.coroutines>. Google Play services Wearable is an external dependency governed by the Google Play services terms.
- Original WearBridge code is licensed under GNU GPL version 3 (`LICENSE`). Third-party files and dependencies retain their own licenses as listed in `THIRD_PARTY_NOTICES.md`.
- `BridgeTerminalView` is WearBridge's own implementation; no third-party terminal emulator source is included in it. Upstream attribution/license headers remain in files derived from the Android sample.
- AI tools assisted with development. The project author made the design and integration decisions and reviewed the resulting project.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the component and license summary.
