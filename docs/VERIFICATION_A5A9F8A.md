# Verification checkpoint — a5a9f8a

This checkpoint is **not a completion or delivery approval**. Browser, workspace, persistence and real Ollama client checks passed. Native Android shell bootstrap failed, so built-in GGUF inference did not run. Later source changes require a new complete run.

## Exact artifact

- Source commit: `a5a9f8a69893928410ed0bb95841d823880c078e`.
- [GitHub Actions run 37577011467](https://github.com/ether4o4/Open-Mine/actions/runs/37577011467).
- Variant/package: development `debug`, `com.openmine`, versionCode **5**, versionName **0.3.0-dev**, minSdk **26**, targetSdk **36**.
- APK size: **26,866,579 bytes**.
- APK SHA-256: `bd2cb49346c68a157128cc5a192934d60d423506260e2d40d6484a26e786c23a`.
- Instrumentation APK SHA-256: `6d14e04faa052444a8c20fc1c1c6f44120114e3f14cd54ef7ab699fd775e45f1`.
- APK signer certificate SHA-256: `4a0b1fb91544315b8b9fa11444ab11261d08ae86b9869946a0a046c06e717be9`. `apksigner verify` passed; app and test signatures matched. The user authorized this new signing identity; it cannot update a differently signed existing installation.
- Packaged ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`. Packaging is not execution evidence for all three.

Both successful emulator jobs checked the downloaded APK against this commit/hash and rechecked that exact APK after execution. The encrypted signing backup is retained in the build evidence; private signing material is not recorded here.

## Executed checks

| Check | Result | Evidence |
|---|---|---|
| JVM suite | **76 passed**, 0 failures, 0 errors, 0 skipped, 19 test classes | `app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| Linux engine lifecycle fixture suite | **7 passed**, 8.914 s | `verification/python-tests.txt`; fixture processes are not model inference |
| Android lint | **0 errors, 33 warnings** | `app/build/reports/lint-results-debug.*`; warnings remain visible |
| API29 ordinary integration | **15 passed**, 63.136 s, no skipped/assumed cases | [job 112649641260](https://github.com/ether4o4/Open-Mine/actions/runs/37577011467/job/112649641260), `verification/android/instrumentation.txt` |
| API29 process restart | Seed **1 passed**, restore **1 passed**, 0.639 s / 2.780 s | `verification/restart/{seedState,verifyRestoredState}.txt`, `restart-persistence.json` |
| API29 real Ollama client | **1 passed**, total test 4.844 s | Same API29 job, `verification/ollama/instrumentation.txt`, `ollama-runtime.json` |
| API35 HUD and projects | **10 passed**, 70.682 s, no skipped/assumed cases | [job 112649641372](https://github.com/ether4o4/Open-Mine/actions/runs/37577011467/job/112649641372), `verification/android/instrumentation.txt` in API35 artifact |
| API29 real native shell | **Failed during bootstrap** | `verification/native/native-runtime-evidence.json` and native instrumentation/logcat |
| Built-in GGUF inference | **Not executed** because preceding shell check failed | Do not infer success from downloaded model weights or completed compilation |

API29 ordinary integration contains 2 assistant protocol tests, 3 actual WebView tests, 6 HUD tests and 4 project tests. It verifies real HTML navigation/history/restoration, error/reload/stop/blocked navigation, empty and populated HUDs, record creation/context persistence, large fonts, reduced motion, corrupted library-session recovery, project/task/draft behavior, storage failure handling and launcher/keyboard configuration. Assistant protocol fixtures verify reviewed tool requests and indexed source transmission; they do not establish inference.

The separate force-stop sequence used two independent Android instrumentation invocations. The app PID changed from **3921** to **3963**. The restored process retained project/knowledge IDs and source byte hashes, indexed retrieval, task status, text/binary files, unsaved draft, selected context/route, motion/haptic settings and assistant partial text. A test-created `STREAMING` turn recovered as `INTERRUPTED`, and the production Activity reopened its persisted project/task. This proves that sequence, not an in-place APK update; the same-signature `adb install -r` retention phase is being added for the next source.

## Actual Ollama operation

The production Android `ModelClient` connected to `http://127.0.0.1:11434/v1` through `adb reverse`. Inference ran in actual **Ollama 0.5.7 on the GitHub-hosted Linux CPU**, using **qwen2.5:0.5b**, Q4_K_M, 494.03M parameters, 397,821,319 model bytes. This is not phone GGUF inference or a phone performance benchmark.

Model digest: `a8b0c51577010a279d933d14c2a8ab4b268079d44c5c8830c0a93900f1827c67`.

The recorded response was nonempty generated prose about a local workspace. The Android client received **50 distinct SSE text updates** before completion. That request took **4,491 ms** under this CI setup. A second real request was cancelled after its first streamed text (`1`); the client terminated with `ModelRequestCancelledException`, returned no successful reply, and recorded **2 ms** cancellation latency. These are observed CI values, not performance promises. Disconnecting the client does not prove the server unloaded the model or stopped all generation internally.

## Device conditions

Both successful jobs used GitHub-hosted Ubuntu 24.04, Android emulator **37.2.12.0**, a Pixel 2 profile, x86_64, a **1080×1920** display at **420 dpi**, and disabled system animations.

- API29 fingerprint: `Android/sdk_phone_x86_64/generic_x86_64:10/QSR1.210820.001/7663313:userdebug/test-keys`; emulator raised RAM to **2048 MB**.
- API35 fingerprint: `Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`; emulator raised RAM to **2560 MB**.
- The independent failed native job used API29/x86_64 with **4 GiB RAM / 8 GiB disk**.

API35 executed HUD/project tests only. It did not execute native PRoot/GGUF, Ollama, browser or real-runtime tool checks. No ARM64 or ARMv7 device was used in this recorded verification. API26/API36 operation, physical-phone thermals/memory, and a 16 KiB page-size device remain unverified. ELF alignment screening alone cannot establish those results.

## Confirmed native blocker and pending changes

The Android-native attempt downloaded Alpine and entered real `apk add` prerequisite installation. Bash's post-install script and BusyBox's trigger failed with **`fork: Function not implemented`**, producing exit code **1** and `Linux prerequisites failed`. The native receipt has `status: failed` and an empty successful-step list. This is a reproduced runtime failure, not a compile or network-success claim. Shell repair is in progress; GGUF import/load/generation/cancellation and reviewed shell-tool operation remain unverified.

Next-source work includes the shell fix, actual reviewed-tool checks, model import filename preservation, Android Storage Access Framework journeys, and same-signed APK update retention. None inherit a pass from this checkpoint. Final delivery requires new evidence against their exact source commit.
