#!/data/data/com.termux/files/usr/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP=${1:-}; SERIAL=${2:-}
case "$APP" in phone|wear) ;; *) echo 'Usage: ./install.sh phone|wear ADB_SERIAL' >&2; exit 2 ;; esac
[ -n "$SERIAL" ] || { echo 'ADB serial is required.' >&2; exit 2; }
APK="$ROOT/$APP/output/wearbridgeadb-$APP.apk"
[ -f "$APK" ] || { echo "Missing signed APK: $APK" >&2; exit 1; }
command -v adb >/dev/null 2>&1 || { echo 'Install Termux package android-tools (adb) first.' >&2; exit 1; }
adb -s "$SERIAL" install -r "$APK"
