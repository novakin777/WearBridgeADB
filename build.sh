#!/data/data/com.termux/files/usr/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP=${1:-all}
TRACE=${2:-}
if [ -n "$TRACE" ] && [ "$TRACE" != "--trace" ]; then echo 'Usage: ./build.sh phone|wear|all [--trace]' >&2; exit 2; fi
case "$APP" in phone) TASK=:Application:assembleDebug ;; wear) TASK=:Wearable:assembleDebug ;; all) TASK=':Application:assembleDebug :Wearable:assembleDebug' ;; *) echo 'Usage: ./build.sh phone|wear|all [--trace]' >&2; exit 2 ;; esac
if [ "$APP" = wear ] || [ "$APP" = all ]; then
  "$ROOT/scripts/build-native-pty.sh"
fi
export ANDROID_HOME="$ROOT/toolchain/android-sdk" ANDROID_SDK_ROOT="$ROOT/toolchain/android-sdk"
cd "$ROOT/source/WearBridgeADB"
# The Termux aapt2 package cannot link framework resources newer than API 28.
TRACE_FLAG=-PwearbridgeTrace=false
if [ "$TRACE" = "--trace" ]; then TRACE_FLAG=-PwearbridgeTrace=true; fi
sh ./gradlew --no-daemon -Dorg.gradle.jvmargs='-Xmx1200m -Dfile.encoding=UTF-8' -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2" "$TRACE_FLAG" $TASK
case "$APP" in
 phone) cp Application/build/outputs/apk/debug/Application-debug.apk "$ROOT/phone/output/unsigned.apk" ;;
 wear) cp Wearable/build/outputs/apk/debug/Wearable-debug.apk "$ROOT/wear/output/unsigned.apk" ;;
 all) cp Application/build/outputs/apk/debug/Application-debug.apk "$ROOT/phone/output/unsigned.apk"; cp Wearable/build/outputs/apk/debug/Wearable-debug.apk "$ROOT/wear/output/unsigned.apk" ;;
esac
