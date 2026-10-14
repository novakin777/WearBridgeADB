#!/data/data/com.termux/files/usr/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
"$ROOT/build.sh" all
"$ROOT/sign.sh" all
