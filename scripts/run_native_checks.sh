#!/usr/bin/env bash
set -euo pipefail
: "${TESTED_COMMIT:?Exact source commit is required}"
: "${RUNNER_TEMP:?Hosted runner temporary directory is required}"
mkdir -p verification/native
adb -e wait-for-device
qemu=$(adb -e shell getprop ro.kernel.qemu | tr -d '\r')
test "$qemu" = 1
adb -e shell getprop > verification/native/device-properties.txt
adb -e shell cat /proc/meminfo > verification/native/memory-before.txt
adb -e shell df -h /data > verification/native/storage-before.txt
adb -e logcat -c
collect_native_evidence() {
  adb -e logcat -d > verification/native/logcat.txt 2>&1 || true
  adb -e shell dumpsys package com.openmine > verification/native/installed-package.txt 2>&1 || true
  adb -e pull /sdcard/Android/data/com.openmine/files/native-runtime-evidence.json verification/native/native-runtime-evidence.json >/dev/null 2>&1 || true
  adb -e pull /sdcard/Android/data/com.openmine/files/verification/. verification/native/app-files/ >/dev/null 2>&1 || true
  adb -e shell df -h /data > verification/native/storage-after.txt 2>&1 || true
}
trap collect_native_evidence EXIT
sha256sum app/build/outputs/apk/debug/app-debug.apk > verification/native/installed-apk.sha256
adb -e install -r app/build/outputs/apk/debug/app-debug.apk
adb -e install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -e shell am instrument -w -r -e class com.openmine.NativeRuntimeTest \
  -e nativeRuntime true -e openMineTestedCommit "$TESTED_COMMIT" \
  com.openmine.test/androidx.test.runner.AndroidJUnitRunner > verification/native/shell-instrumentation.txt
cat verification/native/shell-instrumentation.txt
python3 - <<'PY'
import pathlib, re
text = pathlib.Path('verification/native/shell-instrumentation.txt').read_text()
match = re.search(r'OK \((\d+) tests?\)', text)
assert match and int(match.group(1)) > 0, 'Actual Android shell tests failed or did not run'
assert not re.search(r'INSTRUMENTATION_STATUS_CODE: -[34]\b', text), 'Native shell checks skipped/assumed'
assert not any(x in text for x in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')), 'Native shell runtime failed'
PY
adb -e pull /sdcard/Android/data/com.openmine/files/native-runtime-evidence.json verification/native/native-runtime-evidence.json
model_device_path=/sdcard/Android/data/com.openmine/files/runtime-fixtures/model.gguf
adb -e shell mkdir -p /sdcard/Android/data/com.openmine/files/runtime-fixtures
adb -e push "$RUNNER_TEMP/openmine-runtime-model.gguf" "$model_device_path"
model_sha=$(cat verification/gguf/model-sha256.txt)
model_source=$(python3 -c 'import json; print(json.load(open("verification/gguf/model-provenance.json"))["source_url"])')
adb -e shell am instrument -w -r -e class com.openmine.GgufInferenceTest \
  -e openMineRealGguf true -e openMineTestedCommit "$TESTED_COMMIT" \
  -e openMineGgufPath "$model_device_path" -e openMineGgufSha256 "$model_sha" \
  -e openMineGgufSource "$model_source" \
  com.openmine.test/androidx.test.runner.AndroidJUnitRunner > verification/native/gguf-instrumentation.txt
cat verification/native/gguf-instrumentation.txt
python3 - <<'PY'
import pathlib, re
text = pathlib.Path('verification/native/gguf-instrumentation.txt').read_text()
match = re.search(r'OK \((\d+) tests?\)', text)
assert match and int(match.group(1)) > 0, 'Actual Android GGUF tests failed or did not run'
assert not re.search(r'INSTRUMENTATION_STATUS_CODE: -[34]\b', text), 'Android GGUF checks skipped/assumed'
assert not any(x in text for x in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')), 'Actual GGUF runtime failed'
PY
adb -e pull /sdcard/Android/data/com.openmine/files/verification/gguf-inference.json verification/native/gguf-inference.json
sha256sum --check verification/native/installed-apk.sha256
