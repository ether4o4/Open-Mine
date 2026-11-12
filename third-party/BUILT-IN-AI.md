# Built-in AI components (0.5.0-dev)

| Component | Source (pinned) | License | How it ships |
|---|---|---|---|
| llama.cpp b12318 | https://github.com/ggml-org/llama.cpp commit `7021f7e0993c2bae3860a9b7e6507bead16c90af` (git submodule `app/src/main/cpp/llama.cpp`) | MIT (`assets/licenses/llama.cpp/`) | Compiled into `libopenmine_ai.so` (arm64-v8a, armeabi-v7a, x86_64) with its server running in-process on 127.0.0.1:8181 |
| Cactus runtime 4.1.0 | https://huggingface.co/Cactus-Compute/cactus revision `4e822c9036be2eb0e47ef556df32a818bb1572ef`, `releases/4.1.0/android-{arm64,armv7}/libcactus.a` | Apache-2.0 (`assets/licenses/cactus/LICENSE`) | Statically linked on ARM only; Cactus publishes no Android x86_64 build |
| Needle 3 weights | https://huggingface.co/Cactus-Compute/needle3 revision `c95d25b4efa1e9601e785d92438e4ade0dbddbf8`, `needle3.cact` | Apache-2.0 (`assets/licenses/cactus/NEEDLE3-LICENSE`) | Bundled at `assets/ai/needle3.cact` (stored uncompressed) |
| LFM3 1.7B Uncensored (AtomicChat) | https://huggingface.co/AtomicChat/LFM3-1.7B-Uncensored-GGUF revision `3ad712542eb9de349dbd28f2739b69c925cdec7d`, `LFM3-1.7B-Uncensored-AD-Q4_K_M.gguf` | LFM Open License v1.0 (`assets/licenses/lfm3/LICENSE`); free commercial use only under $10M annual revenue | **Not bundled.** Downloaded on first run, resumable, verified by size and SHA-256 |

SHA-256:

```
ba543bb4305f7648120b97f7be57e230fc5432a6eb7bc416817d1f2f78a1f5fb  app/src/main/cpp/cactus/arm64-v8a/libcactus.a
2b0c1be04a662ba2f0f508c204b5b2dc1a9415947899fc92cdeea5fa6dfeaa4e  app/src/main/cpp/cactus/armeabi-v7a/libcactus.a
9be7eb25b1d89180495d04a0d2c27053f282dac85add50cacc48d73302819d9d  app/src/main/cpp/cactus/include/cactus.h
c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38  app/src/main/assets/ai/needle3.cact
b22df65673b54ed326f18f74826d76f3b65dadc237ffd6306cd52be04544827d  LFM3-1.7B-Uncensored-AD-Q4_K_M.gguf (1,132,208,000 bytes)
```

The Cactus files match the SHA-256 values in the release's `manifest.json`; the LFM3 digest matches
Hugging Face's `X-Linked-ETag` for that revision. The LFM3 build is a third-party safety-ablated
derivative of LiquidAI/LFM3-1.7B; its card reports 0% refusals with MMLU unchanged (41.0%).
