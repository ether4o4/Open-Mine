# Built-in AI components (0.5.0-dev)

| Component | Source (pinned) | License | How it ships |
|---|---|---|---|
| llama.cpp b12318 | https://github.com/ggml-org/llama.cpp commit `7021f7e0993c2bae3860a9b7e6507bead16c90af` (git submodule `app/src/main/cpp/llama.cpp`) | MIT (`assets/licenses/llama.cpp/`) | Compiled into `libopenmine_ai.so` (arm64-v8a, armeabi-v7a, x86_64); its server runs in-process on 127.0.0.1:8181 |
| LFM3 1.7B Uncensored (AtomicChat) | https://huggingface.co/AtomicChat/LFM3-1.7B-Uncensored-GGUF revision `3ad712542eb9de349dbd28f2739b69c925cdec7d`, `LFM3-1.7B-Uncensored-AD-Q4_K_M.gguf` | LFM Open License v1.0 (`assets/licenses/lfm3/LICENSE`); free commercial use only under $10M annual revenue | **Not bundled.** Downloaded on first run, resumable, verified by size and SHA-256 |

```
b22df65673b54ed326f18f74826d76f3b65dadc237ffd6306cd52be04544827d  LFM3-1.7B-Uncensored-AD-Q4_K_M.gguf (1,132,208,000 bytes)
```

The digest matches Hugging Face's `X-Linked-ETag` for that revision. The model is a third-party
safety-ablated derivative of LiquidAI/LFM3-1.7B; its card reports 0% refusals with MMLU unchanged
(41.0%). Cactus/Needle 3 was evaluated and removed (see `docs/BUILT_IN_AI.md`).
