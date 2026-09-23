#!/usr/bin/env bash
set -euo pipefail
umask 077
KEYSTORE_PATH="${BKX_ANDROID_KEYSTORE:-$HOME/.config/devtool/bkx/bkx-personal.p12}"
KEY_ALIAS="${BKX_ANDROID_KEY_ALIAS:-bkx}"
fail() { printf 'BKX fingerprint failed: %s\n' "$*" >&2; exit 1; }
command -v keytool >/dev/null 2>&1 || fail "keytool was not found"
[[ -f "$KEYSTORE_PATH" ]] || fail "keystore not found: $KEYSTORE_PATH"
IFS= read -r -s -p 'Keystore password: ' password
printf '\n'
export BKX_SIGNING_FINGERPRINT_PASSWORD="$password"
keytool -list -v \
  -keystore "$KEYSTORE_PATH" \
  -alias "$KEY_ALIAS" \
  -storepass:env BKX_SIGNING_FINGERPRINT_PASSWORD |
  grep -E 'SHA1:|SHA256:'
unset BKX_SIGNING_FINGERPRINT_PASSWORD password
