"""Require emulator jobs to use the APK built and inspected for their exact source SHA."""
import hashlib
import json
import os
from pathlib import Path

evidence = json.loads(Path('verification/apk-evidence.json').read_text())
assert evidence['source_commit'] == os.environ['TESTED_COMMIT'], 'Downloaded APK commit mismatch'
apk = Path('app/build/outputs/apk/debug/app-debug.apk')
assert hashlib.sha256(apk.read_bytes()).hexdigest() == evidence['apk_sha256'], 'Downloaded APK checksum mismatch'
test_apk = Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
assert hashlib.sha256(test_apk.read_bytes()).hexdigest() == evidence['instrumentation_apk_sha256'], 'Downloaded instrumentation APK checksum mismatch'
print('Exact inspected APK identity verified:', evidence['source_commit'], evidence['apk_sha256'])
