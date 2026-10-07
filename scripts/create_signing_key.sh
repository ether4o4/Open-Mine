#!/usr/bin/env bash
# Private signing material is never committed or uploaded unencrypted.
set -euo pipefail
umask 077
signing_dir=$(mktemp -d "${RUNNER_TEMP:?}/open-mine-signing.XXXXXX")
if [ -n "${OPEN_MINE_SIGNING_P12_BASE64:-}" ] || [ -n "${OPEN_MINE_RETAINED_SIGNING_PASSWORD:-}" ]; then
  : "${OPEN_MINE_SIGNING_P12_BASE64:?Both retained signing secrets are required}"
  : "${OPEN_MINE_RETAINED_SIGNING_PASSWORD:?Both retained signing secrets are required}"
  printf '%s' "$OPEN_MINE_SIGNING_P12_BASE64" | base64 --decode > "$signing_dir/open-mine.p12"
  signing_password="$OPEN_MINE_RETAINED_SIGNING_PASSWORD"
  signing_source=retained
else
  signing_password=$(openssl rand -hex 32)
  signing_source=generated
fi
echo "::add-mask::$signing_password"
printf '%s' "$signing_password" > "$signing_dir/password.txt"
export OPEN_MINE_SIGNING_PASSWORD="$signing_password"
if [ "$signing_source" = generated ]; then
  keytool -genkeypair -keystore "$signing_dir/open-mine.p12" -storetype PKCS12 \
    -storepass:env OPEN_MINE_SIGNING_PASSWORD -keypass:env OPEN_MINE_SIGNING_PASSWORD \
    -alias open-mine -keyalg RSA -keysize 3072 -validity 10000 \
    -dname 'CN=Open Mine, OU=Application Signing, O=Open Mine' -noprompt
fi
# Invalid retained credentials must fail, never silently rotate the update identity.
keytool -list -keystore "$signing_dir/open-mine.p12" -storetype PKCS12 \
  -storepass:env OPEN_MINE_SIGNING_PASSWORD -alias open-mine >/dev/null
mkdir -p verification
printf '%s\n' "$signing_source" > verification/signing-source.txt
tar -C "$signing_dir" -czf "$signing_dir/signing-backup.tar.gz" open-mine.p12 password.txt
openssl cms -encrypt -binary -aes-256-cbc -in "$signing_dir/signing-backup.tar.gz" \
  -outform DER -out verification/signing-backup.cms .github/signing-backup-public.pem
rm "$signing_dir/signing-backup.tar.gz"
{
  echo "OPEN_MINE_SIGNING_STORE=$signing_dir/open-mine.p12"
  echo "OPEN_MINE_SIGNING_PASSWORD=$signing_password"
} >> "$GITHUB_ENV"
