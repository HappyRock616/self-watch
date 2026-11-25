#!/usr/bin/env bash
# Pulls what the overnight watch test needs from the Pixel 5 (debug build): Self Watch logs and
# database, plus the phone's battery stats. Take the pixel5 lock first (watch-admin/devlock.sh).
#   tools/overnight/pull.sh <out-dir>
set -eu
OUT=${1:?out dir}
ADB=${ADB:-"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"}
S=${SERIAL:-0A021FDD4005TQ}
PKG=app.selfwatch.debug
mkdir -p "$OUT"
for f in logs/watch.log logs/app.log; do
  "$ADB" -s "$S" exec-out run-as $PKG cat "files/$f" > "$OUT/$(basename "$f")" 2>/dev/null || true
done
# Copy the database with its WAL so nothing written overnight is missed.
for f in selfwatch.db selfwatch.db-wal selfwatch.db-shm; do
  "$ADB" -s "$S" exec-out run-as $PKG cat "databases/$f" > "$OUT/$f" 2>/dev/null || rm -f "$OUT/$f"
done
"$ADB" -s "$S" shell dumpsys batterystats --charged $PKG > "$OUT/batterystats.txt"
"$ADB" -s "$S" shell dumpsys battery > "$OUT/phone_battery.txt"
ls -la "$OUT"
