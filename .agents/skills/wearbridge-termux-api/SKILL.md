---
name: wearbridge-termux-api
description: Use the WearBridge phone's authenticated loopback API from Termux to inspect the paired Wear OS watch or run a user-requested SHELL/ROOT command. This is WearBridge's bridge, not the Termux:API plugin.
---

# WearBridge Termux bridge

Use this skill when a user asks to inspect or control the paired watch from Termux through the WearBridge phone app.

## Setup once

If the user does not already have the WearBridge checkout in Termux, give these exact commands:

```sh
pkg update
pkg install git curl
cd ~
git clone https://github.com/novakin777/WearBridgeADB.git
cd ~/WearBridgeADB
```

If `~/WearBridgeADB` already exists, skip `git clone` and just run `cd ~/WearBridgeADB`. Do not direct the user to `install.sh` for API setup; it is unrelated ADB APK installation.

1. On the phone, open WearBridge Settings, turn **TERMUX API** on, and tap **Copy API token**. The user copies the bearer token themselves. Never read app-private files, inspect the clipboard, or retrieve/copy the token for them.
2. In Termux, from the repository root, run:

   ```sh
   ./scripts/wadb configure
   ```

   The user long-presses the prompt and pastes the token, then presses Enter. Explain that the pasted characters are hidden; this is expected. The command saves the token to `${XDG_CONFIG_HOME:-$HOME/.config}/wearbridgeadb/token` with mode `600`, where `scripts/wadb` reads it automatically. Do not put the token in chat, command arguments, shell history, logs, or repository files.
3. Confirm setup:

   ```sh
   ./scripts/wadb status
   ./scripts/wadb --shell id
   ```

`wadb` runs as the ordinary phone user and does not need phone root; most phones are not rooted. The separate Termux:API Android plugin is not required. `WADB_TOKEN` can supply the token from an already-secured environment instead.

Do not read or print the token, include it in command output, or put it in logs. If the API is stopped or unavailable, ask the user to enable it in WearBridge Settings. The service authenticates with a bearer token and binds only to `127.0.0.1:18765` on the phone; do not forward it to LAN/Wi-Fi or `0.0.0.0`.

## Run a requested command

Always select the watch execution profile explicitly:

```sh
./scripts/wadb --shell 'id'
./scripts/wadb --shell 'getprop ro.build.fingerprint'
./scripts/wadb --root 'id'
./scripts/wadb --root 'dumpsys battery'
```

Choose execution and privilege independently. `--normal` runs exactly one command (default); `--stream` explicitly opens a persistent interactive PTY from Termux, optionally with an initial command. Never infer the mode from a command name: `logcat` remains NORMAL unless `--stream` was selected. In STREAM, terminal bytes and controls go to the open PTY; Ctrl+C/D/Z and arrow keys are forwarded, while Ctrl+] closes the CLI and remote PTY. The CLI requires an interactive TTY for STREAM. Use `--shell` by default. Use `--root` only when the user explicitly requests root-level execution or confirms it for the current task. ROOT means root on the watch; it does not mean root in Termux or on the phone.

Treat command text as executable input, quote it as one shell argument, and report the actual response. There is almost no protection against operator mistakes: commands execute directly on the watch, and ROOT can change or delete system data. Do not run destructive commands, modify boot/system partitions, or chain unrelated commands unless the user explicitly asked for that exact operation.

`./scripts/wadb status` checks phone API readiness and paired watch connectivity. A successful phone HTTP response does not prove a watch command succeeded; inspect the command result. For ADB-started Shizuku, `--shell id` should report `uid=2000(shell)`. Watch-side ROOT is confirmed only by the command result reporting `uid=0(root)`.

When the user explicitly requests installation of an APK already on the watch, run:

```sh
./scripts/wadb install /sdcard/Download/example.apk
```

This convenience command always uses Shizuku SHELL and executes `pm install -r`, regardless of a preceding `--root`. If the APK is phone-local, use the CLI `push` command below first. STREAM is the interactive PTY, not a file-transfer channel.

## Practical Termux helpers

`wadb doctor` makes one saved, read-only diagnostic report. It checks API/watch connectivity, Shizuku SHELL identity, the installed WearBridge package version and selected permission lines, plus the device-idle whitelist. The report is saved under `${XDG_STATE_HOME:-$HOME/.local/state}/wearbridgeadb/diagnostics/`. ROOT is skipped unless the user explicitly selects it with `wadb --root doctor`; the normal report does not change permissions or Doze state.

