package com.openmine.sandbox

import java.io.File

/** Disk receipts describe completed installation, never current execution health. */
data class RuntimeInstallation(val shellInstalled: Boolean, val engineInstalled: Boolean) {
    companion object {
        fun inspect(base: File): RuntimeInstallation = RuntimeInstallation(
            shellInstalled = File(base, "ready").isFile &&
                File(base, "rootfs/bin/bash").isFile &&
                File(base, "rootfs/bin/busybox").isFile,
            engineInstalled = File(base, "engine-ready").isFile &&
                File(base, "home/.morsvitaest/llm/bin/llama-server").let { it.isFile && it.length() > 0 },
        )
    }
}
