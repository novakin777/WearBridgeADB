#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SRC="$ROOT/source/WearBridgeADB/Wearable/src/main/cpp/pty.c"
LIB="$ROOT/source/WearBridgeADB/Wearable/src/main/jniLibs"
mkdir -p "$LIB/arm64-v8a" "$LIB/armeabi-v7a"
JDK_HOME=${JAVA_HOME:-$(dirname -- "$(dirname -- "$(readlink -f -- "$(command -v javac)")")")}

if [ -n "${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}" ]; then
  NDK=${ANDROID_NDK_HOME:-$ANDROID_NDK_ROOT}
  case "$(uname -s):$(uname -m)" in
    Linux:x86_64) HOST_TAG=linux-x86_64 ;;
    Linux:aarch64) HOST_TAG=linux-aarch64 ;;
    Darwin:arm64) HOST_TAG=darwin-arm64 ;;
    Darwin:x86_64) HOST_TAG=darwin-x86_64 ;;
    *) echo "Unsupported NDK host: $(uname -s) $(uname -m)" >&2; exit 1 ;;
  esac
  CC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
  JNI_INC="-I$JDK_HOME/include -I$JDK_HOME/include/linux"
  "$CC/aarch64-linux-android28-clang" -shared -fPIC -O2 $JNI_INC -o "$LIB/arm64-v8a/libwearpty.so" "$SRC"
  "$CC/armv7a-linux-androideabi28-clang" -shared -fPIC -O2 $JNI_INC -o "$LIB/armeabi-v7a/libwearpty.so" "$SRC"
else
  command -v clang >/dev/null 2>&1 || { echo 'Install Termux clang or set ANDROID_NDK_HOME.' >&2; exit 1; }
  : "${PREFIX:?PREFIX must be set when compiling with Termux clang}"
  NATIVE_INC="-I$PREFIX/include -I$JDK_HOME/include -I$JDK_HOME/include/linux"
  clang --target=aarch64-linux-android28 -shared -fPIC -O2 $NATIVE_INC -o "$LIB/arm64-v8a/libwearpty.so" "$SRC"
  clang --target=armv7a-linux-androideabi28 -shared -fPIC -O2 $NATIVE_INC -o "$LIB/armeabi-v7a/libwearpty.so" "$SRC"
fi
