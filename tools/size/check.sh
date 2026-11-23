#!/bin/sh
# Size and native-code gate for the release APK (WP-01, WP-14).
#
#   tools/size/check.sh app/build/outputs/apk/release/app-release-unsigned.apk
#
# Fails when the APK is larger than the byte count in tools/size/budget.txt,
# or when it contains any lib/ entry (native code is not allowed).
# POSIX sh; runs on Linux CI and in Git Bash. Needs `unzip` or the JDK's `jar`.
set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 <apk>" >&2
    exit 2
fi
apk=$1
if [ ! -f "$apk" ]; then
    echo "size check: no such file: $apk" >&2
    exit 2
fi

here=$(cd "$(dirname "$0")" && pwd)
budget=$(grep -v '^[[:space:]]*#' "$here/budget.txt" | tr -d '[:space:]')
case $budget in
    '' | *[!0-9]*) echo "size check: budget.txt must hold one whole number of bytes" >&2; exit 2 ;;
esac

size=$(wc -c < "$apk" | tr -d '[:space:]')

if command -v unzip >/dev/null 2>&1; then
    entries=$(unzip -Z1 "$apk")
elif command -v jar >/dev/null 2>&1; then
    entries=$(jar tf "$apk")
else
    echo "size check: need unzip or jar to list the APK" >&2
    exit 2
fi

status=0
native=$(printf '%s\n' "$entries" | grep '^lib/' || true)
if [ -n "$native" ]; then
    echo "FAIL: native code found in $apk (no native libraries allowed):" >&2
    printf '%s\n' "$native" | sed 's/^/  /' >&2
    status=1
fi

kb() { echo $(( ($1 + 1023) / 1024 )); }
if [ "$size" -gt "$budget" ]; then
    echo "FAIL: $apk is $size bytes ($(kb "$size") KB), budget is $budget bytes ($(kb "$budget") KB)." >&2
    echo "      Shrink it, or raise tools/size/budget.txt in a commit that says why." >&2
    status=1
else
    echo "OK: $apk is $size bytes ($(kb "$size") KB) of $budget budget, $(( budget - size )) bytes to spare."
fi
exit $status
