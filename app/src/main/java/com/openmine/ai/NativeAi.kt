package com.openmine.ai

/** JNI surface of libopenmine_ai.so. All calls are blocking; callers run them off the main thread. */
object NativeAi {
    /** Null when the library could not be loaded on this device; the message explains why. */
    val loadError: String? = try { System.loadLibrary("openmine_ai"); null }
        catch (e: Throwable) { "${e.javaClass.simpleName}: ${e.message}" }
    val available get() = loadError == null

    /** Runs llama.cpp's server until [serverStop]; returns its exit code (-100 when already running). */
    @JvmStatic external fun serverRun(args: Array<String>): Int
    @JvmStatic external fun serverStop()
    @JvmStatic external fun serverRunning(): Boolean


    /** Helper slot: a small GGUF loaded directly for short background jobs (tagging). */
    @JvmStatic external fun helperLoad(path: String, threads: Int, context: Int): String?
    @JvmStatic external fun helperUnload()
    @JvmStatic external fun helperLoaded(): Boolean
    /** UTF-8 in and out (JNI's modified UTF-8 mangles emoji). Returns the reply, or "ERROR: ...". */
    @JvmStatic external fun helperComplete(system: ByteArray, user: ByteArray, maxTokens: Int): ByteArray
}
