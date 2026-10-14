# WearBridgeADB

English · [Русский](README.md)

Local Android/Wear OS project for running commands on a paired watch through Shizuku and Wear OS Data Layer. **The app does not assess command safety:** it runs the commands you enter on the watch, so review the command, mode, and access profile before running it.

## Current implementation

- Phone 1.32 and Wear 1.17. The phone app has Terminal, Files, and Settings sections with a manual language switch. Fullscreen STREAM uses the project-owned `BridgeTerminalView` over the existing Wear Channel; terminal size changes are sent to the watch PTY through `TIOCSWINSZ`.
- NORMAL sends one command through MessageClient and Shizuku UserService, and displays its output. Commands are not classified: `logcat` in NORMAL does not switch modes.
- STREAM is enabled manually and opens one interactive `/system/bin/sh -i` session with a PTY through ChannelClient. The native PTY is built for arm64 and armeabi-v7a. Ctrl+C/Z/D, Ctrl+\\, Enter, Tab, and ANSI arrow sequences are sent through the PTY line discipline. Closing the channel ends the shell session.
- SHELL and ROOT are selected manually. SHELL uses the active Shizuku identity. ROOT uses the separately approved `/system/bin/su` path on the watch through KernelSU. ROOT is available only if the watch grants the app access. The selected profile is not inferred from the command.
- The optional Termux API listens only on phone loopback (`127.0.0.1:18765`) and authenticates requests with a private bearer token.

## Important: commands run directly

The user chooses NORMAL or STREAM and SHELL or ROOT. WearBridge provides almost no protection against operator mistakes: a command can change or delete files, stop services, modify settings, or damage the watch system. In ROOT mode, commands run with root privileges on the watch. There is no command sandbox or dangerous-command analysis. Check the command, mode, and profile before running it; do not paste commands you have not reviewed.

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
2. Install `wear/output/wearbridgeadb-wear.apk` on the watch and `phone/output/wearbridgeadb-phone.apk` on the phone. For example:

   ```sh
   ./install.sh wear WATCH_ADB_SERIAL
   ./install.sh phone PHONE_ADB_SERIAL
   ```

3. On the watch, open WearBridgeADB, tap **Request Shizuku permission**, grant it in Shizuku, then tap **Test id**. With Shizuku started through ADB, expect `uid=2000(shell)`.
4. Open WearBridgeADB on the phone and select NORMAL or STREAM manually. SHELL/ROOT is also a manual choice; approve the root grant on the watch when prompted.

## Termux API without phone root

1. In phone Settings, enable **TERMUX API**. Android shows a persistent notification while the server is running.
2. Tap **Copy API token** in Settings.
3. In Termux, install curl (`pkg install curl`), then configure the included client:

   ```sh
   ./scripts/wadb configure
   ```

   Paste the token when prompted. The client saves it to `~/.config/wearbridgeadb/token` with owner-only permissions. Root on the phone and the Termux:API plugin are not required. Alternatively, provide the secret using `WADB_TOKEN`.
4. Use the client:

   ```sh
   ./scripts/wadb status
   ./scripts/wadb --shell id
   ./scripts/wadb --root 'getprop ro.build.fingerprint'
   ```

`--root` selects watch-side ROOT execution; it does not require root in Termux. The API supports `GET /v1/status` and `GET /v1/exec?mode=shell|root&command=...`, with commands limited to 4096 characters. Stop the API from phone Settings when finished.

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
