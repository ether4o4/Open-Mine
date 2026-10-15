package com.openmine

/** Once source bytes commit, retries may persist workspace state but must not repeat the mutation. */
internal class LibraryCommitRecovery {
    var committedMessage: String? = null
        private set
    private var applyWorkspace: () -> Unit = {}
    private var workspaceApplied = false

    fun markCommitted(message: String, updateWorkspace: () -> Unit) {
        check(committedMessage == null) { "This operation already committed" }
        committedMessage = message
        applyWorkspace = updateWorkspace
    }

    suspend fun finish(persistWorkspace: suspend () -> Unit): String {
        val message = checkNotNull(committedMessage) { "No library change committed" }
        if (!workspaceApplied) {
            applyWorkspace()
            workspaceApplied = true
        }
        persistWorkspace()
        return message
    }
}
