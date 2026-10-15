#!/usr/bin/env bash
# Rebuild only the ABI with a reproduced legacy-fork compatibility failure.
# Usage: ANDROID_NDK_HOME=/sdk/ndk/27.2.12479018 bash scripts/build_proot_android.sh [evidence-dir]
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
evidence_dir="${1:-$project_dir/verification/native-build}"
mkdir -p "$evidence_dir"
evidence_dir="$(cd "$evidence_dir" && pwd)"
proot_commit=4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f
talloc_version=2.4.3
talloc_commit=77229f73c20af69ab0f3c96efbb229ff64a9dfe4
talloc_sha256=e01fb092aaed2b431be26674e2b791c77fb5984537c29b514e957582c6b31465
ndk_version=27.2.12479018
ndk_dir="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_HOME:-}/ndk/$ndk_version}}"
compiler="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android26-clang"
if [[ ! -x "$compiler" ]]; then
  printf 'Android NDK %s is required; set ANDROID_NDK_HOME.\n' "$ndk_version" >&2
  exit 1
fi
if ! grep -Eq "^Pkg.Revision[[:space:]]*=[[:space:]]*$ndk_version$" "$ndk_dir/source.properties"; then
  printf 'Pinned Android NDK version mismatch.\n' >&2
  exit 1
fi
build_dir="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/openmine-proot-build.XXXXXXXX")"
trap 'rm -rf "$build_dir"' EXIT
src_dir="$build_dir/proot"
git init --quiet "$src_dir"
git -C "$src_dir" remote add origin https://github.com/termux/proot.git
git -C "$src_dir" fetch --quiet --depth 1 origin "$proot_commit"
git -C "$src_dir" checkout --quiet --detach FETCH_HEAD
[[ "$(git -C "$src_dir" rev-parse HEAD)" == "$proot_commit" ]]
git -C "$src_dir" apply --check "$project_dir/third-party/proot-fork-compat.patch"
git -C "$src_dir" apply "$project_dir/third-party/proot-fork-compat.patch"

# Only the public talloc header is needed; runtime links the already-vetted
# repository libtalloc.so. No new binary dependency is downloaded.
talloc_header_dir="$build_dir/talloc-$talloc_version"
mkdir -p "$talloc_header_dir"
curl --fail --location --retry 3 --connect-timeout 15 --max-time 120 \
  "https://raw.githubusercontent.com/samba-team/samba/$talloc_commit/lib/talloc/talloc.h" -o "$talloc_header_dir/talloc.h"
printf '%s  %s\n' "$talloc_sha256" "$talloc_header_dir/talloc.h" | sha256sum --check --status
abi_dir="$project_dir/app/src/main/jniLibs/x86_64"
[[ -s "$abi_dir/libtalloc.so" && -s "$abi_dir/libproot-loader.so" && -s "$abi_dir/libproot-loader32.so" ]]

# Preserve inputs and corresponding patched source, licenses and exact patch.
sha256sum "$abi_dir"/*.so > "$evidence_dir/native-inputs.sha256"
cp "$project_dir/third-party/proot-fork-compat.patch" "$evidence_dir/"
cp "$project_dir/scripts/build_proot_android.sh" "$evidence_dir/"
cp "$talloc_header_dir/talloc.h" "$evidence_dir/talloc-$talloc_version.h"
tar -czf "$evidence_dir/proot-patched-source.tar.gz" --exclude=.git -C "$src_dir" .
"$compiler" --version > "$evidence_dir/compiler-version.txt"

# Unbundled loaders are supplied by PROOT_LOADER in ProotExecutor. The loader
# targets are deliberately supplied by the matching existing loader assets;
# they are not linked into this executable or replaced by this build.
# Only the PRoot executable gets the compatibility fix.
export CPPFLAGS="-DARG_MAX=131072 -I$talloc_header_dir"
export CFLAGS="-fPIE"
make -C "$src_dir/src" -j2 V=1 \
  --assume-old=loader/loader --assume-old=loader/loader-m32 \
  CC="$compiler" LD="$compiler" \
  STRIP="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  OBJCOPY="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objcopy" \
  OBJDUMP="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump" \
  PROOT_UNBUNDLE_LOADER=/unused/openmine-loader \
  LDFLAGS="-L$abi_dir -ltalloc -ldl -Wl,-z,noexecstack,-z,max-page-size=16384 -pie" \
  proot 2>&1 | tee "$evidence_dir/build.log"
"$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$src_dir/src/proot"
"$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -W -h -l -d "$src_dir/src/proot" > "$evidence_dir/proot-elf.txt"
python3 - "$evidence_dir/proot-elf.txt" <<'PYELF'
import re,sys
from pathlib import Path
text=Path(sys.argv[1]).read_text()
assert 'ELF64' in text and 'Advanced Micro Devices X86-64' in text, 'Wrong native ABI'
assert re.search(r'Type:\s+DYN',text), 'PRoot must be a PIE executable'
assert 'Requesting program interpreter: /system/bin/linker64' in text, 'Wrong Android linker'
needed=set(re.findall(r'NEEDED.*\[([^]]+)\]',text))
assert needed == {'libtalloc.so.2','libdl.so','libc.so'}, f'Unexpected native dependencies: {needed}'
loads=[line.split() for line in text.splitlines() if line.strip().startswith('LOAD ')]
assert loads and all(int(row[-1],16)>=16384 for row in loads), 'ELF LOAD alignment must support 16 KiB pages'
assert not re.search(r'GNU_STACK.*RWE',text), 'Executable stack is not permitted'
PYELF
install -m755 "$src_dir/src/proot" "$abi_dir/libproot.so"
sha256sum "$abi_dir"/*.so > "$evidence_dir/native-outputs.sha256"

python3 - "$project_dir" "$evidence_dir" "$proot_commit" "$ndk_version" "$talloc_version" "$talloc_sha256" "$talloc_commit" <<'PY'
import hashlib,json,sys
from pathlib import Path
project,evidence,commit,ndk,talloc,header_hash,talloc_commit=sys.argv[1:]
project,evidence=Path(project),Path(evidence)
sha=lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
(evidence/'provenance.json').write_text(json.dumps({
  'abi':'x86_64','android_api':26,'ndk_version':ndk,
  'source_repository':'https://github.com/termux/proot','source_commit':commit,
  'patch_sha256':sha(project/'third-party/proot-fork-compat.patch'),
  'patched_source_archive_sha256':sha(evidence/'proot-patched-source.tar.gz'),
  'output_sha256':sha(project/'app/src/main/jniLibs/x86_64/libproot.so'),
  'retained_talloc_sha256':sha(project/'app/src/main/jniLibs/x86_64/libtalloc.so'),
  'talloc_header_version':talloc,'retained_talloc_runtime_version':'2.4 (existing repository binary)',
  'talloc_header_sha256':header_hash,'talloc_header_source_commit':talloc_commit,
  'scope':'Only x86_64 libproot.so rebuilt. ARM binaries, loader binaries and runtime talloc retained.',
  'compatibility_fix':'Legacy fork translated to clone(SIGCHLD, 0, 0, 0, 0). Android security policies unchanged.',
  'runtime_verification':'Must be verified by actual Android NativeRuntimeTest; compiling this binary is insufficient.'
},indent=2)+'\n')
PY
