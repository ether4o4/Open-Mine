#!/usr/bin/env bash
# An actual local hosted-CPU service for Android client integration, not phone GGUF inference.
set -euo pipefail
: "${RUNNER_TEMP:?This helper runs on the disposable GitHub hosted runner}"
mkdir -p verification/ollama
ollama_version=0.5.7
ollama_runtime="$RUNNER_TEMP/openmine-ollama"
ollama_archive="$RUNNER_TEMP/openmine-ollama-$ollama_version.tgz"
mkdir -p "$ollama_runtime"
curl --fail --location --retry 3 --connect-timeout 20 --max-time 600 \
  "https://github.com/ollama/ollama/releases/download/v$ollama_version/ollama-linux-amd64.tgz" \
  --output "$ollama_archive"
sha256sum "$ollama_archive" > verification/ollama/server-distribution.sha256
tar -xzf "$ollama_archive" -C "$ollama_runtime"
rm "$ollama_archive"
export OLLAMA_HOST=127.0.0.1:11434
export OLLAMA_MODELS="$RUNNER_TEMP/openmine-ollama-models"
export OLLAMA_NUM_PARALLEL=1
export OLLAMA_MAX_LOADED_MODELS=1
export OLLAMA_KEEP_ALIVE=10m
export CUDA_VISIBLE_DEVICES=''
nohup "$ollama_runtime/bin/ollama" serve > verification/ollama/server.log 2>&1 &
printf '%s\n' "$!" > verification/ollama/server.pid
python3 - <<'PY'
import json, pathlib, time, urllib.request
for attempt in range(90):
    try:
        with urllib.request.urlopen('http://127.0.0.1:11434/api/version', timeout=2) as response:
            version = json.load(response)
        assert version['version'] == '0.5.7', version
        pathlib.Path('verification/ollama/server-version.json').write_text(json.dumps(version, indent=2) + '\n')
        break
    except Exception:
        if attempt == 89:
            raise
        time.sleep(1)
PY
timeout 600 "$ollama_runtime/bin/ollama" pull qwen2.5:0.5b > verification/ollama/model-pull.txt 2>&1
curl --fail --max-time 15 http://127.0.0.1:11434/api/tags > verification/ollama/model-tags.json
python3 - <<'PY'
import json, pathlib
models = json.loads(pathlib.Path('verification/ollama/model-tags.json').read_text())['models']
model = next(x for x in models if x.get('name') == 'qwen2.5:0.5b' or x.get('model') == 'qwen2.5:0.5b')
assert model['digest'] and model['size'] > 0
pathlib.Path('verification/ollama/model-digest.txt').write_text(model['digest'] + '\n')
pathlib.Path('verification/ollama/scope.txt').write_text(
    'Inference runs in real Ollama 0.5.7 on the disposable GitHub-hosted Linux CPU.\n'
    'The tested Android APK reaches it through adb reverse at 127.0.0.1:11434.\n'
    'This verifies the Android Ollama client, actual generation, streaming and cancellation.\n'
    'It does not measure phone inference speed or verify the built-in Android GGUF engine.\n')
PY
