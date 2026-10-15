package com.openmine

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openmine.sandbox.OpenMineRuntime
import com.openmine.sandbox.RuntimeBridgeSettings
import com.openmine.sandbox.RuntimeService

@Composable fun RuntimeControls(context: Context) {
    val settings = remember { RuntimeBridgeSettings(context) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var message by remember { mutableStateOf("") }
    val running by RuntimeService.running.collectAsState()
    val failure by RuntimeService.failure.collectAsState()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        OpenMineRuntime.get(context).verifyShell()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (running) "Runtime service is running" else "Runtime service is stopped", style = MaterialTheme.typography.titleMedium)
        Text("The runtime can continue when you leave Open Mine. Stop ends its shell, model server and managed processes. Android can still stop the app.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else OpenMineRuntime.get(context).verifyShell()
            }, enabled = !running) { Text("Start runtime") }
            OutlinedButton({ RuntimeService.stop(context) }, enabled = running) { Text("Stop runtime") }
        }
        Row {
            Text("Enable Termux control", Modifier.weight(1f))
            Switch(enabled, { value ->
                settings.setEnabled(value); enabled = value
                RuntimeService.reconfigure(context)
                message = if (value) "Copy the token and enter it with openmine configure in Termux." else "Termux control disabled."
            })
        }
        if (enabled) {
            Text("Apps with this token can run Linux commands and control the runtime. The bridge listens only on this device. Configure it once before using openmine start, shell, status or stop.", style = MaterialTheme.typography.bodySmall)
            TextButton({
                val clip = ClipData.newPlainText("Open Mine control token", settings.token())
                if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                message = "Control token copied."
            }) { Text("Copy control token") }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
