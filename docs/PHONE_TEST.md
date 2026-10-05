# Test Open Mine 0.2.0-dev

Install the provided **debug APK** on an Android 8+ phone. Existing installs with a different signing key may refuse an update; do not uninstall without backing up needed data.

1. **AI Models → Set up Linux shell**. Stay in the app until complete. Network access and free storage are required.
2. **Terminal**: type `uname -a`, review and run. Check actual output/exit status. Test back/cancel.
3. **AI Models → Set up GGUF engine**. ARM64 normally uses the existing prebuilt; fallback compilation can take 10–30 minutes.
4. **Import GGUF weights**: select a small quantized GGUF that fits your phone RAM. **Load and start model**; continue only after its health check passes.
5. **Knowledge**: create a uniquely titled record with a summary, source and distinctive content fact. Optional blanks become `NONE`. Search its keyword. Try malformed/duplicate `.omd` imports and confirm rejection preserves knowledge.
6. **AI Chat → Use on-device model**: URL `http://127.0.0.1:8080/v1`, model `local`, blank key. Ask about the distinctive fact; check answer and sources. Ask “Use linux_system_info to tell me the Linux kernel.” Expect actual tool output or explicit failure.
7. Navigate repeatedly, rotate, close/reopen and confirm knowledge persists. Restart the model server if the process was terminated. **Stop model** when finished.

Report phone model/Android version, GGUF filename, failing step and error/output. Runtime/device behavior is unverified. A built APK or imported file header does not establish successful inference.
