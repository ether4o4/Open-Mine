package com.openmine.ai

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openmine.sandbox.OpenMineRuntime

private val GText = Color(0xFFEAF5FF)
private val GMuted = Color(0xFF91A9C8)
private val GCyan = Color(0xFF38D8FF)
private val GViolet = Color(0xFF9F7AFF)
private val GGreen = Color(0xFF59E3A7)
private val GAmber = Color(0xFFFFC857)
private val GPanel = Color(0xFF071224)

@Composable fun GuideScreen(onBack: () -> Unit, onOpenChat: () -> Unit) {
    val context = LocalContext.current
    val agent = remember { GuideAgent.get(context) }
    val state by agent.state.collectAsState()
    val runtime = remember { OpenMineRuntime.get(context) }
    val shellHealthy by runtime.shellHealthy.collectAsState()
    val shellInstalled by runtime.shellInstalled.collectAsState()
    var input by remember { mutableStateOf("") }
    var chooseAutonomy by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { agent.folderPicked(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { agent.modelFilePicked(it) }
    LaunchedEffect(state.uiRequest) {
        when (state.uiRequest) {
            GuideUiRequest.PickFolder -> folderPicker.launch(null)
            GuideUiRequest.PickModelFile -> filePicker.launch(arrayOf("application/octet-stream", "*/*"))
            null -> {}
        }
    }
    LaunchedEffect(state.lfmInstalled) { if (state.lfmInstalled) agent.startLfm() }
    BackHandler { if (state.pending != null) agent.decline() else onBack() }
    val list = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.pending) { if (state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.size + 1) }

    if (chooseAutonomy) AlertDialog(onDismissRequest = { chooseAutonomy = false },
        title = { Text("How should I act from now on?") },
        text = { Text("Ask every time: I show each command, download or change and wait for you.\n\nFull autonomy: I run commands and downloads myself and report results. Destructive commands (like deleting everything) and saving memories still ask. Folder access always uses Android's own picker.\n\nYou can change this anytime.") },
        confirmButton = { TextButton({ chooseAutonomy = false; agent.finishTutorial(AutonomyMode.FULL_AUTONOMY) }, Modifier.testTag("autonomy-full")) { Text("Full autonomy") } },
        dismissButton = { TextButton({ chooseAutonomy = false; agent.finishTutorial(AutonomyMode.ASK_EVERY_TIME) }, Modifier.testTag("autonomy-ask")) { Text("Ask every time") } })

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("Back") }
            Spacer(Modifier.weight(1f))
            TextButton({ agent.clearConversation() }, enabled = !state.busy) { Text("New conversation") }
        }
        LazyColumn(Modifier.weight(1f).testTag("guide-list"), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("OPEN MINE GUIDE", color = GText, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(6.dp))
                ModelStatus(state, agent)
                Spacer(Modifier.height(8.dp))
                if (!state.tutorialComplete) Steps(state, shellInstalled && shellHealthy, agent) { chooseAutonomy = true }
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Approvals: ${if (state.autonomy == AutonomyMode.FULL_AUTONOMY) "full autonomy" else "ask every time"}", color = GMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton({ agent.setAutonomy(if (state.autonomy == AutonomyMode.FULL_AUTONOMY) AutonomyMode.ASK_EVERY_TIME else AutonomyMode.FULL_AUTONOMY) }) {
                        Text(if (state.autonomy == AutonomyMode.FULL_AUTONOMY) "Ask every time" else "Full autonomy") }
                }
                if (state.lfmServer == LocalLlmServer.State.READY) TextButton(onOpenChat) { Text("Use LFM3 in the main chat") }
            }
            items(state.messages, key = { it.id }) { MessageRow(it) }
            item {
                state.pending?.let { action -> ApprovalCard(action, onApprove = agent::approve, onDecline = agent::decline) }
                if (state.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(state.status.ifBlank { "Working…" }, color = GCyan, fontSize = 13.sp, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(agent::stop) { Text("Stop") }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f).testTag("guide-input"), enabled = !state.busy && state.pending == null,
                placeholder = { Text(if (state.lfmServer == LocalLlmServer.State.READY) "Ask anything…" else "Tell me what to set up…") }, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { agent.send(input); input = "" }))
            Spacer(Modifier.width(8.dp))
            Button({ agent.send(input); input = "" }, enabled = input.isNotBlank() && !state.busy && state.pending == null, modifier = Modifier.testTag("guide-send")) { Text("Send") }
        }
    }
}

