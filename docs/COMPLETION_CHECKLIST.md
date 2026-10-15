# Evidence-driven completion

This checklist supersedes older acceptance summaries for this work. `Implemented`
means source exists; it does not mean runtime acceptance passed.

## Correction: 0.4.1-dev approved HUD restored

The user explicitly rejected the 0.4.0 layout redesign. Restore the approved
pre-polish HUD renderer, root host, artwork, navigation, colors and utility
layouts from `5db7be72441f15bc5a4e4634da257ce7b9b097f7`. The original exact-layout
constraint applies. Polish must stay within that design; the native replacement
navigation and glass renderer are not the accepted interface.

Retain the functional fixes: persistent installation state derived from files,
separate runtime health, readable command results, off-main credential lookups,
late-result guards, tool review and immediate draft preservation. Version code
11 permits a same-signer update over 0.4.0 without clearing existing data.
Corrected-build checks and screenshots belong to its separate delivery evidence.

## Superseded: 0.4.0-dev native UI and navigation polish

The polish request was incorrectly implemented as a major UI/navigation upgrade.
The user rejected this redesign and the correction above supersedes it. Open Mine's
original artwork, Ubuntu typography, cyan/violet identity, records and user files
are retained; the fixed scaled artboard and duplicated navigation are replaced.

| Requested improvement | Implementation | Verification scope |
|---|---|---|
| Find chat immediately | Chat is the fresh-install home; permanent Chat / Workspace / Models / Shell / More controls | Android navigation, back, recreation and tap-target checks |
| Readable, sharp glass UI | Density-native text, vector orbital rings, original high-resolution atlas, clean gradient glass; no screenshot crops or grain overlay | Compare native screenshots; physical S24 frame-rate/thermal performance unmeasured |
| Fluid chat | Pinned composer, independently scrolling transcript, history/connection/context sheets, stop and retry | Real HTTP fixture UI journey plus controller streaming/cancellation tests; fixture output is not inference |
| Clear local-model path | Models is a primary destination; after loading, Open chat selects the built-in endpoint/model | Device GGUF execution remains separately unverified |
| Durable setup labels | Completed-install receipts checked against disk; separate shell/engine/live-model health | Installation-state tests; no saved URL or marker alone asserts current inference |
| Responsive endpoint typing | Debounced credential lookup off the main thread; late lookups cannot overwrite manually typed keys | Android controller race check |
| Clear terminal output | stdout/stderr, actual exit code/cwd, cancellation and timeout labels | Formatter tests; native compiler-link issue is not claimed fixed here |
| Safe navigation | Page-scoped state, stable record during transitions, draft flush on tab changes; reduced motion and keyboard-aware space | Project immediate-exit, large-font and restoration tests |

No credentials, models or user data are bundled as demo content. Existing saved
tool opt-in is retained; new chats default to tools off. API errors offer an
explicit retry without tools when applicable. Build/test outcomes and APK identity
belong to the accompanying delivery evidence, not inferred from this inventory.

## Follow-up: 0.3.4-dev model import publication

- The S24 Ultra report's `destination.gguf -> incoming.part` paths match the
  final `Files.createLink(link, existing)` operation after copying and inspecting
  the selected GGUF. The report does not include the exception class or errno;
  a particular Android filesystem restriction is not established from it alone.
- Replaced the hard-link requirement with a same-directory move, serialized
  across app imports. Existing filenames receive unique suffixes; existing model
  files and the user's selected source remain intact. Avoids a second weights
  copy and avoids `ATOMIC_MOVE`, which can replace an existing destination.
- Saving failures now identify the operation and exception class/reason instead
  of displaying only two internal paths. Approved HUD renderer/assets/layout
  are unchanged. Version code 9 uses the same workspace signer as 0.3.3.
- Added unit coverage for concurrent duplicate imports and destination symlinks,
  plus two Android publication tests using the reported filename in app storage
  and a Unicode/space directory. These test file publication, not GGUF inference.
- The separate delivery evidence records build, lint and test outcomes. No S24
  Ultra execution or offline generation is claimed for this fix.

## Follow-up: 0.3.2-dev

0.3.3-dev adds `qwen3moe` to GGUF import validation for the selected Huihui model.
The exact bundled engine revision implements that architecture; a focused test
covers mixed Q4_K/Q6_K tensor metadata. This is compatibility validation, not an
Android inference result. The Windows USB helper checks model hashes before and
after transfer and uses `adb install -r`; it never uninstalls or clears app data.

- Preserved the approved HUD renderer/assets/layout. Runtime utility copy now
  accurately describes installing the included ARM64 engine.
