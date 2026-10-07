# Retaining the Open Mine signing identity

The user authorized a new signing key. The tested development APK is signed with a
PKCS12 keystore whose alias is `open-mine`. Its public certificate fingerprint is
recorded with the APK evidence. A new identity cannot update an installation signed
with the unavailable previous key. Never uninstall or clear user data automatically.

Private signing material must stay outside the repository. CI emits only an
encrypted backup using `.github/signing-backup-public.pem`; the recipient's private
backup key is not committed. Keep the recovered final keystore and its password in
secure storage. APK checksums and certificate fingerprints are public evidence.

For subsequent builds, configure these GitHub Actions repository secrets from the
**final delivered key**, before building an update:

- `OPEN_MINE_SIGNING_P12_BASE64`: base64 encoding of `open-mine.p12`.
- `OPEN_MINE_RETAINED_SIGNING_PASSWORD`: the matching keystore password.

Both secrets must be supplied together. Invalid credentials fail the build instead
of silently generating a replacement. With neither present, the development
verification workflow generates a new disposable identity and records
`generated` in `verification/signing-source.txt`; such builds are not update
candidates for previously delivered APKs. With both present, it records `retained`.
Compare every update's certificate fingerprint with the final delivered one.

For local Gradle builds, use `OPEN_MINE_SIGNING_STORE` (absolute keystore path) and
`OPEN_MINE_SIGNING_PASSWORD` in the process environment. Do not place passwords in
shell command arguments, source files, logs, pull requests, or public artifacts.

The development verification workflow does not publish releases or install on
personal devices. The same-signature emulator reinstall check proves data retention
for that tested artifact, not compatibility with a differently signed old APK.
