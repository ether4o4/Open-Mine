# Built-in AI: LFM3 (0.5.0-dev)

Open Mine includes its own AI engine so it can guide setup, run commands and answer questions about
your knowledge on the phone, with no API key and no Linux runtime required. Other models and API
keys remain optional for heavier work.

## Model and engine

| | |
|---|---|
| Model | LFM3 1.7B Uncensored (AtomicChat), `AD-Q4_K_M`, 1.13 GB, LFM Open License v1.0 |
| Delivery | One-tap download on first run: resumable, verified by size and SHA-256 before use |
| Engine | llama.cpp b12318 compiled into `libopenmine_ai.so`; its OpenAI-compatible server runs **in-process** on 127.0.0.1:8181 with a per-install key (other apps get HTTP 401) |
| Reuse | The same server can be selected in the main chat ("Use LFM3 in the main chat") |

## Why one model

Routing was measured on 45 setup requests not used for any tuning (38 actions, 7 chat):

| Router | Right actions | Wrong actions | Missed | Chat left alone |
|---|---|---|---|---|
| Needle 3 (Cactus), confidence ≥ 0.3 | 10 | 10 | 21 | 4/7 |
| Fixed keyword rules | 11 | 1 | 26 | 7/7 |
| LFM2.5-350M | 24 | 8 | 7 | 6/7 |
| **LFM3 1.7B Uncensored** | **37** | **2** | **1** | 5/7 |

Needle 3 was removed: its confidence did not separate right from wrong choices. LFM3 handles every
request once downloaded (about 7 s per request on a 4-core x86 host; phone speed not yet measured).

Before LFM3 is downloaded, the guide is driven by its step buttons. Typed requests get at most one
fixed keyword match for the setup steps, offered for approval; otherwise the guide says it needs LFM3.

## Guide tools

`install_runtime`, `download_chat_model`, `request_folder_access` (Android folder picker, persisted
grant), `run_command` (Alpine terminal, real exit code and output), `run_diagnostics`,
`import_model_file` (model file doctor), `search_knowledge`, `read_slice`, `save_memory`.
Every call is validated in Kotlin: known tool, exact argument names, text values, length limits, and
the terminal command policy.

## Approvals

- During the tutorial every consequential action asks, showing the exact command or change.
- At the end of the tutorial the user chooses **Ask every time** or **Full autonomy**, changeable anytime.
- Full autonomy runs commands and downloads automatically. Destructive commands (`rm -rf /`, `~`,
  `$HOME`, `mkfs`, `dd of=`, fork bombs, reboot) and saving memories always ask.
- Read-only tools (diagnostics, knowledge search/read) never prompt; their data stays on the phone.
- Folder access is always granted through Android's own picker.

## Knowledge without filling the context (RLM pattern)

Implemented natively in Kotlin:

- The system prompt carries only a short index, e.g. `Knowledge: memory 1 item; project:cabin 1 item`.
- `search_knowledge` ranks ~500-token passages with BM25 across library records, ingested documents
  and memory notes, returning ids and short previews.
- `read_slice` returns one passage of at most ~1,000 tokens.
- Up to 6 tool rounds per answer: search, read, refine, then answer with citations. Verified with the
  real LFM3: search → read `doc.solar#1` → correct cited answer.

## Optional helper model (tagging)

An opt-in second slot loads a small GGUF directly through libllama (the embedded server allows one
instance). The guide offers LiquidAI LFM2.5-230M (153 MB, pinned revision and SHA-256). It writes a
summary and keywords for each note once, keyed by a hash of the note's content. Tags only influence
search ranking; LFM3 always reads the original text.

Measured on 30 notes and 24 questions worded differently from the notes (x86_64 host):

| | Right note in search top 3 | LFM3 read the right note |
|---|---|---|
| Plain BM25 | 14 / 24 | 21 / 24 |
| BM25 + helper tags | 17 / 24 | 22 / 24 |

Tagging costs about 1.3–1.7 s per note, once. Letting the small model shortlist passages for LFM3 was
also tried and did not beat BM25's own ranking, so it was not built. Because LFM3 already retries
searches with different words, the helper's benefit is small; it stays optional.

## Verification status

| Check | Result |
|---|---|
| Embedded server through the JNI bridge: start, tool call, 401 without key, stop, restart | Passed on x86_64 host with real LFM3 |
| arm64 / armv7 / x86_64 native builds, 16 KB page alignment | Verified in the APK |
| In-process server on Android (`BuiltInServerTest`) | Passed in CI native-inference job (x86_64 emulator, small GGUF) |
| Helper slot through the JNI bridge: load, tag, emoji round trip, oversized input rejected, unload/reload | Passed on x86_64 host with real LFM2.5-230M |
| LFM3 on a physical phone (S24 Ultra / S25) | **Not yet measured** |
