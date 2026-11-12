# Built-in AI: Needle 3 + LFM3 (0.5.0-dev)

Open Mine includes its own AI so it can guide setup from the first launch, before any terminal,
model or API key exists. Other models and API keys remain optional for heavier work.

## Two models, one interface

| | Needle 3 (Cactus) | LFM3 1.7B Uncensored (AtomicChat) |
|---|---|---|
| Role | Fast tool routing for setup actions | Conversation, reasoning, commands, knowledge questions |
| Delivery | Bundled in the APK (35 MB) | One-tap download on first run (1.13 GB, resumable, SHA-256 verified) |
| Runtime | Cactus 4.1.0, linked into `libopenmine_ai.so` (ARM only) | llama.cpp b12318 server running **in-process** on 127.0.0.1:8181 with a per-install key |
| Measured here | ~0.08 s per routing call, 79 MB RAM (x86_64 host) | ~17 tokens/s generation, 4 threads (x86_64 host) — phone speed not yet measured |

LFM3 speaks the OpenAI-compatible API, so the same server can also be selected in the main chat
("Use LFM3 in the main chat"). Nothing depends on the Linux/PRoot runtime.

## Routing

1. If LFM3 is running, it handles the message with the guide's tools.
2. Otherwise Needle 3 picks a tool. Its choice is used only when it names a known tool with
   confidence ≥ 0.3; otherwise the guide says it is unsure and points to the step buttons.
3. Every proposal is validated in Kotlin (known tool, exact argument names, text types, length limits,
   command policy) before anything is shown or run.

Measured on this commit's tool set: Needle 3 chose correctly in 6 of 12 phrasings, returned no call in
4, and was wrong in 2 (one at 0.67 confidence). LFM3 chose correctly in 9 of 10 and wrote exact
commands (`apk add git python3`). Needle's suggestions are therefore always reviewed by the user.

## Guide tools

`install_runtime`, `download_chat_model`, `request_folder_access` (Android folder picker, persisted
grant), `run_command` (Alpine terminal, real exit code and output), `run_diagnostics`,
`import_model_file` (model file doctor), `search_knowledge`, `read_slice`, `save_memory`.

## Approvals

- During the tutorial every consequential action asks, showing the exact command or change.
- At the end of the tutorial the user chooses **Ask every time** or **Full autonomy**, and can switch
  anytime in the guide.
- Full autonomy runs commands and downloads automatically. Destructive commands (`rm -rf /`, `~`,
  `$HOME`, `mkfs`, `dd of=`, fork bombs, reboot), and saving memories, always ask.
- Read-only tools (diagnostics, knowledge search/read) never prompt; their data stays on the phone.
- Folder access is always granted through Android's own picker.

## Knowledge without filling the context (RLM pattern)

The Recursive Language Model / progressive-disclosure pattern is implemented natively in Kotlin
(no Python REPL is needed on the phone):

- The system prompt carries only a short index, e.g. `Knowledge: memory 1 item; project:cabin 1 item`.
- `search_knowledge` ranks ~500-token passages with BM25 (library records, ingested documents, memory
  notes) and returns ids and 220-character previews.
- `read_slice` returns one passage, at most ~1,000 tokens.
- The agent loop allows up to 6 tool rounds, so the model searches, reads, refines and then answers
  with citations. Verified with the real LFM3: search → read `doc.solar#1` → correct cited answer.

Needle 3's embeddings were evaluated for hybrid search but separate topics poorly (0.948 relevant vs
0.914 unrelated), so ranking is BM25 only. Memory remains the existing local, user-approved store.

## Verification status

| Check | Result |
|---|---|
| Cactus C API (load/init/complete/embed) via the official 4.1.0 runtime | Passed on x86_64 host |
| Embedded llama.cpp server through the JNI bridge: start, tool call, 401 without key, stop, restart | Passed on x86_64 host with real LFM3 |
| arm64/armv7/x86_64 native builds, 16 KB alignment, Cactus linked on ARM only | Verified in the APK |
| In-process server on Android (`BuiltInServerTest`) | Runs in the CI native-inference job (x86_64 emulator, small GGUF) |
| Needle 3 on Android | **Not testable in CI** (no Android x86_64 Cactus build); first run is on an ARM phone |
| LFM3 on a physical S24 Ultra | **Not yet measured** |
