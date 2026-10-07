#!/usr/bin/env bash
set -euo pipefail
mkdir -p verification/restart
qemu=$(adb -e shell getprop ro.kernel.qemu | tr -d '\r')
test "$qemu" = 1
collect_restart_evidence() {
  adb -e pull /sdcard/Android/data/com.openmine/files/verification/. verification/restart/app-files/ >/dev/null 2>&1 || true
  adb -e logcat -d > verification/restart/logcat.txt 2>&1 || true
}
trap collect_restart_evidence EXIT
for phase in seedState verifyRestoredState; do
  adb -e shell am instrument -w -r -e isolatedEmulator true \
    -e class "com.openmine.RestartPersistenceTest#$phase" \
    com.openmine.test/androidx.test.runner.AndroidJUnitRunner > "verification/restart/$phase.txt"
  cat "verification/restart/$phase.txt"
  python3 - "$phase" <<'PY'
import pathlib, re, sys
text = pathlib.Path('verification/restart', sys.argv[1] + '.txt').read_text()
assert re.search(r'OK \(1 tests?\)', text), 'Restart persistence phase did not pass exactly one test'
assert not re.search(r'INSTRUMENTATION_STATUS_CODE: -[34]\b', text), 'Restart test was skipped/assumed'
assert not any(x in text for x in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')), 'Restart phase failed'
PY
  if [ "$phase" = seedState ]; then adb -e shell am force-stop com.openmine; fi
done
adb -e pull /sdcard/Android/data/com.openmine/files/verification/restart-persistence.json verification/restart/restart-persistence.json
sha256sum --check verification/android/installed-apk.sha256
