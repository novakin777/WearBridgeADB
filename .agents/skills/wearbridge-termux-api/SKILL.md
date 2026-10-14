---
name: wearbridge-termux-api
description: Use the WearBridge phone's authenticated loopback API from Termux to inspect the paired Wear OS watch or run a user-requested SHELL/ROOT command. This is WearBridge's bridge, not the Termux:API plugin.
---

# WearBridge Termux bridge

Use this skill when a user asks to inspect or control the paired watch from Termux through the WearBridge phone app.

## Setup once

1. Find the WearBridge checkout and use its `scripts/wadb` client.
2. On the phone, open WearBridge Settings, enable **TERMUX API**, and tap **Copy API token**.
3. In Termux, run `./scripts/wadb configure` and paste the token into its hidden prompt. It saves the token in `${XDG_CONFIG_HOME:-$HOME/.config}/wearbridgeadb/token` with mode `600`. Phone root and the separate Termux:API plugin are not required. `WADB_TOKEN` can supply the token from an already-secured environment instead.
4. Check availability with `./scripts/wadb status`.

Do not read or print the token, include it in command output, or put it in logs. If the API is stopped or unavailable, ask the user to enable it in WearBridge Settings. The service authenticates with a bearer token and binds only to `127.0.0.1:18765` on the phone; do not forward it to LAN/Wi-Fi or `0.0.0.0`.

## Run a requested command

Always select the watch execution profile explicitly:

```sh
./scripts/wadb --shell 'id'
./scripts/wadb --shell 'getprop ro.build.fingerprint'
./scripts/wadb --root 'id'
./scripts/wadb --root 'dumpsys battery'
```

Use `--shell` by default. Use `--root` only when the user explicitly requests root-level execution or confirms it for the current task. ROOT means root on the watch; it does not mean root in Termux or on the phone. Do not infer NORMAL versus STREAM from command names. `wadb` runs one request in the selected mode; interactive STREAM/PTy must be selected and used in the WearBridge UI.

Treat command text as executable input, quote it as one shell argument, and report the actual response. There is almost no protection against operator mistakes: commands execute directly on the watch, and ROOT can change or delete system data. Do not run destructive commands, modify boot/system partitions, or chain unrelated commands unless the user explicitly asked for that exact operation.

`./scripts/wadb status` checks phone API readiness and paired watch connectivity. A successful phone HTTP response does not prove a watch command succeeded; inspect the command result. For ADB-started Shizuku, `--shell id` should report `uid=2000(shell)`. Watch-side ROOT is confirmed only by the command result reporting `uid=0(root)`.

## API details

Prefer `scripts/wadb`; it handles bearer auth and URL encoding, keeps the token in a mode-600 temporary curl config instead of process arguments, and removes that file when the command exits. The endpoints are `GET /v1/status` and `GET /v1/exec?mode=shell|root&command=...`; commands are limited to 4096 characters. Phone↔watch requests use the paired Wear OS Data Layer and can use Bluetooth with Wi-Fi disabled. Direct file transfer is a separate optional Wi-Fi feature.
