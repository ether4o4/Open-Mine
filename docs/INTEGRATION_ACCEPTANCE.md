# Open Mine integration acceptance

Status: **not complete and not ready for unrestricted use**. At commit `a5a9f8a69893928410ed0bb95841d823880c078e`, browser/workspace, restart and real Ollama client checks passed; native Android shell bootstrap failed, preventing built-in GGUF verification. Further source changes need a new complete run. See [the exact checkpoint evidence](VERIFICATION_A5A9F8A.md).

## Intended artifact and source

- Package identity remains `com.openmine`.
- Intended existing variant: development **debug APK**, versionCode **5**, versionName **0.3.0-dev**, minSdk 26 / targetSdk 36.
- Functional source base: `298bfeaac499a8b52e724c4a9d09193cba774265` (`codex/functional-library-checkpoint`).
- Approved HUD source branch: `codex/reference-hud`; integrated controls display actual persisted records and runtime state.
- The final exact source commit is recorded by CI in `verification/source-commit.txt` and `verification/apk-evidence.json`; no prospective commit is presented as tested.
- CI checks out the branch/PR head itself, builds that source, inspects and installs that exact APK on a disposable hosted emulator, then rechecks its SHA-256. It does not publish a release, merge branches, or install on a user device.

## Completion matrix

“Implemented” below describes source, not executed acceptance. Final evidence must record actual outcomes against the exact final commit.

| Requirement | Implementation / check | Evidence required | Current acceptance |
|---|---|---|---|
| Existing functional work and HUD integrated | Functional branch runtime/storage plus approved native HUD adapter | Final build and rendered HUD/navigation tests | HUD integration passed on API29/API35 at a5a9f8a; final-source rerun and visual review still required |
| Real shell and reported defect | Persistent Bash/PRoot; CRLF/BOM deployed-runner repair; packaged asset gate; cancellation and output fixes | Actual Android shell setup, output/exit, cwd/env continuity, timeout/cancel/retry and restart; shell tests | FAILED on actual API29 PRoot: prerequisite post-install/trigger fork returned Function not implemented; repair and rerun pending |
| Browser | Embedded Android WebView, navigation, errors, stop/reload, persistent preferences, safe URL policy | BrowserRuntimeTest on Android; actual page rendering, history, blocked navigation and recovery | 3 actual WebView tests passed on API29 at a5a9f8a; browser not exercised on API35 |
| GGUF import, provision and inference | Real existing engine runner and model import; start/stop/health paths | Compatible actual GGUF filename/hash/license, engine hash/revision, generated answer, Android ABI/API, start/stop/cancel and failure recovery | Not executed at a5a9f8a because actual shell bootstrap failed; model download/hash does not establish inference |
| Ollama connection | Exact `/v1` base validation and preset; actual HTTP client | Running real Ollama and installed tag, actual generated answer/stream and failure recovery from Android | PASSED Android client against actual hosted Ollama0.5.7/qwen2.5:0.5b at a5a9f8a; host inference, not phone GGUF |
| Streaming and cancellation | SSE parser, total budget, connection cancellation and persistent turn states | JVM parser tests; Android protocol test; actual model streaming/cancel separately | Real Ollama SSE (50 updates) and client cancellation passed on API29; built-in GGUF streaming remains unverified |
| Reviewed tools | Bounded proposal validation and explicit approval; denial executes nothing; manual TOOL review | ReviewedModelToolsTest and Android protocol review round trip; real shell tool execution separately | Protocol approval/denial tests passed; real reviewed shell-tool execution remains unverified and has new pending checks |
| Project workflows | Persistent project/task/file workflows and contextual actions | CRUD/file import/export and permission/error handling; repository tests; Android journey | 4 project Android tests passed on API29/API35 plus force-stop persistence; new SAF picker/import/export journeys pending |
| Indexed knowledge | Strict bounded UTF-8 .omd records; source fingerprints; actual versioned index search; atomic recovery/migration | KnowledgeRepositoryTest, ObjectFormatTest and Android persisted retrieval | JVM index/format and Android indexed retrieval/restart passed at a5a9f8a; final-source rerun required |
| Storage preservation/migrations | Stable IDs/directories, atomic writes/rollback, corrupted data preserved and reported, old index regeneration | Old-library/index/AtomicFile fixtures, restart and update install using matching signature | Migration tests and actual force-stop/restart passed; same-signature APK-update retention phase added for next run |
| Settings and continuity | Persistent preferences, chat/task/session continuity and HUD refresh | Restart/recreation/draft restoration; cancelled/interrupted operations shown accurately | Preference/draft/session recovery passed in actual API29 force-stop/restart; final-source rerun required |
| Navigation/accessibility/platform | Adaptive HUD, full-size actionable controls, back/insets, app icons, reduced motion | Portrait/landscape/large font, TalkBack semantics, keyboard/back, API29 and modern target behavior | HUD/large-font/reduced-motion/viewport and project/keyboard checks passed API29/API35; manual TalkBack, landscape and API36 remain unverified |
| Purpose-driven polish | Workspace transitions, saved session continuity, contextual actions with feedback | Interrupt animations, reduced motion, no dead controls or fabricated operational states | Context actions, reduced-motion setting and session continuity passed; final transition/performance review remains pending |
| Final build identity | APK manifest/version, apksigner verification, native ELF/ABI inventory and SHA-256 | `apk-evidence.json`, `apk-signature.txt`, `apk-manifest.txt`, `app-debug.apk.sha256` | a5a9f8a artifact identity/signatures/checksums verified; later final-source artifact still required |
| Final full suite | JVM, lint, Python/shell, Android instrumentation on the final source | Unit XML/HTML, lint report, instrumentation log, runtime logs, exact source SHA | a5a9f8a:76 JVM,7 Python,15 API29 standard,2 restart phases,1 real Ollama,10 API35 passed; native shell failed |

