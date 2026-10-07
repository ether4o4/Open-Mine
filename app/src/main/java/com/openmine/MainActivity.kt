package com.openmine

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openmine.sandbox.OpenMineRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Background = Color(0xFF020712)
private val Panel = Color(0xFF091528)
private val Cyan = Color(0xFF38D8FF)
data class Orb(val title: String, val sub: String, val accent: Color, val icon: ImageVector, val id: String = title, val record: StrictObject? = null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OpenMine() }
    }
}

/** Routes and selection use stable record IDs; no saved procedure executes on navigation. */
@Composable fun OpenMine() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("open_mine", Context.MODE_PRIVATE) }
    val runtime = remember { OpenMineRuntime.get(context) }
    RuntimeOperationLifecycle(context)
    var screen by remember { mutableIntStateOf(preferences.getInt("screen", 3).coerceIn(0, 12)) }
    var overlay by remember { mutableStateOf(preferences.getString("route", "").orEmpty()) }
    var openedId by remember { mutableStateOf(preferences.getString("opened_record", "").orEmpty()) }
    var active by remember { mutableStateOf(preferences.getString("active_$screen", preferences.getString("active", "")).orEmpty()) }
    var contextIds by remember { mutableStateOf(preferences.getStringSet("context", emptySet())?.toSet().orEmpty()) }
    var animations by remember { mutableStateOf(preferences.getBoolean("animations", true)) }
    var haptics by remember { mutableStateOf(preferences.getBoolean("haptics", true)) }
    var systemMotion by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    var snapshot by remember { mutableStateOf<KnowledgeStoreSnapshot?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    val revision by OpenMineObjectStore.revision.collectAsState()
    val status by runtime.status.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { refresh++; systemMotion = ValueAnimator.areAnimatorsEnabled() }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision, refresh) {
        loading = true
        try {
            val loaded = withContext(Dispatchers.IO) { OpenMineObjectStore.inspect(context) }
            snapshot = loaded; loadError = null
            // Older HUD builds stored titles. Only migrate unambiguous names; retain unresolved entries.
            fun resolve(value: String): String = loaded.records.firstOrNull { it.id == value }?.id
                ?: loaded.records.filter { it.title == value }.singleOrNull()?.id ?: value
            val resolvedContext = contextIds.map(::resolve).toSet()
            val resolvedActive = resolve(active)
            if (resolvedContext != contextIds || resolvedActive != active) {
                contextIds = resolvedContext; active = resolvedActive
                preferences.edit().putStringSet("context", resolvedContext).putString("active", resolvedActive).putString("active_$screen", resolvedActive).apply()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            loadError = e.message ?: "Cannot read the saved workspace."
        } finally { loading = false }
    }
    fun route(value: String, id: String = "") {
        overlay = value; openedId = id
        preferences.edit().putString("route", value).putString("opened_record", id).apply()
    }
    fun navigate(value: Int) {
        screen = value; route("")
        active = preferences.getString("active_$value", "").orEmpty()
        preferences.edit().putInt("screen", value).apply()
        if (haptics) (context as? android.app.Activity)?.window?.decorView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
    fun select(orb: Orb) {
        active = orb.id
        preferences.edit().putString("active", active).putString("active_$screen", active).apply()
    }
    fun toggle(record: StrictObject) {
        val added = record.id !in contextIds
        contextIds = if (added) contextIds + record.id else contextIds - record.id
        preferences.edit().putStringSet("context", contextIds).apply()
        scope.launch { snackbar.showSnackbar("${record.title}: ${if (added) "added to context" else "removed from context"}") }
    }
    fun openRecord(record: StrictObject) {
        route(when (record.type) { "PROJECT" -> "project"; "TOOL" -> "tool"; else -> "record" }, record.id)
    }
    fun manage(category: Int) = route("library:$category")
    BackHandler(overlay.isNotEmpty() || screen != 3) { if (overlay.isNotEmpty()) route("") else navigate(3) }
    val records = snapshot?.records.orEmpty()
    val opened = records.firstOrNull { it.id == openedId }
    MaterialTheme(colorScheme = darkColorScheme(background = Background, surface = Panel, primary = Cyan, secondary = Color(0xFF9F7AFF))) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), color = Background) {
            Box(Modifier.fillMaxSize()) {
                when {
                    overlay == "runtime" -> Column(Modifier.fillMaxSize()) {
                        TextButton({ route("") }) { Text("Back to workspace") }
                        Box(Modifier.weight(1f)) { OperationalRuntimeScreen(context) }
                    }
                    overlay == "project" && opened != null -> ProjectWorkspace(opened) { route("") }
                    overlay == "tool" && opened != null -> ReviewedToolScreen(opened) { route("") }
                    overlay.startsWith("library:") || overlay == "record" -> OperationalLibraryScreen(
                        category = if (overlay == "record") null else recordType(overlay.substringAfter(':').toIntOrNull() ?: 3),
                        initialRecordId = openedId.takeIf(String::isNotBlank), contextIds = contextIds,
                        onToggleContext = ::toggle, onOpenRecord = ::openRecord, onBack = { route("") })
                    overlay in setOf("project", "tool") -> Column(Modifier.padding(20.dp)) {
                        Text(if (loading) "Loading saved workspace…" else "This record is unavailable. Its files have not been deleted.")
                        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        TextButton({ refresh++ }) { Text("Retry") }
                        TextButton({ route("") }) { Text("Back to workspace") }
                    }
                    else -> Column(Modifier.fillMaxSize()) {
                        if (loadError != null) Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(loadError.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                            TextButton({ refresh++ }) { Text("Retry") }
                        } else if (loading && snapshot == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Box(Modifier.weight(1f)) {
                            ReferenceHud(screen, animations && systemMotion, active, contextIds, records, status,
                                onNavigate = ::navigate, onSelect = ::select, onToggleContext = { it.record?.let(::toggle) },
                                onManage = ::manage, onOpenRecord = ::openRecord,
                                onRuntime = { route("runtime") }, onAssistant = { navigate(12) }) { destination ->
                                when (destination) {
                                    8 -> BrowserScreen(onExit = { navigate(3) })
                                    9 -> OperationalRuntimeScreen(context, terminalOnly = true)
                                    10 -> DiagnosticsScreen(context)
                                    11 -> SettingsScreen(animations, { animations = it; preferences.edit().putBoolean("animations", it).apply() },
                                        haptics, { haptics = it; preferences.edit().putBoolean("haptics", it).apply() })
                                    12 -> OperationalAssistantScreen(context)
                                    else -> OperationalLibraryScreen(recordType(destination), contextIds = contextIds,
                                        onToggleContext = ::toggle, onOpenRecord = ::openRecord, onBack = { navigate(3) })
                                }
                            }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

private fun recordType(screen: Int): String? = when (screen) {
    0 -> "MODEL"; 1 -> "PROJECT"; 2 -> "CONNECTOR"; 3 -> null
    4 -> "SKILL"; 5 -> "MISSION"; 6 -> "TOOL"; 7 -> "FILE"; else -> null
}

@Composable private fun DiagnosticsScreen(context: Context) {
    val runtime = remember { OpenMineRuntime.get(context) }
    val healthy by runtime.shellHealthy.collectAsState()
    val model by runtime.modelHealthy.collectAsState()
    val busy by runtime.busy.collectAsState()
    val status by runtime.status.collectAsState()
    val revision by OpenMineObjectStore.revision.collectAsState()
    var snapshot by remember { mutableStateOf<KnowledgeStoreSnapshot?>(null) }
    var error by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision, refresh) {
        try { snapshot = withContext(Dispatchers.IO) { OpenMineObjectStore.inspect(context) }; error = "" }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message.orEmpty() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text("Open Mine ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.APPLICATION_ID}")
        Text("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT} · ${Build.SUPPORTED_ABIS.joinToString()}")
        Text("Shell: ${if (healthy) "execution probe passed" else "not verified"}\nModel: ${if (model) "last HTTP health check passed" else "not verified or stopped"}\n$status")
        Button({ runtime.verifyShell() }, enabled = !busy) { Text("Check shell execution") }
        Button({ runtime.engine("status") }, enabled = !busy && healthy) { Text("Check model HTTP health") }
        snapshot?.let { data ->
            Text("Library: ${data.records.size} validated records · ${data.chunkCount} indexed chunks\nIndex: ${if (data.indexReady) "validated against sources" else "unavailable"}")
            data.issues.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button({ scope.launch {
            refreshing = true
            try { withContext(Dispatchers.IO) { OpenMineObjectStore.rebuildIndex(context) }; refresh++; error = "" }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message.orEmpty() }
            finally { refreshing = false }
        } }, enabled = !refreshing) { Text(if (refreshing) "Rebuilding index…" else "Rebuild knowledge index") }
        TextButton({ refresh++ }) { Text("Refresh diagnostics") }
    }
}

@Composable private fun SettingsScreen(animations: Boolean, setAnimations: (Boolean) -> Unit, haptics: Boolean, setHaptics: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Setting("Animations", "Workspace motion; Android reduced motion takes precedence.", animations, setAnimations, "setting-animations")
        Setting("Haptic feedback", "Touch feedback when switching workspace sections.", haptics, setHaptics, "setting-haptics")
        Text("Browser and model connection settings are saved in their respective workspaces. API keys stay in memory.")
    }
}
@Composable private fun Setting(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(detail, style = MaterialTheme.typography.bodySmall) }
        Switch(value, onChange, Modifier.testTag(tag))
    }
}
