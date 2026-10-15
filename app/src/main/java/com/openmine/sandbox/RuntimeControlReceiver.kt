package com.openmine.sandbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Explicit, authenticated bootstrap only; all shell commands use the authenticated loopback bridge. */
class RuntimeControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action != ACTION_START || intent.component?.className != javaClass.name) return
            if (!RuntimeBridgeSettings(context).authenticates(intent.getStringExtra(EXTRA_TOKEN))) return
            RuntimeService.start(context)
        } catch (error: Exception) {
            // A token is not an Android background-start exemption. The caller must check bridge readiness.
            Log.w("OpenMineRuntime", "Android refused the runtime service start", error)
        }
    }

    companion object {
        const val ACTION_START = "com.openmine.runtime.START"
        const val EXTRA_TOKEN = "token"
    }
}
