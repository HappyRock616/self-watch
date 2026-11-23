#!/usr/bin/env bash
# Builds the signed release APK. Asks for the keystore and key passwords (never stored or echoed).
#   tools/sign-release.sh /path/to/selfwatch-release.jks [alias]
set -euo pipefail
store="${1:?path to the release keystore}"
alias_name="${2:-selfwatch}"
read -rsp "Keystore password: " SELFWATCH_KEYSTORE_PASSWORD; echo
read -rsp "Key password (Enter if the same): " SELFWATCH_KEY_PASSWORD; echo
[ -z "$SELFWATCH_KEY_PASSWORD" ] && SELFWATCH_KEY_PASSWORD="$SELFWATCH_KEYSTORE_PASSWORD"
export SELFWATCH_KEYSTORE="$store" SELFWATCH_KEY_ALIAS="$alias_name" SELFWATCH_KEYSTORE_PASSWORD SELFWATCH_KEY_PASSWORD
cd "$(dirname "$0")/.."
./gradlew -q :app:assembleRelease
unset SELFWATCH_KEYSTORE_PASSWORD SELFWATCH_KEY_PASSWORD
ls -l app/build/outputs/apk/release/
echo "Done. You can close this tab."
