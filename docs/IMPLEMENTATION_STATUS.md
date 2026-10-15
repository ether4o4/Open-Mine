# Open Mine 0.2.0 development test build

Independent Open Mine incorporates selected existing MVE code. It does not require the MVE app. Current priority is a functional phone-test APK; Play packaging is deferred by user instruction.

## Implemented

- Persistent labeled `.omd` knowledge, strict bounded UTF-8 imports, duplicate-ID protection, atomic writes, exact-token retrieval and labeled source chunks.
- Actual imported category records, orbital selection, context selection by ID, inspect controls, navigation/back/cancel fixes, system insets, scrolling and transitions.
- Incorporated MVE PRoot executor, rootfs extraction/configuration, PRoot/talloc binaries and GGUF provisioning/start/stop/status script. Source revision and licenses: `third-party/REUSE.md`.
- Open Mine-owned Linux storage/UI; SHA-256-verified rootfs download and staged extraction; GGUF imports; checksum-verified prebuilt engine; successful health check required before reporting a started model.
- Manual shell commands require review/confirmation, have a 30-second timeout and bounded output. Engine operations support cancellation and a 30-minute process cap. Keep the app open during setup.
- Genuine OpenAI-compatible inference through on-device loopback (`http://127.0.0.1:8080/v1`, alias `local`) or an explicit HTTPS endpoint. Retrieved sources are sent to the endpoint; keys remain session-only.
- Bounded `search_library` and fixed read-only `linux_system_info` (`uname -a`) model tools. Other names and arbitrary model commands are rejected. Knowledge is reference data, not execution authority.
- API 36 / Java 17 / AGP 8.10.1, Gradle wrapper, CI tests/lint/APK/AAB and artifact uploads.

## Validation and remaining coverage

Seven JVM tests cover record round-tripping/malformed records, archive sibling-path traversal and truncated archives. Engine script passes `bash -n`. Debug APK signature verifies. Refer to build/CI reports for current lint results.

No Android device/emulator was available. Provisioning, GGUF loading/inference/tool calls, UI, restart persistence, memory/thermal behavior and cancellation still require phone testing. Source/build verification is not an executed inference test. The user will test the APK.

Supported imports are labeled `.omd` knowledge and GGUF weights. PDF, arbitrary JSON and embeddings are unsupported. GGUF header/size checks reject obvious bad files; actual loading checks architecture and memory compatibility. Library editing/autosaved drafts/export/full chat history, model deletion, MCP authentication and broader actions remain unfinished. USB is deferred.

## Deferred release work

Development setup downloads native code; provisioning is disabled in release builds. The unsigned AAB is a packaging artifact, not a usable Play release. Bundled engine distribution, redistribution obligations and native 16 KiB compatibility need subsequent work. No release-signing credentials were created, agreements accepted or app published.

References: [executable-code policy](https://support.google.com/googleplay/android-developer/answer/16559646), [API target policy](https://support.google.com/googleplay/android-developer/answer/11926878), [16 KiB compatibility](https://developer.android.com/guide/practices/page-sizes), [signing](https://developer.android.com/studio/publish/app-signing).
