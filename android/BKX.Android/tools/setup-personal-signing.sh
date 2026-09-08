#!/usr/bin/env bash
set -euo pipefail
umask 077

CONFIG_PATH="${BKX_ANDROID_SIGNING_PROPERTIES:-$HOME/.config/bkx/android-signing.properties}"
KEYSTORE_PATH="${BKX_ANDROID_KEYSTORE:-$HOME/.config/bkx/bkx-personal.p12}"
KEY_ALIAS="${BKX_ANDROID_KEY_ALIAS:-bkx}"

fail() { printf 'BKX signing setup failed: %s\n' "$*" >&2; exit 1; }
command -v keytool >/dev/null 2>&1 || fail "keytool was not found; install/use OpenJDK 21 first"

case "$CONFIG_PATH" in /*) ;; *) fail "BKX_ANDROID_SIGNING_PROPERTIES must be an absolute path" ;; esac
case "$KEYSTORE_PATH" in /*) ;; *) fail "BKX_ANDROID_KEYSTORE must be an absolute path" ;; esac

mkdir -p "$(dirname "$CONFIG_PATH")" "$(dirname "$KEYSTORE_PATH")"

if [[ -e "$CONFIG_PATH" || -e "$KEYSTORE_PATH" ]]; then
  printf 'Existing signing material was not modified.\n'
  printf 'Properties: %s\n' "$CONFIG_PATH"
  printf 'Keystore:   %s\n' "$KEYSTORE_PATH"
  printf 'Delete or move them yourself only if you intentionally want a new app identity.\n'
  exit 2
fi

printf 'Create the permanent BKX Android signing key.\n'
printf 'This key must be kept for every future BKX APK update.\n'
printf 'The password is never written to the repository.\n\n'

while :; do
  IFS= read -r -s -p 'Keystore password (at least 12 characters): ' password
  printf '\n'
  [[ ${#password} -ge 12 ]] || { printf 'Use at least 12 characters.\n' >&2; continue; }
  IFS= read -r -s -p 'Confirm password: ' confirm
  printf '\n'
  [[ "$password" == "$confirm" ]] || { printf 'Passwords do not match.\n' >&2; continue; }
  break
done
unset confirm

export BKX_SIGNING_SETUP_PASSWORD="$password"
keytool -genkeypair \
  -keystore "$KEYSTORE_PATH" \
  -storetype PKCS12 \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname 'CN=BKX Personal, OU=Personal, O=BKX, C=BR' \
  -storepass:env BKX_SIGNING_SETUP_PASSWORD \
  -keypass:env BKX_SIGNING_SETUP_PASSWORD >/dev/null

cat > "$CONFIG_PATH" <<EOF
storeFile=$KEYSTORE_PATH
storePassword=$password
keyAlias=$KEY_ALIAS
keyPassword=$password
EOF
chmod 600 "$CONFIG_PATH" "$KEYSTORE_PATH"

printf '\nPermanent BKX signing material created.\n'
printf 'Properties: %s\n' "$CONFIG_PATH"
printf 'Keystore:   %s\n' "$KEYSTORE_PATH"
printf 'Alias:      %s\n\n' "$KEY_ALIAS"
printf 'Certificate fingerprints to register with Google Cloud:\n'
keytool -list -v \
  -keystore "$KEYSTORE_PATH" \
  -alias "$KEY_ALIAS" \
  -storepass:env BKX_SIGNING_SETUP_PASSWORD |
  grep -E 'SHA1:|SHA256:' || true

unset BKX_SIGNING_SETUP_PASSWORD password
printf '\nBack up BOTH files somewhere private and durable. Losing this keystore prevents in-place updates of the installed BKX app.\n'
printf 'Next: run Gradle :app:verifyBkxPersonalSigningConfigured and build a personal APK.\n'
