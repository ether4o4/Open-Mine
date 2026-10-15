package com.openmine.sandbox

/** Present the actual process result without exposing Kotlin's map serialization. */
object TerminalResultFormatter {
    fun format(result: Map<String, Any>): String = buildString {
        val stdout = result["stdout"]?.toString().orEmpty()
        val stderr = result["stderr"]?.toString().orEmpty()
        if (stdout.isNotEmpty()) {
            append(stdout)
            if (!stdout.endsWith('\n')) append('\n')
        }
        if (stderr.isNotEmpty()) {
            append("\nStandard error\n").append(stderr)
            if (!stderr.endsWith('\n')) append('\n')
        }
        val outcome = when {
            result["timed_out"] == true -> "Time limit reached"
            result["cancelled"] == true -> "Cancelled"
            result["success"] == true -> "Completed"
            else -> "Failed"
        }
        append('\n').append(outcome)
        result["exit_code"]?.let { append(" · exit ").append(it) }
        result["cwd"]?.toString()?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        if (result["shell_died"] == true) append("\nShell session ended; the next command starts a new session.")
        append('\n')
    }
}