Capture a bounded logcat scenario with an explicit filter:

```sh
./scripts/wadb capture-logcat --duration 30 --tag WearBridge
./scripts/wadb capture-logcat --duration 60 --pid 1234 --output "$HOME/storage/downloads/watch-run.log"
```

Exactly one of `--tag` or `--pid` is required. Duration is limited to 1–300 seconds. Capture runs as an async SHELL job using the watch's `/system/bin/timeout`; it sends SIGINT at the requested duration and escalates to SIGKILL after a 2-second grace period if logcat does not exit. The phone-side wait allows up to 120 seconds for delayed Shizuku startup plus the capture duration and result-delivery margin; if that window expires, it requests job cancellation and reports the job ID. On completion, it saves the returned log on the phone (Termux `storage/downloads` when available, otherwise the WearBridge state directory). The API caps each job's combined output at 2 MiB; check the printed truncation warning. A filtered log can still contain private data, so keep the file local and remove it when no longer needed.

Install an APK from the phone in one SHELL-only operation:

```sh
./scripts/wadb install-local --package dev.example.app ./app-release.apk
```

The package id is required because this Termux installation has no bundled APK metadata reader. The helper uploads to a unique temporary path under `/data/local/tmp` over Data Layer by default (or accepts `--wifi`), checks local and watch SHA-256 values, runs `pm install -r`, confirms `versionName` and `pm path`, then removes the temporary watch copy. `/sdcard/Download` can be unreadable to `system_server` on this watch's FUSE-backed storage. If an intermediate step is indeterminate or verification fails, the helper leaves the uploaded APK on watch and prints its exact path for inspection; do not blindly retry an install after an API timeout.

## Long-running commands

For a synchronous NORMAL request, `wadb` prints a `requestId` before sending it. Ctrl+C attempts remote cancellation; if the client is killed or interrupted separately, run `./scripts/wadb cancel REQUEST_ID` from another Termux session. The cancel endpoint confirms dispatch only; check the request's final result when available. SHELL and ROOT command cancellation uses a dedicated `setsid` process group when supported. ROOT NORMAL commands require `/system/bin/setsid`, have a 120-second execution ceiling, and send TERM then KILL to the whole group on cancellation or timeout; without `setsid`, ROOT execution is rejected before the command starts. For async jobs, use `./scripts/wadb job cancel JOB_ID`; queued jobs become cancelled without being sent to the watch, while running jobs receive a watch-side cancellation message. Treat `cancel_requested` as pending, not proof of termination.

Use an asynchronous job when a command may exceed the synchronous 120-second HTTP wait:

```sh
./scripts/wadb --shell --async 'am instrument -w PACKAGE/TEST_RUNNER'
./scripts/wadb job status JOB_ID
./scripts/wadb job output JOB_ID
./scripts/wadb job cancel JOB_ID
```

The async call returns a `jobId`; poll status and fetch output separately. Status includes `stage` and `lastActivityAt` alongside `updatedAt`: `updatedAt` tracks job-state changes, while the additional fields expose bridge phase transitions. This is not a live-output heartbeat. Output is collected when the command finishes rather than exposed as a live tail. `job output` prints the saved JSON and writes a copy under `${XDG_STATE_HOME:-$HOME/.local/state}/wearbridgeadb/jobs/`. The phone app also persists job records in its private storage. Wear 1.32 allows up to three concurrent SHELL NORMAL commands and caps watch stdout and stderr at 1 MiB each; excess work is rejected instead of queued, and the response marks truncated output. ROOT work uses the same three-command cap. File-copy error capture is capped at 64 KiB. SHELL and ROOT NORMAL waits and reader joins are bounded; ROOT timeout returns exit code 124. A cancellation message acknowledgement is not proof of termination: check for a terminal job state and use a fresh watch trace if it remains pending.

Wear 1.29 holds a scoped `PARTIAL_WAKE_LOCK` from receipt of a NORMAL execution request through Shizuku acquisition, process completion, and result delivery. The lock is released in `finally` and on service destruction; STREAM/PTTY retains its separate lock. This is required for timed commands when the watch enters Doze with its display off. `batteryExempt=true` reports the package allowlist state, not whether a wake lock is currently held. A foreground service alone does not establish that CPU stayed awake. A running command holds the NORMAL lock until it returns or is cancelled, so an intentionally unbounded command (for example `logcat` in NORMAL) can keep the CPU awake and use battery until stopped.

