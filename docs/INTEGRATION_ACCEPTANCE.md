# Open Mine integration acceptance

Status: **not complete and not ready for unrestricted use**. Source implementation is being integrated and final Android verification has not yet run. Passing compilation or protocol fixtures must not change inference requirements to “verified.”

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
| Existing functional work and HUD integrated | Functional branch runtime/storage plus approved native HUD adapter | Final build and rendered HUD/navigation tests | Pending final CI and render inspection |
| Real shell and reported defect | Persistent Bash/PRoot; CRLF/BOM deployed-runner repair; packaged asset gate; cancellation and output fixes | Actual Android shell setup, output/exit, cwd/env continuity, timeout/cancel/retry and restart; shell tests | Source/Python checks pass in first CI; dedicated NativeRuntimeTest and real GGUF execution pending |
| Browser | Embedded Android WebView, navigation, errors, stop/reload, persistent preferences, safe URL policy | BrowserRuntimeTest on Android; actual page rendering, history, blocked navigation and recovery | Pending instrumentation; loopback HTML fixture is browser evidence only |
| GGUF import, provision and inference | Real existing engine runner and model import; start/stop/health paths | Compatible actual GGUF filename/hash/license, engine hash/revision, generated answer, Android ABI/API, start/stop/cancel and failure recovery | Pending dedicated native Android CI job; public official Qwen0.5B Q4_K_M weights are pinned by repository revision and checked against LFS SHA-256 |
| Ollama connection | Exact `/v1` base validation and preset; actual HTTP client | Running real Ollama and installed tag, actual generated answer/stream and failure recovery from Android | Pending real Ollama runtime test; CI provisions a pinned official hosted CPU server/model and exercises the Android client via adb reverse |
| Streaming and cancellation | SSE parser, total budget, connection cancellation and persistent turn states | JVM parser tests; Android protocol test; actual model streaming/cancel separately | Source implemented; final tests pending; fixtures never count as inference |
| Reviewed tools | Bounded proposal validation and explicit approval; denial executes nothing; manual TOOL review | ReviewedModelToolsTest and Android protocol review round trip; real shell tool execution separately | Pending final tests/runtime |
| Project workflows | Persistent project/task/file workflows and contextual actions | CRUD/file import/export and permission/error handling; repository tests; Android journey | Source integration pending final verification |
| Indexed knowledge | Strict bounded UTF-8 .omd records; source fingerprints; actual versioned index search; atomic recovery/migration | KnowledgeRepositoryTest, ObjectFormatTest and Android persisted retrieval | JVM index/format tests passed at first integration commit; final-source rerun pending |
| Storage preservation/migrations | Stable IDs/directories, atomic writes/rollback, corrupted data preserved and reported, old index regeneration | Old-library/index/AtomicFile fixtures, restart and update install using matching signature | File/index migration tests passed at first integration commit; actual process-restart and new-key installation checks pending |
| Settings and continuity | Persistent preferences, chat/task/session continuity and HUD refresh | Restart/recreation/draft restoration; cancelled/interrupted operations shown accurately | Pending final tests |
| Navigation/accessibility/platform | Adaptive HUD, full-size actionable controls, back/insets, app icons, reduced motion | Portrait/landscape/large font, TalkBack semantics, keyboard/back, API29 and modern target behavior | Pending device inspection; API29 alone does not verify target36 edge behavior |
| Purpose-driven polish | Workspace transitions, saved session continuity, contextual actions with feedback | Interrupt animations, reduced motion, no dead controls or fabricated operational states | Pending real UI checks |
| Final build identity | APK manifest/version, apksigner verification, native ELF/ABI inventory and SHA-256 | `apk-evidence.json`, `apk-signature.txt`, `apk-manifest.txt`, `app-debug.apk.sha256` | Pending final artifact |
| Final full suite | JVM, lint, Python/shell, Android instrumentation on the final source | Unit XML/HTML, lint report, instrumentation log, runtime logs, exact source SHA | Pending CI |

## Test conditions and interpretation

CI conditions: GitHub-hosted Ubuntu 24.04, Java 17, Android build SDK 36 / build-tools 35.0.0. Identical signed app/test APKs are reused in an **API 29 x86_64 Pixel 2 emulator** for complete application/protocol/Ollama/restart checks, an **API 35 x86_64 Pixel 2 emulator** for HUD/accessibility/keyboard/project checks, and an independent **API 29 x86_64 Pixel 2 emulator with 4 GiB RAM / 8 GiB disk** for real PRoot shell setup and real Qwen0.5B Q4_K_M GGUF inference, all with animations disabled. Full device properties, memory/storage and installed package details are captured. This is an emulator, not a phone benchmark. Physical ARM64 performance, thermals and memory remain unverified unless separately recorded.

`AssistantProtocolRuntimeTest` uses a local test HTTP fixture and real Android networking/storage. It proves protocol/review/persistence behavior only. `BrowserRuntimeTest` uses actual WebView with local HTML fixtures. Neither proves real model inference. A health response alone also does not establish inference. Skipped/assumption-failed instrumentation is explicitly flagged rather than counted as complete.

## Exact outstanding external facts and limits

1. **New signing identity authorized:** the user explicitly requested generation of a new key. CI signs the exact tested APK and saves an encrypted key backup (`verification/signing-backup.cms`) for secure recovery and future updates. The APK certificate digest is recorded. It cannot update an existing installation signed with a different key. Do not uninstall or clear app data automatically. Prior HUD APK signer SHA-256 was `0d9f0096d9cc7dd2847c137627de4bb0e59d63f9b7fac19305fd14d4212cf663`; its old private key was not supplied.
2. **Real inference evidence:** no usable GGUF/model-server artifact or real Ollama runtime has yet been exercised against the integrated APK. Native/runtime checks must record model and engine provenance plus actual responses; mocks remain test-only.
3. **Local execution environment:** this workspace currently has Java 21 but no Android SDK/adb/emulator/KVM. Direct dependency requests fail at the configured proxy. Verification is therefore being moved to the user-authorized GitHub-hosted CI; this local limitation is not presented as proof a remote test cannot run.
4. **Native platform support:** shipped runtime libraries exist for ARM64, ARMv7 and x86_64. CI records each ELF's load alignment; packaging does not establish ARM execution or 16 KiB page-size support. Any incompatible native alignment remains a platform limitation until rebuilt and tested.
5. **Release distribution:** production signing/Play/native-download packaging and corresponding-source redistribution obligations are not completed by this development APK. A new development signing key is authorized; release publication, paid service, merge or installation on user devices is not authorized.
6. **Specification scope:** the accompanying full integration specification is not present as a separate supplied file. This checklist covers the explicit completion-loop workflows and repository phone-repair/checkpoint requirements; it does not invent acceptance for unseen requirements.

## Verification history

- First integration commit `490595356c3af7136b198f844e4bdf917ae60717`: [CI run 37573306202](https://github.com/ether4o4/Open-Mine/actions/runs/37573306202) reports successful shell verification, JVM unit tests, lint, app/instrumentation APK assembly and APK identity inspection. Final results: 68 JVM tests passed with no failures; 14 Android tests executed, 12 passed and 2 failed (browser back navigation, HUD settings test tag). These failures are being corrected before a new full run. Subsequent changes, real-model phases and final signing still require their own final-source execution.

## Delivery gate

Deliver the exact tested APK with its evidence files. A successful workflow is necessary but not sufficient: every critical advertised workflow above must either have real operation evidence or remain plainly unverified. Do not label the product “ready,” “fully functional,” or “complete” while GGUF/Ollama/shell operation or other critical acceptance remains missing.
