"""Verify and Bash-parse the actual APK asset, not the working-tree script."""
import argparse
import pathlib
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=pathlib.Path)
parser.add_argument("--bash", default="bash")
args = parser.parse_args()
with zipfile.ZipFile(args.apk) as archive:
    data = archive.read("assets/sandbox/morsllm.sh")
assert b"\r" not in data, "Packaged shell asset contains carriage returns"
assert not data.startswith(b"\xef\xbb\xbf"), "Packaged shell asset contains BOM"
assert data.startswith(b"#!"), "Packaged shell asset lacks shebang"
data.decode("utf-8", errors="strict")
with tempfile.TemporaryDirectory(prefix="openmine-apk-shell-") as folder:
    extracted = pathlib.Path(folder) / "morsllm.sh"
    extracted.write_bytes(data)
    subprocess.run([args.bash, "-n", extracted.as_posix()], check=True, timeout=30)
print(f"PASS: actual APK shell asset: {len(data)} bytes, LF-only, no BOM, Bash syntax valid")
