# Setup Assistant, operation center and model capabilities (0.5.0-dev)

Location: the existing **Diagnostics** destination in the orbital HUD. The approved HUD
renderer, artwork, navigation and colors are unchanged; the new panel appears below the
existing diagnostics controls.

## Setup Assistant

`SetupDiagnostics.evaluate` turns facts from real probes into findings. Each finding has a
severity and, where one exists, a repair the Kotlin layer performs or opens:

| Finding | Source of truth | Repair |
|---|---|---|
| Linux runtime missing / unverified | `RuntimeInstallation` files, shell execution probe | Set up shell / Check shell |
| GGUF engine missing | engine receipt on disk | Install / repair engine |
| No local model / none running | model directory listing, HTTP health | Import GGUF / Open Models |
| Chat endpoint missing / invalid / model ID missing | saved assistant session, `ModelEndpoint.chatUri` | Open chat connection settings |
| Built-in endpoint selected but model stopped | session + model health | Load / import model |
| Low storage, limited RAM, non-ARM64 | `StatFs`, `ActivityManager`, ABIs | Guidance |
| Library issues / index not validated | `OpenMineObjectStore.inspect` | Rebuild knowledge index |
| Notifications denied (API 33+) | runtime permission | Allow notifications |
| Last operation failed or interrupted | operation journal | Retry (fixed parameters only) |

Nothing is repaired automatically. Every repair starts from a user tap; imports show a
confirmation stating what changes.

### Model file doctor

Diagnoses why a model cannot be imported. It uses the Storage Access Framework only: the
picker opens at Downloads, then Open Mine reads the display name, size, MIME type and the
first 1 MiB of the selected document. It never writes to or deletes the original.

It distinguishes:

- **Access** – the provider refused to open the document (expired grant, cloud-only file).
- **Location** – the user pasted a filesystem path or a `content://` link from another app.
  Paths do not grant access; the doctor extracts the filename and explains where to pick it.
- **Filename** – unfinished browser downloads (`.crdownload`, `.part`) and split GGUF parts
  (`-00001-of-00003.gguf`), which the single-file importer does not merge.
- **Format** – a saved Hugging Face web page, a Git LFS pointer, safetensors, PyTorch pickle,
  ZIP or JSON instead of GGUF weights.
- **Compatibility** – unsupported GGUF version or an architecture the bundled engine lacks.
- **Storage / memory** – the copy does not fit, or the model clearly exceeds device RAM.

When the file is valid, **Review import** shows the change (copy into private storage, unique
name on collision, original untouched) and then runs the existing importer, which re-validates
full metadata. The doctor reports success only when the new file appears in the model list.

## Operation center

`OperationJournal` records every runtime operation launched through
`OpenMineRuntime.launchOperation`: component, label, start/end time, outcome and an optional
fixed retry action. It is written atomically to `files/operations/journal.json`, bounded to 100
entries, and survives restarts. Operations still running when the process died are marked
**interrupted**. An unreadable journal is preserved as `journal.json.corrupt-<time>`.

Retry re-runs only fixed actions (check shell, setup, engine install, health check, load the
same model if it is still present). Imports require choosing the file again, because a
document grant cannot be assumed to persist.

## Model capabilities and routing

`ModelCatalog`/`ModelRouter` describe models by role (router, tool specialist, extraction,
general, planning), runtime, tool-calling support, context and memory. All models are still
reached through the one OpenAI-compatible `ModelClient` interface, so adding a model never
requires a new agent system.

The router picks the smallest healthy, runnable model that advertises the role a task needs,
keeps routine tasks on the device ahead of remote providers, uses a configured remote provider
for planning, honours an offline-only constraint and falls back to a general model.

The panel shows the current routing decision for each task kind and the candidates being
evaluated. **No candidate is bundled or claimed as supported.** Each starts `UNVERIFIED` and
needs a real load and inference result in the intended runtime:

| Candidate | Runtime | Status |
|---|---|---|
| BotPsy 0.8B | llama.cpp GGUF | Unverified; overlaps Needle 3 as a router |
| Cactus Needle 3 | Cactus engine | **Not runnable** – Cactus is not integrated; license reported as personal/hobby |
| Liquid LFM 1.7B | llama.cpp GGUF | Unverified; `lfm2`/`lfm2moe` import now accepted (see below) |
| Gemma 4 E2B / E4B | llama.cpp GGUF | Unverified in this build |
| Nanbeige 3B | llama.cpp GGUF | Unverified |
| Qwen3 1.7B | llama.cpp GGUF | Comparison baseline |

Memory figures in the catalog are planning estimates, not benchmarks.

## Assistant integration

The assistant gains one reviewed, read-only tool, `setup_diagnostics`. It takes no arguments,
requires approval like every other tool, returns findings without keys or file contents and
cannot perform repairs; the model can only tell the user which repair to choose.

## Importer fix

The bundled llama.cpp engine's architecture table includes `lfm2`, `lfm2moe`, `smollm3`,
`granite` and `granitemoe`, but the importer's allowlist rejected them. They are now accepted.
This changes import validation only; inference for these architectures has not been run on a
device in this work.
