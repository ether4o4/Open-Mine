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
| Version | `0.5.0-dev` · version code `13` (source); latest published APK is 0.4.2-dev |
| Updated | 2026-10-15 |
| Package | `com.openmine` |
| Baseline | Verified 0.4.1 source bundle, original commit `21a33b8451241494d7b6c4f6a1d2296315f2bd8d` |
| Changes | PR #3 model fixes and 6% text increase, persistent runtime, Termux controls, panel heights, shell history and Hugging Face import entry point |

## New in 0.5.0-dev (source)

- **Built-in AI:** LFM3 1.7B Uncensored runs on the phone through an in-process llama.cpp engine
  (one-tap 1.13 GB verified download). No API key or terminal needed.
- **Open Mine Guide** is offered on first launch: setup steps, approval for every action during the
  tutorial, then a choice of full autonomy or ask every time. It can install the terminal, run
  commands (`apk add …`), request folder access, import models and diagnose problems.
- **Knowledge without context overload:** tiny index in the prompt, BM25 `search_knowledge` and
  bounded `read_slice`, multi-step lookups with citations. See [Built-in AI](docs/BUILT_IN_AI.md).

- **Setup Assistant** in Diagnostics: real checks of runtime, engine, models, chat endpoint,
  storage, memory, library and notifications, each with a user-started repair.
- **Model file doctor**: pick a file through Android's picker and learn exactly why it will
  not import (access, location, unfinished/split download, web page/LFS/safetensors instead
  of GGUF, unsupported architecture, storage or memory), then import it after review.
- **Operation center**: persistent record of setup, model and terminal operations, with
  interrupted-operation detection and fixed-parameter retry.
- **Model capability profiles and routing** for small specialists, general and remote models
  through the one OpenAI-compatible interface. Candidates (BotPsy, Needle 3, LFM, Gemma 4,
  Nanbeige, Qwen) are listed as unverified; none are bundled.
- Importer now accepts `lfm2`/`lfm2moe`/`smollm3`/`granite` GGUFs that the bundled engine
  implements. A reviewed read-only `setup_diagnostics` assistant tool.

Details: [Setup Assistant](docs/SETUP_ASSISTANT.md). The download link above is still the
0.4.2-dev release until a 0.5.0-dev APK is published from CI.

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