- Bundled checksum-pinned ARM64 llama-server. Setup now installs its previously
  missing explicit libstdc++/libgcc dependencies; incompatible bundled execution
  reports a retained log instead of silently starting a compiler install.
- Added per-call reviewed project-file listing, bounded UTF-8 reads, and creation
  of new text/code files. Uses only the conversation's selected active project;
  rejects traversal, archive/exclusion, overwrite, and arbitrary execution.
- Raised response budget from 256 to 1024 tokens and kept the limit message accurate.
- Existing source fallback is pinned for new checkouts; capped CPU threads and
  batching for phone memory. No source build or Android ARM execution is claimed.
- User requested a fast build/code double-check instead of another full Android
  acceptance run. Final build/unit/lint evidence is delivered separately. Previous
  API29 results belong to 0.3.1 and must not be relabeled as this build's results.
- Still no physical-phone ADB connection from this cloud session. GGUF offline
  generation, Android native shell cancellation, and API35 flows remain unverified.

## Base established before changes

- Main: `596efe3292c81262a956f86b56e13edbf279568c` (reverified).
- Operational code: `dedb3883b18326d0c55c5906506a949a4b3e6523` (reverified).
- Approved HUD: `4585eab`, application `de332a93a686daf979eba7a396383063512db48b`.
  Evidence: PR #2, `design-qa.md`, four comparison images, and native screenshot
  artifact 11430884322 from run 37501452978. Preserved in `/workspace/evidence/baseline`.
- No AGENTS.md exists in the repository or workspace ancestors. Clean clone;
  work is isolated on `codex/evidence-completion`. No user data exists here.
- Run 37578625716: build passed; native shell cancelled job reached final printf;
  GGUF was never run; API29 23 tests/1 SAF failure; API35 13 tests/3 SAF failures.
  Earlier status documents must not be treated as current acceptance.
- Integration changed approved colors, rail spacing, card artwork, detail geometry,
  tab names and utility navigation. Visual preservation is not established by ancestry.

## Capability checks

Final execution reports are delivered alongside the APK; they identify the exact
commit, checksums and device. Do not infer acceptance from implementation or from
an earlier source revision's results.

| Capability | Current state | Evidence / remaining acceptance |
|---|---|---|
| Approved HUD renderer | Implemented; native captures inspected | Original rail/orbits/artwork/detail geometry/dock restored; data and honest empty states replace demo catalog. Final screenshot set required |
| API endpoint/model selection and discovery | Implemented | Live `/models` request; saved URL never implies connectivity |
| API generation/SSE/retries/cancel/errors | Implemented; fixture checks verified | Real Ollama acceptance is separate; no paid remote credentials available |
| Secure persistent credentials | Implemented; Android verified on API29 | Keystore AES-GCM, endpoint-bound ciphertext in noBackupFilesDir, restart and backup exclusion assertion |
| GGUF import/management/runtime/inference | Implemented; acceptance BLOCKED | Real pinned Qwen 0.5B weights downloaded and metadata inspected. Android setup fails Alpine downloads in this proxy-only environment; no offline inference pass claimed |
| GGUF corruption/architecture/memory/storage preflight | Implemented; unit verified | Bounded metadata parser, conservative memory reserve; device OOM/restart matrix not complete |
| Shell entire-submission cancellation | Implemented; host verified | Stops ancestors before descendants, kills only owned tree, subsequent statement and descendant regression assertions. Android blocked at runtime download |
| Shell cwd/env/Unicode/pipes/exit/recovery | Implemented; host verified | Independent Android native runner retains all assertions |
| Browser nav/history/reload/stop/upload/errors | Implemented; Android verified on API29 | Real WebView navigation, lifecycle, failure recovery and SAF upload checks |
| Browser app downloads/camera/mic | Implemented; unverified | Explicit destination/review, streaming/cancel, origin checks and Android permission prompt; physical permission/download journeys outstanding |
| Bounded reviewed model tools | Implemented; Android fixture verified | Explicit approval/rejection; only scoped library search and fixed system information. No model-directed arbitrary execution/browser access |
| Distinct stores and scoped retrieval | Implemented; unit verified | Conversation/project/shared opt-in, exclusions/archive revocation; no global selection grant |
| Memory controls/source preview | Implemented; unit verified | Explicit note view/edit/forget; documents/project deletion removes derived text; remote destination/source preview |
| OMD/project SAF import/export | Implemented; initial API29 verified | Repaired picker root selection/stale-node recovery without skipped assertions; final API29/API35 required |
| Document extraction/search | Implemented; real PDF Android verified | UTF-8 txt/md/csv/json, PDFBox text, DOCX XML; extracted chunks searched within project. OCR unsupported; DOCX device case outstanding |
| Bounded streaming/progress/cancel | Implemented; partial verification | 16MiB project files, 64MiB ingestion, 8GiB weights, 16GiB backup limits; weights never opened as text. Device maximum-size/storage-exhaustion cases unverified |
| Portable backup/staged restore | Implemented; unit verified | v1 manifest, checksums, conflicts, rollback, fresh directory, truncation/cancel/traversal; optional weights and shell home files. Android fresh-install/migration/kill-mid-restore still unverified |
| Lifecycle/accessibility/scroll/input | Implemented; partial Android verification | Activity recreation, saved navigation/drafts, reduced motion, large font; full physical keyboard/rotation/performance matrix outstanding |
| APK identity/signature/delivery | Pending final artifact inspection | Original private signing key unavailable; never uninstall an existing installation to accept this key |
| Physical phone/ARM/16KiB pages | BLOCKED: no device attached | Packaging ABIs is not execution evidence |

