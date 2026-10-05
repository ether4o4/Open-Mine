# Reused MVE code

Source: https://github.com/ether4o4/NeverSoft-Services-OS/tree/9786327e246adf745d93dd555ae0c96404aa41da

Selected source is incorporated into Open Mine. Open Mine does not depend on the MVE application.

- `ProotExecutor.kt`: process execution, concurrent bounded output, environment and timeout handling. Package name and truncation helper adapted; engine streaming operations capped at 30 minutes; persistent shell lifetime is uncapped with per-command timeouts.
- `PersistentSandboxShell.kt`: serialized live Bash session, command sentinels, bounded output and foreground cancellation. Package/helper adapted; cwd restored on process restart.
- `RootfsDownloader.kt`: Alpine URLs, tar extraction and rootfs configuration. Ktor transport replaced with Android HTTP transport; SHA-256 verification, extraction limits, staging and hardlink/path checks added.
- `morsllm.sh`: existing GGUF engine provisioning, model validation, start/stop/status and health checks. Release URL updated; prebuilt checksum verified; model alias set to `local`.
- PRoot/loader/talloc binaries copied from the source repository's `androidApp/src/main/jniLibs`.

MVE source license is Apache-2.0, preserved in `MVE-APACHE-2.0.txt`. Native third-party notices and upstream source links are preserved in `MVE-NATIVE-NOTICES.md` (PRoot GPL-2.0; talloc LGPL-3.0). Shipping binaries requires satisfying corresponding-source and other redistribution obligations; this file alone does not complete those obligations.

Development-only setup downloads Alpine/native packages and a checksum-verified engine. This path is disabled in release builds because native downloads need a different Google Play distribution approach. No security settings on the development computer were changed.