### Cancellation-state and memory-retention invariant

When changing bridge cancellation or memory handling, inspect both the phone and Wear processes: matching request IDs on the wire do not mean their in-memory maps share cleanup. A concrete Wear retention defect was found in `ShellUserService`: `cancel()` added a request ID to a cancellation set before checking for a running process. A late/unknown cancel had no process and no later `execJob` completion, so its unique ID stayed in the UserService set indefinitely. The separate Wear listener's 120-second marker expiry did not bound this second map.

Treat pre-start cancellation markers as short-lived tombstones, not permanent history. Remove them when consumed or when the request reaches a terminal path; expire them using monotonic elapsed time and impose a hard size cap. Do not rely on a paired callback eventually arriving, and do not schedule one delayed cleanup task per marker unless that scheduler queue is itself bounded. Keep active-process tracking separate so pruning a stale pre-start marker cannot hide or orphan a live process. Audit normal completion, errors, cancellation, timeout, channel disconnect, and service destruction for cleanup of processes, streams, file descriptors, Wear channels, wake locks, listeners, coroutines, and retained output. Bound both item counts and output bytes. Compilation alone does not prove a memory issue is fixed; label source-level retention findings separately from a measured heap leak, and use repeated late-cancel/normal-cancel scenarios plus device heap or process-memory evidence when runtime verification is requested.

Wear resource-retention rules: close Wear ChannelClient input/output streams before joining pump coroutines, because blocking stream reads do not observe coroutine cancellation by themselves. PTY sessions have an opaque session ID; always call `closePty(sessionId)` on disconnect/close so native code closes its guard FD, signals the shell process group, and `waitpid`s the child. Limit concurrent watch shell/root work and reject excess work promptly instead of queuing requests that retain payloads, wake locks, and output buffers. These are source-level retention controls; only a device heap/process measurement can establish a measured leak or prove a runtime fix.

### Shizuku UserService process lifecycle

`Shizuku.unbindUserService(args, connection, true)` removes the server record but does not itself guarantee the remote process exits. Every UserService AIDL must implement Shizuku's reserved `void destroy() = 16777114` transaction, release its owned processes, PTYs, streams and descriptors, then exit the process. If any AIDL method uses an explicit transaction ID, assign stable IDs to every method and preserve existing IDs. Use separate stable `tag()` values for clients with independent lifecycles so an Activity unbind cannot tear down a background execution backend. On memory investigations, compare exact UserService PIDs and PSS before/after a small bounded sequence of commands; a successful command and successful APK build do not prove the old processes exited. A service that predates this destroy implementation may remain orphaned until Shizuku is restarted or the watch reboots.

To verify natural timer completion, use a short command that emits a marker after the timer, then check both the terminal job state and output; for example:

```sh
./scripts/wadb --shell --async 'echo TIMER_START; sleep 8; echo TIMER_DONE'
./scripts/wadb job status JOB_ID
./scripts/wadb job output JOB_ID
```

Expected result is `state=completed`, `exitCode=0`, and both markers. Do not use a successful cancel as proof that a timer fired. In tracing, compare watch `message_received`/`deviceIdle` with `exec_wakelock_acquired`, `backend_ready`, `exec_start`, `exec_finished`, and `exec_wakelock_released`. Shizuku bind delay before `exec_start` is distinct from a process that remains alive after `exec_start`. On Wear 1.29 one runtime `sleep 8` test completed naturally with both markers and exit code 0; that run did not confirm whether Doze was active, so it is not a controlled Doze-specific validation.

For intermittent delays, request tracing is excluded from normal builds by default. Build with `./build.sh all --trace` (or `phone --trace` / `wear --trace`) to include opt-in **Settings → Diagnostics → Request tracing** controls; builds without `--trace` do not record traces and hide trace controls. Trace files stay in each app's private storage and can be copied through the UI. They include request IDs, Data Layer stages/timings, async job/cancellation transitions, and watch idle/interactive/battery-exemption state. Command text is not recorded, and no telemetry is uploaded. A watch trace entry for `message_received` proves the Data Layer request reached the watch app; compare it with the phone `data_layer_send_ack`, watch backend/exec stages, and phone result-received stages to locate the stalled interval.