## Test conditions and interpretation

CI conditions: GitHub-hosted Ubuntu 24.04, Java 17, Android build SDK 36 / build-tools 35.0.0. Identical signed app/test APKs are reused in an **API 29 x86_64 Pixel 2 emulator** for complete application/protocol/Ollama/restart checks, an **API 35 x86_64 Pixel 2 emulator** for HUD/accessibility/keyboard/project checks, and an independent **API 29 x86_64 Pixel 2 emulator with 4 GiB RAM / 8 GiB disk** for real PRoot shell setup and real Qwen0.5B Q4_K_M GGUF inference, all with animations disabled. Full device properties, memory/storage and installed package details are captured. This is an emulator, not a phone benchmark. Physical ARM64 performance, thermals and memory remain unverified unless separately recorded.

`AssistantProtocolRuntimeTest` uses a local test HTTP fixture and real Android networking/storage. It proves protocol/review/persistence behavior only. `BrowserRuntimeTest` uses actual WebView with local HTML fixtures. Neither proves real model inference. A health response alone also does not establish inference. Skipped/assumption-failed instrumentation is explicitly flagged rather than counted as complete.

## Exact outstanding external facts and limits

1. **New signing identity authorized:** the user explicitly requested generation of a new key. CI signs the exact tested APK and saves an encrypted key backup (`verification/signing-backup.cms`) for secure recovery and future updates. The APK certificate digest is recorded. It cannot update an existing installation signed with a different key. Do not uninstall or clear app data automatically. Prior HUD APK signer SHA-256 was `0d9f0096d9cc7dd2847c137627de4bb0e59d63f9b7fac19305fd14d4212cf663`; its old private key was not supplied.
2. **Native operation blocker:** actual API29 Alpine/PRoot bootstrap failed at prerequisite post-install/trigger `fork: Function not implemented`. Built-in GGUF and reviewed shell-tool workflows remain unverified until repair and rerun. Actual Ollama generation/SSE/client cancellation passed using the Android client and a hosted CPU server; that success is explicitly not Android GGUF or phone performance evidence.
3. **Local execution environment:** this workspace currently has Java 21 but no Android SDK/adb/emulator/KVM. Direct dependency requests fail at the configured proxy. Verification runs in the user-authorized GitHub-hosted CI; this local limitation is not presented as proof a remote test cannot run.
4. **Native platform support:** shipped runtime libraries exist for ARM64, ARMv7 and x86_64. CI records each ELF's load alignment; packaging does not establish ARM execution or 16 KiB page-size support. API35 currently verifies UI/project journeys only; native PRoot/GGUF there is unverified. Physical ARM64/ARMv7 execution and a 16 KiB-page Android runtime remain unverified; low-alignment 32-bit stubs/libraries are recorded in the APK inventory, not silently treated as runtime-compatible.
5. **Release distribution:** production signing/Play/native-download packaging and corresponding-source redistribution obligations are not completed by this development APK. A new development signing key is authorized; release publication, paid service, merge or installation on user devices is not authorized.
6. **Specification scope:** the accompanying full integration specification is not present as a separate supplied file. This checklist covers the explicit completion-loop workflows and repository phone-repair/checkpoint requirements; it does not invent acceptance for unseen requirements.

## Verification history

- First integration commit `490595356c3af7136b198f844e4bdf917ae60717`: [CI run 37573306202](https://github.com/ether4o4/Open-Mine/actions/runs/37573306202) reports successful shell verification, JVM unit tests, lint, app/instrumentation APK assembly and APK identity inspection. Final results: 68 JVM tests passed with no failures; 14 Android tests executed, 12 passed and 2 failed (browser back navigation, HUD settings test tag). Both failures were corrected and their affected checks passed in the second checkpoint. Subsequent changes, real-model phases and final signing still require their own final-source execution.

- Second integration commit `a5a9f8a69893928410ed0bb95841d823880c078e`: [CI run 37577011467](https://github.com/ether4o4/Open-Mine/actions/runs/37577011467). 76 JVM tests and 7 shell/process fixture tests passed; lint had 0 errors / 33 warnings. API29 standard 15/15, separate restart 1/1 + 1/1, real Ollama 1/1, API35 HUD/project 10/10 passed without skips. Native shell bootstrap failed; GGUF did not run. [Exact artifact hashes, timings, environment and limitations](VERIFICATION_A5A9F8A.md). SAF, reviewed shell tools, model filename and APK-update-retention changes remain pending next-source verification.

## Delivery gate

Deliver the exact tested APK with its evidence files. A successful workflow is necessary but not sufficient: every critical advertised workflow above must either have real operation evidence or remain plainly unverified. Do not label the product “ready,” “fully functional,” or “complete” while GGUF/Ollama/shell operation or other critical acceptance remains missing.
