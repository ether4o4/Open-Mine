# Verified development checkpoint

This is a development build, not a finished standalone AI assistant or a Play release.

## Implemented

- User-owned UTF-8 `.omd` records persist in app-private storage independently of model selection.
- Required labels, duplicate sections/labels, unlabeled content, UTC timestamps, priority and IDs are validated. Duplicate IDs are rejected without overwriting prior knowledge. Writes use atomic replacement. Imports are limited to 1 MiB.
- Records produce labeled index chunks; exact-token retrieval is available in Knowledge and Library Tools.
- Categories show actual imported records, rather than fictional installed models or connected services. Inspect and context-selection controls work on persisted IDs.
- AI Chat implements HTTPS OpenAI-compatible `chat/completions`, genuine network errors and model responses, retrieved source context, and a bounded read-only `search_library` function. API keys remain session-only. Up to two tool rounds and three calls per round are permitted; other tool names are rejected.
- Library context is transmitted to the model endpoint chosen by the user. Imported content is treated as reference data, not executable instructions. No shell commands or external service actions are allowed.
- Navigation indices, system insets, scrolling, readable text, back/cancel paths, transitions and orbital record selection were repaired.
- Gradle wrapper and Java 17 target alignment are present. CI uses the installed SDK manager's full path and runs unit tests, lint, APK and AAB builds.

## Known limitations and unverified coverage

- No Android GGUF JNI/NDK runtime, model weight import/download/loading or standalone inference is implemented.
- The HTTPS endpoint integration has not been exercised against a real model server on an Android device. No running Ollama/LM Studio/llama server or device was found. Endpoint support for tools is required; unsupported protocol errors are surfaced.
- Five JVM tests cover strict record round-tripping and rejection of malformed records. These do not validate Android filesystem persistence, UI, networking or model quality.
- No emulator is installed in the selected SDK and `adb devices` returned no devices. Device navigation, imports, rotations, accessibility, screenshots, restart persistence and inference tests are still required.
- Only labeled `.omd` text imports are supported. PDF, JSON, arbitrary files and GGUF imports are not supported. Retrieval is exact-token matching, not embeddings or model-weight training.
- Knowledge editing, autosaved drafts, library export/backup, full chat history and external connector/action execution are unfinished. USB storage is deferred scope.
- The unsigned release AAB requires an approved signing setup. No signing credentials were created or exposed and no app was published.

## Google Play gate

Official policy retrieved October 5, 2026 requires API 36 for new phone apps and updates from August 31, 2026. Check current build configuration against that requirement before submission: https://support.google.com/googleplay/android-developer/answer/11926878

Native dependencies also need 16 KiB page-size compatibility verification: https://developer.android.com/guide/practices/page-sizes

Release signing / Play App Signing setup: https://developer.android.com/studio/publish/app-signing

Store listing, privacy policy, Data safety disclosures (including optional endpoint transmission), content rating, device/prelaunch testing and any account-specific testing requirements remain release gates. A successful build is not evidence of production readiness.
