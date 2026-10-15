# Open Mine

Android AI workspace with the original blue orbital HUD, world map, navigation rail and shortcut dock.

## Latest APK

**[Download Open Mine 0.4.2-dev APK](https://github.com/ether4o4/Open-Mine/releases/download/v0.4.2-dev/Open-Mine-0.4.2-dev.apk)**

**Development preview — separate test installation only.** The available signing
key differs from the published 0.4.1 APK. This download cannot update that
installation. Keep your existing Open Mine installation and data; do not uninstall
it to bypass Android's signature check. An in-place update requires its original
signing key. The package remains `com.openmine`.

[Release notes, exact tested commit, checksums and verification](https://github.com/ether4o4/Open-Mine/releases/tag/v0.4.2-dev)
· [Rolling download bookmark](https://github.com/ether4o4/Open-Mine#latest-apk)

| Current source | Details |
| --- | --- |
| Version | `0.4.2-dev` · version code `12` |
| Updated | 2026-10-15 |
| Package | `com.openmine` |
| Baseline | Verified 0.4.1 source bundle, original commit `21a33b8451241494d7b6c4f6a1d2296315f2bd8d` |
| Changes | PR #3 model fixes and 6% text increase, persistent runtime, Termux controls, panel heights, shell history and Hugging Face import entry point |

## What's included

- A retained Bash session and a foreground Android service. Ordinary background
  commands survive completed shell submissions; explicitly managed services also
  survive terminal cancellation. Android force-stop and system restrictions still
  apply.
- Optional authenticated Termux control: `openmine start`, `openmine shell`,
  `openmine status`, `openmine stop`, and managed-process commands.
- Browser and Terminal panels resize in height only. Each remembers its own size;
  tap the top handle for a slider and reset control.
- Return/Send submits shell commands and clears accepted input. Up/down arrows
  recall the last 100 commands across restarts and restore unfinished drafts.
- **Import from Hugging Face** opens the model catalog in your device browser.
  Download compatible GGUF weights, return to Open Mine, and choose the downloaded
  file. This uses the existing importer; other model formats are not made runnable.
- Updated Qwen/Gemma validation, model-server lifetime and health handling, import
  storage checks, and 6% larger text while retaining system font scaling.

The original HUD assets, project storage paths, and application ID are preserved.
See [runtime and Termux setup](docs/RUNTIME_CONTROL.md) for controls and limits.

## Verification

The release notes record checks against the exact delivered APK, including any
failures or unverified device behavior. Hosted emulator inference is not proof of
physical S24 Ultra inference. The original 0.4.1 APK and source remain available:

[Previous 0.4.1 APK](https://drive.google.com/file/d/12siB5tumckPtduMRV6yC86NryhKmf1pE/view)
· [Original source bundle](https://drive.google.com/file/d/14r5fDTTG8n2gpPxFU42vtKQ_pEq8JDac/view)
· [Restored HUD reference](https://drive.google.com/file/d/14wmiyhon8h70df7SiHYWbr-W6KZWCdqX/view)
