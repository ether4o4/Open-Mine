package com.openmine

/** Bounded, oldest-first local history; leaving recall restores the unfinished draft. */
internal class ShellCommandHistory(saved: List<String> = emptyList()) {
    var entries = saved.filter { it.isNotBlank() && it.toByteArray().size <= 8192 }.takeLast(100)
        private set
    var position = entries.size
        private set
    private var draft = ""

    fun record(command: String) {
        if (entries.lastOrNull() != command) entries = (entries + command).takeLast(100)
        position = entries.size
        draft = ""
    }

    fun previous(current: String): String {
        if (position == 0) return current
        if (position == entries.size) draft = current
        return entries[--position]
    }

    fun next(current: String): String {
        if (position == entries.size) return current
        position++
        return if (position == entries.size) draft else entries[position]
    }
}
