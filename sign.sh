#!/data/data/com.termux/files/usr/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP=${1:-all}
case "$APP" in phone|wear) APPS="$APP" ;; all) APPS='phone wear' ;; *) echo 'Usage: ./sign.sh phone|wear|all' >&2; exit 2 ;; esac
. "$ROOT/keys/signing.env"
export WBADB_SIGNING_PASS
for app in $APPS; do
  IN="$ROOT/$app/output/unsigned.apk"; OUT="$ROOT/$app/output/wearbridgeadb-$app.apk"
  [ -f "$IN" ] || { echo "Missing APK: $IN (run ./build.sh $app)" >&2; exit 1; }
  java -jar "$ROOT/apksigner.jar" sign --ks "$ROOT/keys/wearbridge-project.p12" --ks-key-alias wearbridge-project --ks-pass env:WBADB_SIGNING_PASS --key-pass env:WBADB_SIGNING_PASS --out "$OUT" "$IN"
  java -jar "$ROOT/apksigner.jar" verify --verbose --print-certs "$OUT"
  echo "Signed: $OUT"
done