### Authoritative storage

- `open_mine_objects`: structured OMD source records.
- `assistant`: conversations and explicit source permissions; no API keys.
- `ai_memory`: explicit durable notes, separate from history and phone storage.
- `knowledge_documents`: imported document copies, extracted text and provenance.
- `open_mine_projects`: project files/tasks and recoverable drafts.
- `linux-sandbox/home/.morsvitaest/llm/models`: optional GGUF weights.
- `linux-sandbox/rootfs` and managed engine directories: rebuildable runtime.
- `open_mine_index.json`: rebuildable scoped record index; document chunks are
  rebuilt from extracted text when searched.
- Android Keystore plus `noBackupFilesDir/provider-credentials`: credentials.

Portable exports explicitly list their contents. Managed runtime binaries/logs,
indexes, API credentials, shell key stores/history and phone originals are excluded.
Ordinary shell home work is included; symlinks are rejected rather than followed.
Backup v1 rejects unknown versions; no claim of migrations from nonexistent older
portable backup formats. Legacy conversation schema remains readable.

## HUD interaction inventory and routing

| Visible interaction | Operational handler/state/backend | Verification |
|---|---|---|
| Rail 01–04; dock Models/Projects/Connectors/Knowledge | MainActivity.navigate; persisted screen and per-screen record ID | Native API29 navigation exercised |
| Rail/dock Skills/Missions | Typed library; validated OMD store | Implemented; final evidence report records coverage |
| Tools | Typed library → ReviewedToolScreen → full-command review → PRoot | Implemented; final evidence report records coverage |
| Files | File records / selected project file workspace → SAF copy | Implemented; final evidence report records coverage |
| Browser | BrowserScreen/WebView: URL, back, forward, reload/stop, upload, download, site permissions | Implemented; final evidence report records coverage |
| Terminal | OperationalRuntimeScreen → review → PersistentSandboxShell | Host fixed; Android runtime setup blocked |
| Diagnostics | Source/index/runtime probes; retry/rebuild | Implemented; final evidence report records coverage |
| Settings | Persisted animation/haptic preferences; recovery controls | Implemented; final evidence report records coverage |
| Orbit card tap | Stable record selection; persisted active ID | Implemented; final evidence report records coverage |
| Detail tabs | Selected record content and category-specific actions | Implemented; final evidence report records coverage |
| Primary context/project action | Scoped selection / ProjectWorkspace | Explicit conversation permissions; no implicit assistant access |
| Object ellipsis | Open/edit record, context, manage | Implemented; final evidence report records coverage |
| Carousel cards, left/right arrows | Selected object and cyclic paging | Implemented; final evidence report records coverage |
| Hub/status/counts | Read-only decoration and actual store counts, never connectivity claims | Implemented; final evidence report records coverage |
| Empty state create/import | Typed OMD editor / Android system picker | Implemented; final evidence report records coverage |
| Workspace menu/accessibility entry | Device-size actions/navigation | Implemented; final evidence report records coverage |
| Android back, keyboard, scrolling, rotation | BackHandler, insets, saved drafts and selection | Implemented; final evidence report records coverage |

No drag/drop or orbit swipe gesture is advertised by the approved renderer.
Imported procedures are data and cannot authorize execution. Phone storage is
accessed only through a user-selected document URI; import preserves originals.

## Known acceptance limits

No physical phone, ARM inference, thermal/battery measurement or 16KiB-page runtime
test was performed. Software-emulated Android cannot establish smooth physical
scrolling or phone inference speed. Shell/GGUF failures are independent and retained;
no inference pass is inferred from a health endpoint, a model file, or host Ollama.
The final delivery report, not this source checklist, records final test outcomes.
