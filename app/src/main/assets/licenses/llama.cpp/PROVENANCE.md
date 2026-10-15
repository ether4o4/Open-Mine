# Bundled ARM64 CPU engine

Asset: `app/src/main/assets/sandbox/llama-server-aarch64-musl`

- Source: https://github.com/ggml-org/llama.cpp/commit/74ade52741203e5c8f81eaf06a96cb1cfe15f2a3
- Binary: https://github.com/ether4o4/NeverSoft-Services-OS/releases/download/llama-server-prebuilt-latest/llama-server-aarch64-musl
- Release source record: `llama-cpp-commit.txt`, resolved `74ade52` to the full source revision above.
- SHA-256: `f2454a01c217b9f10ee82550b136885f46ae85152c8be8d21617c8f77f6929de`
- Size: 9,380,464 bytes. ELF64 AArch64, musl loader, 64 KiB load alignment.
- Dynamic dependencies: musl, libstdc++.so.6, libgcc_s.so.1. Alpine installs these separately.

The release tag is mutable; the APK contains the exact digest above, checked during
installation. A changed download cannot silently replace this engine. The app
preserves a working existing engine. It runs the binary inside its app-private
Alpine/PRoot runtime, not as an external service. Inspecting the ELF and checksum
does not prove execution on an Android phone.

llama.cpp, cpp-httplib and nlohmann/json notices accompany this file. No model weights
are bundled. Existing PRoot/talloc notices remain applicable.
