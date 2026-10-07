# Android x86_64 legacy fork compatibility repair

CI run [37577011467](https://github.com/ether4o4/Open-Mine/actions/runs/37577011467), source `a5a9f8a69893928410ed0bb95841d823880c078e`, ran the actual development APK in Android API 29 x86_64. Alpine HTTPS package downloads succeeded. Package hooks failed with `bash-5.2.37-r0.post-install: fork: Function not implemented` and `busybox-1.37.0-r12.trigger: fork: Function not implemented`. Shell setup was correctly reported failed.

The bundled executable identifies its source as `4dba3afb-dirty`. Upstream `src/tracee/seccomp.c` at `4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f` lacks a legacy `fork` translation. Android exposes equivalent process creation through `clone`, as used by bionic. The narrow patch translates legacy `fork` into `clone(SIGCHLD, 0, 0, 0, 0)` through PRoot's existing compatibility handler. Android's kernel policy, app identity, permissions, SELinux and syscall restrictions remain unchanged.

The translation follows the [PRoot maintainer's proposal](https://github.com/termux/proot/issues/237#issuecomment-1178592429) and [reporter's successful test](https://github.com/termux/proot/issues/237#issuecomment-1181298046). Those reports support the diagnosis but do not replace Open Mine's actual Android runtime acceptance test.

The auditable patch also supplies the missing `<string.h>` declarations for `strcmp`/`memset` required by modern C99 compilers. In the existing two-case `setresuid`/`setresgid` switch arm, an exhaustive `else` replaces the redundant second condition so the compiler can prove `ret` is assigned; it does not change either operation or permission check. Compiler errors/warnings are not disabled.

## Reproducible build

On Linux, install Android NDK `27.2.12479018` and run before assembling the APK:

```sh
ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018" bash scripts/build_proot_android.sh verification/native-build
```

Requirements: Java/Android tooling for the subsequent APK build; the native script itself requires Bash, git, curl, make, tar, SHA-256 tools and Python 3. Source downloads require HTTPS access to GitHub. The script verifies the exact PRoot git commit and the talloc header checksum. It builds API 26 x86_64, PIE, with a maximum ELF page size of 16 KiB. The runtime talloc 2.4 binary and matching existing PRoot loaders are retained. Only x86_64 `libproot.so` is replaced in the build workspace; ARM binaries are unchanged.

The talloc 2.4.3 header comes from Samba source commit `77229f73c20af69ab0f3c96efbb229ff64a9dfe4`, SHA-256 `e01fb092aaed2b431be26674e2b791c77fb5984537c29b514e957582c6b31465`. This is a header, not a downloaded replacement binary. It retains its LGPL-3.0-or-later notice. PRoot retains its GPL-2.0-or-later source/license notices.

`verification/native-build` records compiler version, exact source/patch/header hashes, input/output native hashes, link metadata, build output, and a complete archive of patched PRoot source with its licenses. These build artifacts must accompany the tested APK evidence. The existing native provenance in `MVE-NATIVE-NOTICES.md` still applies to retained binaries.

The original dirty patch set is unknown. Rebuilding from the exact upstream base plus the documented narrow patch makes this executable's new source reproducible. Both assembly and **actual Android** setup, persistent shell execution, cancellation, reviewed-tool execution and GGUF inference must pass against that APK before operational readiness is claimed.
