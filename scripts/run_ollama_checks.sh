#!/usr/bin/env bash
set -euo pipefail
: "${TESTED_COMMIT:?Exact tested source commit is required}"
mkdir -p verification/ollama
qemu=$(adb -e shell getprop ro.kernel.qemu | tr -d '\r')
test "$qemu" = 1
adb -e reverse tcp:11434 tcp:11434
collect_ollama_evidence() {
  adb -e pull /sdcard/Android/data/com.openmine/files/verification/. verification/ollama/android/ >/dev/null 2>&1 || true
  adb -e logcat -d > verification/ollama/android-logcat.txt 2>&1 || true
  adb -e reverse --remove tcp:11434 >/dev/null 2>&1 || true
}
trap collect_ollama_evidence EXIT
model_digest=$(cat verification/ollama/model-digest.txt)
adb -e shell am instrument -w -r \
  -e class com.openmine.OllamaRuntimeTest \
  -e openMineRealOllama true \
  -e openMineOllamaModel qwen2.5:0.5b \
  -e openMineOllamaDigest "$model_digest" \
  -e openMineTestedCommit "$TESTED_COMMIT" \
  com.openmine.test/androidx.test.runner.AndroidJUnitRunner > verification/ollama/instrumentation.txt
cat verification/ollama/instrumentation.txt
python3 - <<'PY'
import pathlib, re
text = pathlib.Path('verification/ollama/instrumentation.txt').read_text()
match = re.search(r'OK \((\d+) tests?\)', text)
assert match and int(match.group(1)) > 0, 'Real Ollama tests failed or did not run'
assert not re.search(r'INSTRUMENTATION_STATUS_CODE: -[34]\b', text), 'Real Ollama checks skipped/assumed'
assert not any(x in text for x in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')), 'Real Ollama runtime failed'
PY
adb -e pull /sdcard/Android/data/com.openmine/files/verification/ollama-runtime.json verification/ollama/ollama-runtime.json
sha256sum --check verification/android/installed-apk.sha256
