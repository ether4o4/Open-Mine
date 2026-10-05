# Repository reuse review

Authenticated paginated enumeration found 74 owned repositories (47 public, 27 private). Seventy repository trees were inspected; four were empty or inaccessible. This review separates tree triage from source inspection and execution evidence. Private repository identities and unrelated local data are omitted.

## Incorporated source

NeverSoft-Services-OS PR53 at 9786327e246adf745d93dd555ae0c96404aa41da supplies selected PRoot executor, Alpine extraction/configuration, native binaries and morsllm GGUF code. Open Mine-owned glue integrates these independently of the MVE app. See third-party/REUSE.md for adaptations and licenses. Seven JVM tests, script syntax, APK/AAB builds and Android lint were checked locally. No Android runtime execution is claimed.

## Other source reviewed

- Mobile-Container contains actual ARM64/x86_64 libllama prebuilts and a JNI bridge. The bridge uses older llama.cpp APIs and a hardcoded external headers path; matching library/header provenance and ABI compatibility need verification before reuse. It was not incorporated.
- Intelli-Shell command-risk heuristics were inspected. Open Mine instead reviews every manually entered command, so these heuristics were not copied. They should not be treated as a security sandbox.
- The local NeverSoft-Engineering extraction uses React Native llama.rn and an Android sandbox bridge. Its React Native coupling makes direct reuse inappropriate for this Compose checkpoint.

## Public repository tree triage

Candidate paths and test filenames identify follow-up opportunities; their presence does not establish working or tested functionality. Repositories below were tree-inspected unless marked inaccessible. Source-level review is limited to the repositories described above.

| Repository | Files | Candidate paths | Test paths |
|---|---:|---:|---:|
| ether4o4/4GAuteauOS | 211 | 1 | 0 |
| ether4o4/4gauteauOS-manifests | 4 | 0 | 0 |
| ether4o4/agent-config-hub | 182 | 7 | 4 |
| ether4o4/AutoNoBot | 42 | 4 | 0 |
| ether4o4/backtrack | 1 | 0 | 0 |
| ether4o4/BlackBerryFanta | inaccessible | 0 | 0 |
| ether4o4/cClaw | 137 | 38 | 28 |
| ether4o4/closeout | 150 | 6 | 14 |
| ether4o4/Colour_Ceauxdid | 61 | 12 | 10 |
| ether4o4/DataPipeline | 38 | 6 | 1 |
| ether4o4/File-seperate | 53 | 26 | 0 |
| ether4o4/ForTheWin11 | 210 | 1 | 2 |
| ether4o4/Ghost-key-file-explorer | 126 | 7 | 4 |
| ether4o4/GoFindMe | 77 | 8 | 9 |
| ether4o4/GoFindMe_Mobile | 1 | 0 | 0 |
| ether4o4/Hand-commands | 21 | 2 | 0 |
| ether4o4/Holt | 43 | 1 | 6 |
| ether4o4/IAT-Vault | 7 | 1 | 0 |
| ether4o4/Intelli-Shell | 125 | 25 | 20 |
| ether4o4/LLM-research-protocol- | 5 | 0 | 0 |
| ether4o4/mlabonnegem34Bitablit-chasehuihuigem3ne4bablit | 1 | 0 | 0 |
| ether4o4/Mobile-Container | 64 | 8 | 1 |
| ether4o4/NeverSoft-11 | 240 | 4 | 2 |
| ether4o4/NeverSoft-Agent | 12 | 1 | 0 |
| ether4o4/Neversoft-AI-agent-hub | 40 | 1 | 3 |
| ether4o4/NeverSoft-AI-Container | 34 | 1 | 0 |
| ether4o4/Neversoft-Aware | 35 | 1 | 0 |
| ether4o4/NeverSoft-CMD | 44 | 16 | 1 |
| ether4o4/NeverSoft-GPU | 12 | 1 | 3 |
| ether4o4/NeverSoft-Media-Editor | 34 | 1 | 0 |
| ether4o4/NeverSoft-Mobile-Desktop | 71 | 3 | 0 |
| ether4o4/NeverSoft-Services-Full-OS-MVE | 1 | 0 | 0 |
| ether4o4/NeverSoft-Services-OS | 1935 | 100 | 30 |
| ether4o4/NeverSoft-Services-OS-PR | 1 | 0 | 0 |
| ether4o4/Open-Mine | 12 | 0 | 0 |
| ether4o4/PixAvatar | 13 | 0 | 0 |
| ether4o4/POSH | 1824 | 100 | 30 |
| ether4o4/POSH-release | inaccessible | 0 | 0 |
| ether4o4/Retro-Space | 12 | 0 | 0 |
| ether4o4/social-hub- | 761 | 0 | 0 |
| ether4o4/Spotlight | 89 | 2 | 10 |
| ether4o4/Swab-Link | 91 | 2 | 2 |
| ether4o4/synapse-swarm | 18 | 1 | 0 |
| ether4o4/synapse-swarm-mobile | 32 | 0 | 0 |
| ether4o4/Telegram-bot-setup | 21 | 0 | 2 |
| ether4o4/windows-to-android | 1 | 0 | 0 |
| ether4o4/windowslauncher | 4860 | 38 | 2 |
