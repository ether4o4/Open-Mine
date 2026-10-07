#!/usr/bin/env bash
# Installs only on the disposable hosted emulator. Never targets a physical/user device.
set -euo pipefail
mkdir -p verification/android
adb -e wait-for-device
qemu=$(adb -e shell getprop ro.kernel.qemu | tr -d '\r')
if [ "$qemu" != "1" ]; then
  echo 'Refusing installation: the selected adb target is not an Android emulator.' >&2
  exit 1
fi
adb -e shell getprop > verification/android/device-properties.txt
adb -e shell wm size > verification/android/display.txt
adb -e shell wm density >> verification/android/display.txt
adb -e shell df -h /data > verification/android/storage-before.txt
adb -e shell cat /proc/meminfo > verification/android/memory-before.txt
adb -e logcat -c
collect_evidence() {
  adb -e logcat -d > verification/android/logcat.txt 2>&1 || true
  adb -e shell dumpsys package com.openmine > verification/android/installed-package.txt 2>&1 || true
  adb -e shell df -h /data > verification/android/storage-after.txt 2>&1 || true
  adb -e pull /sdcard/Android/data/com.openmine/files/. verification/android/app-files/ >/dev/null 2>&1 || true
}
trap collect_evidence EXIT
sha256sum app/build/outputs/apk/debug/app-debug.apk > verification/android/installed-apk.sha256
adb -e install -r app/build/outputs/apk/debug/app-debug.apk
adb -e install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
if [ -n "${OPENMINE_TEST_CLASSES:-}" ]; then
  test_filter=(-e class "$OPENMINE_TEST_CLASSES")
else
  test_filter=(-e notClass com.openmine.NativeRuntimeTest,com.openmine.GgufInferenceTest,com.openmine.OllamaRuntimeTest,com.openmine.RestartPersistenceTest)
fi
adb -e shell am instrument -w -r "${test_filter[@]}" com.openmine.test/androidx.test.runner.AndroidJUnitRunner > verification/android/instrumentation.txt
cat verification/android/instrumentation.txt
python3 - <<'PY'
import json, pathlib, re
report = pathlib.Path('verification/android/instrumentation.txt').read_text()
match = re.search(r'OK \((\d+) tests?\)', report)
if not match or int(match.group(1)) == 0 or any(x in report for x in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')):
    raise SystemExit('Android instrumentation failed or did not execute any tests; inspect instrumentation.txt and logcat.txt')
ignored = len(re.findall(r'INSTRUMENTATION_STATUS_CODE: -[34]\b', report))
result = {'tests_reported_by_runner': int(match.group(1)), 'ignored_or_assumption_failures': ignored,
          'runtime': 'Hosted Android emulator; exact API/ABI/device in device-properties.txt',
          'scope': 'Executed test cases only; HTTP fixture tests do not establish GGUF or Ollama inference.'}
pathlib.Path('verification/android/test-summary.json').write_text(json.dumps(result, indent=2) + '\n')
if ignored:
    raise SystemExit('Android tests were skipped/assumed; affected requirements remain unverified')
PY
sha256sum --check verification/android/installed-apk.sha256
