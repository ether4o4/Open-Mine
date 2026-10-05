# Phone feedback repair checkpoint

Confirmed code defect: terminal used a fresh PRoot process per command. It now uses the existing MVE persistent Bash session implementation. `cd` and `export` survive subsequent commands/navigation while the process remains alive; working directory and bounded terminal history are saved across app restart. Exported environment variables are not restored after Android kills the process. Files and installed packages remain in app storage.

Skills and Tools now open type-filtered record management with Create, Import, validated canonical Edit and confirmed Delete. They store real labeled records; custom command execution or automatic model invocation of user tools is not implemented by this checkpoint.

GGUF repair: refresh bundled script for existing installations; retain stdout/stderr and referenced build-log tail; surface structured error/detail; skip prerequisites when an existing binary passes --version; attempt ARM64 prebuilt only on ARM64. The prior phone screenshot's extracted text contains only the generic failure, so actual compiler/network/device cause is still unknown. These changes do not establish successful native inference.

HUD visual changes await the user's corrected reference images. Original feedback Library reads provided OCR/captions, but image pixels were not locally materialized: two supported prepare calls returned signed transfers only. Windows transfer helper metadata support was unavailable (os.setxattr); no alternate download workaround used. No visual QA claim.

Phone checks: run `mkdir -p /root/test && cd /root/test && export OPENMINE_TEST=works`; next run `pwd; echo "$OPENMINE_TEST"`; switch tabs and repeat; restart app and check cwd/history (environment starts fresh). Create/edit/import/delete SKILL and TOOL records; verify Knowledge sees them. Retry GGUF setup and supply retained error/log output if it fails. Load a compatible GGUF, start model, use on-device endpoint in AI Chat and confirm generated response. No device/emulator attached on build computer.