Shizuku UserService acquisition has a bounded 15-second caller wait. If a trace stops at `shizuku_bind_call_start`, the Shizuku Binder call itself is still pending; do not repeatedly retry commands. A single background bind attempt is shared and later requests should fail promptly until it completes or the callback arrives. `backend_callback_connected` followed by `backend_ready` means the shell backend became available; it does not prove the command had not already been cancelled.

## File transfer from the CLI

Use the Wear Data Layer asset path for files; it does not open a LAN socket or require watch Wi-Fi:

```sh
./scripts/wadb --shell push ./app.apk /sdcard/Download/app.apk
./scripts/wadb --root pull /sdcard/Download/log.txt ./log.txt
```

`push` takes a phone-local source and absolute watch destination; it replaces a file at that destination. `pull` takes an absolute watch source and a phone destination; it refuses to overwrite an existing phone file and requires its parent directory to exist. The 1 GiB limit is only the implementation's hard request cap; it is not a measured or recommended Bluetooth transfer size. A Wear Data Layer `Asset` supports data larger than the roughly 100 KB `DataItem` payload limit, but that does not establish a safe throughput or file-size limit for a particular watch. Do not run file transfers concurrently with other Data Layer work while diagnosing congestion. Measure one transfer at a time, recording byte count and elapsed time, and increase the test size only after the previous transfer has completed and the watch UI remains responsive. Stop on a prolonged `Loading` screen, loss of connection, or timeout. A client-side interrupt does not prove that the watch-side transfer was cancelled; the phone implementation can keep waiting for a transfer response for up to 10 minutes, so do not immediately retry or start another transfer. `--shell` uses the Shizuku UserService shell identity; select `--root` only when explicitly requested. Wear OS chooses the available Data Layer transport; Bluetooth should be sufficient with Wi-Fi disabled while the paired connection remains available. The existing in-app Files transfer flow may still use its separate Wi-Fi transport.

## Timeouts and diagnostics

- Synchronous `/v1/exec` waits up to 120 seconds. A timeout is indeterminate: it does not show whether the watch command started or finished. Do not automatically retry, especially `am instrument`, APK installs, or commands that create temporary files. Before retrying, make short read-only requests to check the specific process, known temporary/result files, and relevant `logcat` tags. If the result remains ambiguous, report that and ask before retrying. Prefer async jobs for long operations, but still inspect status/output rather than resubmitting the command.
- For PackageManager checks, target one package (for example `./scripts/wadb --shell 'dumpsys package com.example.target'`) and filter only needed fields. Avoid broad `dumpsys package` or whole-system dumps through synchronous `/v1/exec`; they can exceed the timeout.
- After a WearBridge update or watch reboot, recheck live state: `./scripts/wadb status`, `./scripts/wadb --root 'id'` if ROOT matters, the exact permission grant, and `./scripts/wadb --shell 'cmd deviceidle whitelist'`. Do not treat older output as current proof.
- Prefer the watch UI's battery-optimization status and user confirmation prompt. Change the device-idle whitelist only on an explicit user request. Check whether the package was already listed first; add it only if absent and remove it only if this task added it. Example:

  ```sh
  pkg=dev.wearbrdgeadb
  ./scripts/wadb --shell 'cmd deviceidle whitelist'
  ./scripts/wadb --shell "cmd deviceidle whitelist +$pkg"
  ./scripts/wadb --shell 'cmd deviceidle whitelist'
  # Roll back only if it was absent before this change:
  ./scripts/wadb --shell "cmd deviceidle whitelist -$pkg"
  ./scripts/wadb --shell 'cmd deviceidle whitelist'
  ```

  The app's `PowerManager.isIgnoringBatteryOptimizations()` status and the `cmd deviceidle` whitelist are related but distinct; state which check was actually performed.

## API details

Prefer `scripts/wadb`; it handles bearer auth and URL encoding, keeps the token in a mode-600 temporary curl config instead of process arguments, and removes that file when the command exits. The API exposes `/v1/status`, `/v1/exec`, `/v1/exec/cancel`, `/v1/jobs/status`, `/v1/jobs/output`, `/v1/jobs/cancel`, `/v1/files/push`, `/v1/files/pull`, and `/v1/stream/{open,input,output,resize,close}`. The command limit is 65536 UTF-8 bytes (the HTTP parser allows 256 KiB for URL-encoded headers). Commands use Wear OS Data Layer; async results are sent in bounded chunks to avoid a single-message size limit. File bytes use Data Layer `Asset`s by default or authenticated Wi-Fi when `--wifi` is selected; this option affects files only. Commands and STREAM always use Wear Data Layer.