@Composable private fun ModelStatus(state: GuideState, agent: GuideAgent) {
    Surface(color = GPanel, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val lfmColor = when (state.lfmServer) { LocalLlmServer.State.READY -> GGreen; LocalLlmServer.State.FAILED -> GAmber; else -> GMuted }
            Text("${if (state.lfmServer == LocalLlmServer.State.READY) "●" else "○"} LFM3 1.7B Uncensored · conversation", color = lfmColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            state.downloadProgress?.let { p ->
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${(p * BuiltInModels.LFM3.sizeBytes / (1024 * 1024)).toLong()} / ${BuiltInModels.LFM3.sizeBytes / (1024 * 1024)} MiB", color = GMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(agent::cancelDownload) { Text("Pause") }
                }
            }
            if (state.lfmNote.isNotBlank()) Text(state.lfmNote, color = GMuted, fontSize = 12.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            when {
                state.downloadProgress != null -> {}
                !state.lfmInstalled -> OutlinedButton({ agent.quick(GuideTool.download_chat_model) }, enabled = !state.busy, modifier = Modifier.testTag("guide-download-lfm")) {
                    Text("Download LFM3 (${BuiltInModels.LFM3.sizeBytes / (1024 * 1024)} MiB, Wi-Fi recommended)") }
                state.lfmServer == LocalLlmServer.State.READY -> TextButton(agent::stopLfm) { Text("Unload LFM3 to free memory") }
                state.lfmServer != LocalLlmServer.State.STARTING -> OutlinedButton(agent::startLfm) { Text("Start LFM3") }
            }
        }
    }
}

@Composable private fun Steps(state: GuideState, shellReady: Boolean, agent: GuideAgent, onFinish: () -> Unit) {
    Surface(color = GPanel, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().testTag("guide-steps")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("SETUP STEPS", color = GViolet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Step("1. Offline chat model", state.lfmInstalled, "Download") { agent.quick(GuideTool.download_chat_model) }
            Step("2. Linux terminal", shellReady, "Install") { agent.quick(GuideTool.install_runtime) }
            Step("3. Storage folder (optional)", state.grantedFolders.isNotEmpty(), "Choose") { agent.quick(GuideTool.request_folder_access, mapOf("folder" to "Documents", "purpose" to "projects")) }
            Step("4. Check everything", false, "Check") { agent.quick(GuideTool.run_diagnostics) }
            Spacer(Modifier.height(4.dp))
            Button(onFinish, modifier = Modifier.fillMaxWidth().testTag("guide-finish")) { Text("Finish tutorial: choose how I act") }
        }
    }
}

@Composable private fun Step(label: String, done: Boolean, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${if (done) "✓" else "·"} $label", color = if (done) GGreen else GText, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (!done) TextButton(onClick) { Text(action) }
    }
}

@Composable private fun MessageRow(m: GuideMessage) {
    val (label, color) = when (m.role) { "user" -> "You" to GCyan; "assistant" -> "Guide" to GViolet; "result" -> "Result" to GGreen; else -> "Open Mine" to GMuted }
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        SelectionContainer { Text(m.text, color = GText, fontSize = 14.sp, fontFamily = if (m.role == "result") FontFamily.Monospace else FontFamily.Default) }
    }
}

@Composable private fun ApprovalCard(action: GuideAction, onApprove: () -> Unit, onDecline: () -> Unit) {
    Surface(color = Color(0xFF14203A), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("guide-approval")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Approve this?", color = GAmber, fontWeight = FontWeight.Bold)
            SelectionContainer { Text(action.summary, color = GText, fontSize = 14.sp, fontFamily = if (action.tool == GuideTool.run_command) FontFamily.Monospace else FontFamily.Default) }
            if (action.tool == GuideTool.run_command && ApprovalPolicy.isDestructive(action.command))
                Text("This command can delete or overwrite data. It always asks, even in full autonomy.", color = GAmber, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onApprove, modifier = Modifier.testTag("guide-approve")) { Text(if (action.tool == GuideTool.run_command) "Run" else "Approve") }
                OutlinedButton(onDecline, modifier = Modifier.testTag("guide-decline")) { Text("Decline") }
            }
        }
    }
}
