"""Download a real public chat model at an exact revision; retain provenance, not weights, as evidence."""
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.parse
import urllib.request

repo = 'Qwen/Qwen2.5-0.5B-Instruct-GGUF'
filename = 'qwen2.5-0.5b-instruct-q4_k_m.gguf'
output = Path('verification/gguf')
output.mkdir(parents=True, exist_ok=True)
request = urllib.request.Request(f'https://huggingface.co/api/models/{repo}?blobs=true', headers={'User-Agent': 'OpenMine-runtime-verification'})
with urllib.request.urlopen(request, timeout=60) as response:
    metadata = json.load(response)
revision = metadata['sha']
assert re.fullmatch(r'[a-f0-9]{40}', revision), 'Model repository must resolve to an exact revision'
assert not metadata.get('private') and not metadata.get('gated'), 'This check requires ungated public weights'
license_name = metadata.get('cardData', {}).get('license')
assert license_name == 'apache-2.0', f'Unexpected model license: {license_name}'
model_file = next(item for item in metadata['siblings'] if item['rfilename'] == filename)
lfs = model_file.get('lfs', {})
expected_hash = (lfs.get('sha256') or lfs.get('oid') or '').removeprefix('sha256:')
expected_size = model_file.get('size') or lfs.get('size')
assert re.fullmatch(r'[a-f0-9]{64}', expected_hash), 'No source LFS SHA-256 available'
assert isinstance(expected_size, int) and 32 < expected_size < 1024 * 1024 * 1024, 'Unexpected model size'
url = f'https://huggingface.co/{repo}/resolve/{revision}/{urllib.parse.quote(filename)}?download=true'
target = Path(os.environ['RUNNER_TEMP']) / 'openmine-runtime-model.gguf'
partial = target.with_suffix('.part')
digest = hashlib.sha256()
received = 0
try:
    with urllib.request.urlopen(url, timeout=120) as response, partial.open('wb') as destination:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            received += len(chunk)
            assert received <= expected_size, 'Model download exceeds its declared size'
            digest.update(chunk)
            destination.write(chunk)
    assert received == expected_size, 'Truncated model download'
    assert digest.hexdigest() == expected_hash, 'Model SHA-256 differs from pinned source metadata'
    with partial.open('rb') as source:
        assert source.read(4) == b'GGUF', 'Downloaded weights lack GGUF header'
    partial.replace(target)
finally:
    partial.unlink(missing_ok=True)
evidence = {'repository': repo, 'revision': revision, 'filename': filename, 'license': license_name,
            'source_url': url, 'bytes': received, 'sha256': digest.hexdigest(),
            'scope': 'Real quantized 0.5B chat weights for inference inside the Android emulator. No phone throughput claim.'}
(output / 'model-provenance.json').write_text(json.dumps(evidence, indent=2) + '\n')
(output / 'model-sha256.txt').write_text(digest.hexdigest() + '\n')
print(json.dumps(evidence, indent=2))
