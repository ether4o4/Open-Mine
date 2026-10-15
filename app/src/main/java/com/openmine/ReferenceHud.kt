package com.openmine

import android.content.Context
import android.graphics.BitmapFactory
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

private val Ink = Color(0xFF040C17)
private val Glass = Color(0xDA071321)
private val Edge = Color(0xFF293D53)
private val Ice = Color(0xFF76E7FF)
private val White = Color(0xFFF0F6FB)
private val Muted = Color(0xFF9BACBF)
private val Ready = Color(0xFF78DBB8)
private val Violet = Color(0xFFAC7CF6)
private val HudFont = FontFamily(Font(R.font.ubuntu_regular), Font(R.font.ubuntu_medium, FontWeight.Medium), Font(R.font.ubuntu_bold, FontWeight.Bold))
private val ArtworkAlpha = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(1f,0f,0f,0f,0f, 0f,1f,0f,0f,0f, 0f,0f,1f,0f,0f, .75f,.75f,.75f,0f,0f)))
private val HudNavIcons = listOf(Icons.Outlined.Memory, Icons.Outlined.FolderOpen, Icons.Outlined.Hub,
    Icons.Outlined.LibraryBooks, Icons.Outlined.HourglassEmpty, Icons.Outlined.TaskAlt,
    Icons.Outlined.Construction, Icons.Outlined.Folder, Icons.Outlined.Language,
    Icons.Outlined.Terminal, Icons.Outlined.MonitorHeart, Icons.Outlined.Settings, Icons.Outlined.Chat)
private val HudNavNames = listOf("AI MODELS", "PROJECTS", "CONNECTORS", "KNOWLEDGE", "SKILLS", "MISSIONS",
    "TOOLS", "FILES", "BROWSER", "TERMINAL", "DIAGNOSTICS", "SETTINGS", "AI CHAT")

private data class HudItem(val record: StrictObject, val content: HudRecordContent, val objectRef: Orb, val category: Int) {
    val description get() = content.summary
    val tabs get() = content.tabs.keys.toList()
    val tags get() = content.tags
    val metrics get() = content.metrics
    val art get() = when(record.title) {
        "Gemma 4 2B"->1;"Llama 3.2 3B"->2;"Hermes 3 3B"->5;"RefinedToolCall"->8;"Suno"->7;"Claude 3.5"->6;"GPT-4o"->4;"Mixtral 8x7B"->3
        "Google Drive"->10;"Dropbox"->11;"Browser","OSINT Database"->13;"Telegram"->14;"Notion","Notion Key"->12
        else->when(category){0->0;1->3;2->9;else->15}
    }
    val hubArt get() = -1
}
private fun hudItem(record: StrictObject, category: Int) = HudItem(record, hudRecordContent(record),
    Orb(record.title, hudRecordContent(record).subtitle, Violet, HudNavIcons[category], record.id, record), category)

/** A data-only adapter: opening the HUD never installs, connects, or runs a saved procedure. */
@Composable
internal fun ReferenceHud(
    screen: Int, animations: Boolean, active: String, contextItems: Set<String>,
    records: List<StrictObject>, runtimeStatus: String,
    onNavigate: (Int) -> Unit, onSelect: (Orb) -> Unit, onToggleContext: (Orb) -> Unit,
    onManage: (Int) -> Unit, onOpenRecord: (StrictObject) -> Unit,
    onRuntime: () -> Unit, onAssistant: () -> Unit,
    utility: @Composable (Int) -> Unit,
) {
    val category = screen.coerceIn(0, 3)
    val items = remember(records, category) { records.filter { it.type == HudRecordTypes[category] }.map { hudItem(it, category) } }
    val current = items.firstOrNull { it.record.id == active } ?: items.firstOrNull()
    var navigation by rememberSaveable { mutableStateOf(false) }
    var carouselStart by rememberSaveable(screen) { mutableIntStateOf(0) }
    var browse by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable(screen, current?.record?.id) { mutableStateOf("OVERVIEW") }
    val config = LocalConfiguration.current
    val accessible = rememberTouchExplorationEnabled()
    val adaptive = accessible || config.fontScale > 1.2f || config.screenWidthDp < 320 || config.screenWidthDp > config.screenHeightDp
    val actualContext = remember(records, contextItems) { contextItems.intersect(records.map { it.id }.toSet()) }
    val pulse = if (animations && !accessible) {
        rememberInfiniteTransition(label = "hub-glow").animateFloat(.88f, 1f,
            infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "glow").value
    } else 1f

    Box(Modifier.fillMaxSize().background(Ink).testTag("hud-root")) {
        if (adaptive) {
            HudImage("background.png", Modifier.fillMaxSize(), ContentScale.Crop)
            Column {
                HudNativeToolbar(HudNavNames[screen]) { navigation = true }
                if (screen !in 0..3) Box(Modifier.weight(1f).fillMaxWidth()) { utility(screen) }
                else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("accessible-workspace"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { HudText("${items.size} saved records · ${actualContext.size} in context", 14, color = Muted) }
                    item { HudText(runtimeStatus, 14, color = Ice) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ onManage(screen) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Manage records") }
                        if (screen == 0) Button(onRuntime, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Model runtime") }
                    } }
                    if (current == null) item { HudEmptyState(category, onManage, onRuntime, Modifier.fillMaxWidth()) }
                    else {
                        item { HudAccessibleDetailPanel(current, tab, { tab = it }, actualContext, onToggleContext, onOpenRecord, onManage, Modifier.fillMaxWidth(), true) }
                        items(items, key = { it.record.id }) { entry ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(9.dp)).background(Glass)
                                .clickable(role = Role.Button) { onSelect(entry.objectRef) }.semantics { selected = current.record.id == entry.record.id }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                HudItemArt(entry, Modifier.size(40.dp)); Spacer(Modifier.width(12.dp))
                                Column { HudText(entry.record.title, 16); HudText(entry.content.subtitle, 13, color = Muted) }
                            }
                        }
                    }
                }
            }
        } else {
            HudArtboard {
                HudImage("background.png", Modifier.offset((-22).dp, 0.dp).fillMaxSize().graphicsLayer { alpha = .78f }, ContentScale.FillBounds)
                HudHeader()
                HudRail(screen, onNavigate)
                HudLocation()
                HudStats(records, actualContext.size)
                Box(Modifier.offset(315.dp, 413.dp).size(195.dp, 190.dp)) {
                    HudText(HudNavNames[category], 14, weight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = if (category == 0) 0.dp else 30.dp))
                    HudHubArt(category, Modifier.size(if (category == 0) 151.dp else 127.dp)
                        .align(Alignment.TopCenter).offset(y = if (category == 0) 17.dp else 35.dp).graphicsLayer { alpha = pulse })
                    if (category != 0) HudText("${items.size} SAVED RECORDS", 12,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = 148.dp))
                }
                if (screen !in 0..3) {
                    Box(Modifier.offset(207.dp, 265.dp).size(466.dp, 909.dp).clip(RoundedCornerShape(16.dp))
                        .background(Glass).border(1.dp, Edge, RoundedCornerShape(16.dp))) { utility(screen) }
                } else if (current == null) {
                    HudEmptyState(category, onManage, onRuntime, Modifier.offset(219.dp, 603.dp).width(444.dp))
                    Column(Modifier.offset(28.dp, 903.dp).size(636.dp, 228.dp).clip(RoundedCornerShape(18.dp)).background(Glass)
                        .border(1.dp, Ice.copy(alpha = .5f), RoundedCornerShape(18.dp)).padding(26.dp)) {
                        HudText("YOUR WORKSPACE", 24, weight = FontWeight.Bold)
                        HudText("Create or import your own records. Saved model and connector records describe configuration; their presence does not establish a running model or a connection.", 17, modifier = Modifier.padding(top = 16.dp), color = Muted, lineHeight = 24)
                        TextButton(onAssistant, Modifier.heightIn(min = 48.dp)) { HudText("OPEN AI CHAT  →", 15, color = Ice) }
                    }
                } else {
                    val selectedIndex = items.indexOf(current)
                    val pageSize = if (category < 2) 9 else 8
                    val start = (selectedIndex / pageSize) * pageSize
                    HudText("%02d".format(selectedIndex + 1), 28, color = Muted, modifier = Modifier.offset(318.dp, if(category==0)440.dp else 452.dp))
                    items.drop(start).take(pageSize).forEachIndexed { index, entry ->
                        val pos = positions(if (category < 2) 9 else 8)[index]
                        HudOrbitCard(entry, start + index, current.record.id == entry.record.id,
                            Modifier.offset(pos.first.dp, pos.second.dp).graphicsLayer { rotationZ = pos.third }) { onSelect(entry.objectRef) }
                    }
                    HudDetailPanel(category, current, tab, { tab = it }, actualContext, onToggleContext,
                        { onOpenRecord(current.record) }, Modifier.offset(28.dp, 765.dp))
                    HudCarousel(items, carouselStart.coerceAtMost(items.lastIndex), { carouselStart = (carouselStart + it + items.size) % items.size }, onSelect, Modifier.offset(40.dp, 1098.dp))
                }
                HudDock(screen, onNavigate)
            }
            // The portrait reference scales its tiny rail. This native 48dp entry exposes every
            // action at device size, including record selection and context controls.
            IconButton({ navigation = true }, Modifier.align(Alignment.TopEnd).size(48.dp).testTag("workspace-actions")) {
                Icon(Icons.Outlined.MoreVert, "Workspace actions and navigation", tint = Ice)
            }
        }
    }
    if (navigation) AlertDialog(onDismissRequest = { navigation = false }, title = { Text("Open Mine") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(runtimeStatus, color = Ice, modifier = Modifier.padding(bottom = 8.dp))
            if (screen in 0..3) {
                HudMenuButton("Browse saved records", Icons.Outlined.ViewList) { navigation = false; browse = true }
                HudMenuButton("Create, import or manage records", Icons.Outlined.LibraryAdd) { navigation = false; onManage(screen) }
                current?.let { entry ->
                    HudMenuButton("Open ${entry.record.title}", Icons.Outlined.Description) { navigation = false; onOpenRecord(entry.record) }
                    HudMenuButton(if (entry.record.id in actualContext) "Remove selected record from context" else "Add selected record to context", Icons.Outlined.AddCircleOutline) { navigation = false; onToggleContext(entry.objectRef) }
                }
                HorizontalDivider()
            }
            HudMenuButton("Model runtime", Icons.Outlined.Memory) { navigation = false; onRuntime() }
            HudMenuButton("AI chat", Icons.Outlined.Chat) { navigation = false; onAssistant() }
            HudNavNames.forEachIndexed { index, name ->
                if (index != 12) HudMenuButton(name, HudNavIcons[index]) { navigation = false; onNavigate(index) }
            }
        } }, confirmButton = { TextButton({ navigation = false }) { Text("Close") } })
    if (browse) AlertDialog(onDismissRequest = { browse = false }, title = { Text("Saved ${HudNavNames[category].lowercase()}") },
        text = { LazyColumn { if (items.isEmpty()) item { Text("No records saved yet. Use Manage records to create or import one.") }
            items(items, key = { it.record.id }) { entry ->
                HudMenuButton(entry.record.title, entry.objectRef.icon) { onSelect(entry.objectRef); browse = false; onOpenRecord(entry.record) }
            }
        } }, confirmButton = { TextButton({ browse = false; onManage(screen) }) { Text("Manage records") } },
        dismissButton = { TextButton({ browse = false }) { Text("Close") } })
}

private fun positions(count: Int): List<Triple<Int, Int, Float>> = when (count) {
    9 -> listOf(Triple(243, 213, -16f), Triple(421, 237, 14f), Triple(539, 323, 29f),
        Triple(544, 477, 12f), Triple(488, 596, 31f), Triple(343, 641, -1f),
        Triple(209, 593, 30f), Triple(163, 478, -10f), Triple(163, 342, 17f))
    8 -> listOf(Triple(242, 218, -16f), Triple(423, 242, 14f), Triple(539, 332, 28f),
        Triple(545, 491, 12f), Triple(488, 621, 33f), Triple(306, 639, 24f),
        Triple(165, 511, -11f), Triple(165, 376, 12f))
    else -> listOf(Triple(242, 218, -16f), Triple(423, 242, 14f), Triple(539, 332, 28f),
        Triple(545, 491, 12f), Triple(488, 621, 33f), Triple(306, 639, 24f), Triple(165, 376, 12f))
}


private val HudPositions = listOf(Triple(243, 213, -16f), Triple(421, 237, 14f), Triple(539, 323, 29f),
    Triple(544, 477, 12f), Triple(488, 596, 31f), Triple(343, 641, -1f),
    Triple(209, 593, 30f), Triple(163, 478, -10f), Triple(163, 342, 17f))

@Composable private fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

@Composable private fun HudMenuButton(label: String, icon: ImageVector, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = action).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)); Text(label)
    }
}

@Composable private fun HudNativeToolbar(title: String, onNavigation: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Glass), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onNavigation, Modifier.size(48.dp).testTag("workspace-actions")) { Icon(Icons.Outlined.Menu, "Workspace actions and navigation", tint = Ice) }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) { HudText("Open Mine", 19, weight = FontWeight.Bold); HudText(title, 12, color = Muted) }
    }
}

@Composable private fun HudArtboard(content: @Composable BoxScope.() -> Unit) {
    Layout(content = { Box(Modifier.requiredSize(700.dp, 1280.dp).testTag("hud-artboard"), content = content) }) { measurables, constraints ->
        val w = 700.dp.roundToPx(); val h = 1280.dp.roundToPx()
        val scale = min(constraints.maxWidth.toFloat() / w, constraints.maxHeight.toFloat() / h)
        val child = measurables.single().measure(Constraints.fixed(w, h))
        layout(constraints.maxWidth, constraints.maxHeight) {
            child.placeWithLayer(((constraints.maxWidth - w * scale) / 2).toInt(), ((constraints.maxHeight - h * scale) / 2).toInt()) {
                scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

@Composable
private fun HudHeader() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = Date() } }
    Row(Modifier.offset(22.dp, 17.dp).size(650.dp, 56.dp), verticalAlignment = Alignment.CenterVertically) {
        HudCrop("reference-models.jpg", 20, 18, 43, 44, Modifier.size(44.dp))
        Spacer(Modifier.width(17.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudText("Open Mine", 27, weight = FontWeight.Bold)
                HudText("  ${BuildConfig.VERSION_NAME}", 10, color = Muted, modifier = Modifier.padding(top = 7.dp))
            }
            HudText("AI WORKSPACE ENVIRONMENT", 11, color = Muted, spacing = 1.7f)
        }
        Column(Modifier.width(122.dp)) {
            HudText(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now), 17, weight = FontWeight.Bold)
            HudText(SimpleDateFormat("EEE M/d/yyyy", Locale.getDefault()).format(now), 13)
        }
        Icon(Icons.Outlined.NightlightRound, "Local workspace", tint = Ice, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(14.dp))
        Column { HudText("LOCAL", 16, weight = FontWeight.Bold); HudText("Storage", 12, color = Muted) }
        Spacer(Modifier.width(14.dp))
        Icon(Icons.Outlined.Equalizer, null, tint = Muted, modifier = Modifier.size(42.dp))
    }
}

@Composable
private fun HudRail(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.offset(18.dp, 101.dp).width(174.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HudNavNames.take(12).forEachIndexed { index, name ->
            val active = index == selected
            Row(Modifier.fillMaxWidth().height(42.dp)
                .graphicsLayer { if (active) { shadowElevation = 14.dp.toPx(); ambientShadowColor = Ice; spotShadowColor = Ice } }
                .clip(RoundedCornerShape(5.dp)).background(if (active) Color(0xCC10364A) else Color(0x6B061320))
                .border(if (active) 1.5.dp else .6.dp, if (active) Ice.copy(alpha = .8f) else Edge.copy(alpha = .4f), RoundedCornerShape(5.dp))
                .clickable { onSelect(index) }.testTag("nav-$index").padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(HudNavIcons[index], null, tint = if (active) Ice else Muted, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(17.dp))
                HudText(name, 11, color = if (active) White else Muted, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                HudText("%02d".format(index + 1), 10, color = Muted)
            }
        }
    }
}

@Composable private fun HudLocation() {
    Row(Modifier.offset(226.dp, 110.dp)) {
        Icon(Icons.Outlined.LocationOn, null, tint = Ice, modifier = Modifier.size(29.dp)); Spacer(Modifier.width(27.dp))
        Column { HudText("LOCATION", 10, color = Muted); HudText("Local workspace", 11, weight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp)); HudText("ON DEVICE · PRIVATE STORAGE", 8, color = Muted, modifier = Modifier.padding(top = 5.dp)) }
    }
}

@Composable private fun HudStats(records: List<StrictObject>, contextCount: Int) {
    val metrics = listOf("MODELS" to records.count { it.type == "MODEL" }, "PROJECTS" to records.count { it.type == "PROJECT" }, "RECORDS" to records.size, "IN CONTEXT" to contextCount)
    Column(Modifier.offset(521.dp, 89.dp).size(151.dp, 152.dp).clip(RoundedCornerShape(8.dp)).background(Glass.copy(alpha = .65f))
        .border(.7.dp, Edge.copy(alpha = .6f), RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
        metrics.forEachIndexed { index, metric -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HudNavIcons[index], null, tint = Muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(9.dp))
            HudText(metric.first, 8, color = Muted, modifier = Modifier.weight(1f)); HudText(metric.second.toString(), 11, weight = FontWeight.Bold)
        } }
    }
}

@Composable private fun HudEmptyState(category: Int, onManage: (Int) -> Unit, onRuntime: () -> Unit, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Glass).border(1.dp, Edge, RoundedCornerShape(14.dp)).padding(20.dp)) {
        HudText("No ${HudRecordTypes[category].lowercase()} records yet", 23, weight = FontWeight.Bold)
        HudText("Create a labeled record or import a validated .omd file.", 16, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
        Button({ onManage(category) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("empty-manage")) { Text("CREATE / IMPORT RECORDS") }
        if (category == 0) TextButton(onRuntime, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("OPEN GGUF MODEL RUNTIME") }
    }
}

@Composable
private fun HudOrbitCard(entry: HudItem, index: Int, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    Box(modifier.size(143.dp, 128.dp)
        .graphicsLayer { shadowElevation = if (selected) 28.dp.toPx() else 8.dp.toPx(); ambientShadowColor = if (selected) Ice else Color.Black; spotShadowColor = if (selected) Ice else Color.Black }
        .clip(RoundedCornerShape(9.dp)).background(if (selected) Color(0xF00D1634) else Color(0xD907101F))
        .border(if (selected) 2.dp else 1.4.dp, if (selected) Ice else Color(0xFF7D8794).copy(alpha = .62f), RoundedCornerShape(9.dp))
        .clickable(onClick = onSelect).testTag("orbit-${entry.record.id}")) {
      HudCrop("card-frame.png", 13, 66, 1298, 1050, Modifier.fillMaxSize(), ContentScale.FillBounds)
      Column(Modifier.fillMaxSize().padding(start = 5.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Muted, modifier = Modifier.size(9.dp))
            HudText("%02d".format(index + 1), 9, color = Muted)
        }
        HudItemArt(entry, Modifier.size(62.dp).align(Alignment.CenterHorizontally))
        HudText(entry.objectRef.title, 13, weight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp), maxLines = 1)
        HudText(shortSub(entry), 10, color = Muted, modifier = Modifier.padding(start = 10.dp, top = 3.dp), maxLines = 1)
      }
    }
}

private fun shortSub(entry: HudItem) = when (entry.objectRef.title) {
    "Qwen 3.5 2B" -> "LOCAL · GGUF"
    "Open Mine" -> "AI WORKSPACE"
    "GitHub MCP" -> "DEV TOOLS"
    "Engineering Vault" -> "SPECS & DOCS"
    else -> entry.objectRef.sub.uppercase()
}

@Composable private fun HudAccessibleDetailPanel(entry: HudItem, tab: String, onTab: (String) -> Unit, contextItems: Set<String>,
    onToggleContext: (Orb) -> Unit, onOpenRecord: (StrictObject) -> Unit, onManage: (Int) -> Unit, modifier: Modifier, adaptive: Boolean) {
    val included = entry.record.id in contextItems
    var menu by remember(entry.record.id) { mutableStateOf(false) }
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Glass).border(1.3.dp, Ice.copy(alpha = .55f), RoundedCornerShape(18.dp)).padding(if (adaptive) 16.dp else 20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Column(Modifier.weight(1f)) {
                HudText(entry.record.type, if (adaptive) 12 else 10, color = Muted)
                Row(Modifier.fillMaxWidth().heightIn(min = if (adaptive) 64.dp else 80.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        HudText(entry.record.title, if (adaptive) 23 else 29, weight = FontWeight.Bold, modifier = Modifier.testTag("detail-title"), maxLines = if (adaptive) 3 else 1)
                        HudText(entry.content.subtitle, if (adaptive) 14 else 13, color = Muted, modifier = Modifier.padding(top = 5.dp), maxLines = 2)
                    }
                    HudItemArt(entry, Modifier.size(if (adaptive) 54.dp else 67.dp))
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).border(.6.dp, Edge, RoundedCornerShape(4.dp))) {
                    entry.content.tabs.keys.forEach { name ->
                        Box(Modifier.heightIn(min = 48.dp).widthIn(min = 60.dp).clickable(role = Role.Tab) { onTab(name) }
                            .semantics { selected = tab == name }.testTag("tab-$name").padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                            HudText(name, if (adaptive) 12 else 10, color = if (tab == name) Ice else Muted, weight = FontWeight.Bold)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(if (adaptive) 150.dp else 65.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    HudText(entry.content.tabs[tab] ?: entry.content.summary, if (adaptive) 15 else 13, lineHeight = if (adaptive) 21 else 18, color = White.copy(alpha = .9f))
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    entry.content.tags.forEach { tag -> HudText(tag, if (adaptive) 12 else 10, color = Muted,
                        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFF132637)).border(1.dp, Edge, RoundedCornerShape(7.dp)).padding(horizontal = 7.dp, vertical = 4.dp)) }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ if (entry.category == 1) onOpenRecord(entry.record) else onToggleContext(entry.objectRef) }, Modifier.weight(1f).heightIn(min = 48.dp).testTag("primary-action"),
                        colors = ButtonDefaults.buttonColors(containerColor = if (included) Color(0xFF246776) else Color(0xFF099FC9)), shape = RoundedCornerShape(7.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        HudText(if (entry.category == 1) "OPEN PROJECT" else if (included) "REMOVE FROM CONTEXT" else "ADD TO CONTEXT", if (adaptive) 13 else 12, weight = FontWeight.Bold)
                    }
                    Box {
                        OutlinedIconButton({ menu = true }, Modifier.size(48.dp).testTag("object-menu")) { Icon(Icons.Outlined.MoreHoriz, "Record actions", tint = White) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Open record and actions") }, onClick = { menu = false; onOpenRecord(entry.record) })
                            DropdownMenuItem(text = { Text(if (included) "Remove from context" else "Add to context") }, onClick = { menu = false; onToggleContext(entry.objectRef) })
                            DropdownMenuItem(text = { Text("Manage records") }, onClick = { menu = false; onManage(entry.category) })
                        }
                    }
                }
            }
            if (!adaptive) Column(Modifier.width(200.dp)) {
                Box(Modifier.fillMaxWidth().height(121.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, Edge, RoundedCornerShape(9.dp))) {
                    when (entry.category) {
                        0 -> HudCrop("reference-models.jpg", 425, 789, 205, 115, Modifier.fillMaxSize())
                        1 -> HudCrop("reference-projects.jpg", 434, 739, 207, 112, Modifier.fillMaxSize())
                        2 -> HudHubArt(2, Modifier.size(115.dp).align(Alignment.Center))
                        else -> HudCrop("reference-knowledge.jpg", 495, 793, 199, 119, Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, Edge, RoundedCornerShape(9.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    HudText("SAVED RECORD", 10, color = Muted, weight = FontWeight.Bold)
                    entry.content.metrics.forEach { (label, value) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudText(label, 11, color = Muted, modifier = Modifier.weight(1f)); HudText(value, 11, modifier = Modifier.widthIn(max = 94.dp), maxLines = 2)
                    } }
                }
            }
        }
        if (adaptive) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Edge)
            entry.content.metrics.forEach { (label, value) -> HudText("$label: $value", 13, color = Muted, modifier = Modifier.padding(bottom = 4.dp)) }
            TextButton({ onOpenRecord(entry.record) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("OPEN RECORD AND ACTIONS") }
        }
    }
}

@Composable
private fun HudDetailPanel(category: Int, entry: HudItem, tab: String, onTab: (String) -> Unit,
                           contextItems: Set<String>, onToggleContext: (Orb) -> Unit, onOpenVault: () -> Unit,
                           modifier: Modifier) {
    var menu by remember(entry.record.id) { mutableStateOf(false) }
    val onMenu: (Boolean) -> Unit = { menu = it }
    val inContext = entry.record.id in contextItems
    Column(modifier.size(636.dp, 419.dp).graphicsLayer { shadowElevation = 18.dp.toPx(); ambientShadowColor = Ice; spotShadowColor = Ice }
        .clip(RoundedCornerShape(18.dp)).background(Glass)
        .border(1.3.dp, Ice.copy(alpha = .55f), RoundedCornerShape(18.dp)).padding(24.dp)) {
        Row(Modifier.fillMaxWidth().height(307.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(HudNavIcons[category], null, tint = Muted, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    HudText(listOf("AI MODEL", "PROJECT", "CONNECTOR", "KNOWLEDGE")[category], 10, color = Muted)
                }
                Row(Modifier.fillMaxWidth().height(92.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        HudText(entry.objectRef.title, if (entry.objectRef.title.length > 15) 29 else 31,
                            weight = FontWeight.Bold, modifier = Modifier.testTag("detail-title"), maxLines = 1)
                        HudText(entry.objectRef.sub, 15, color = Muted, modifier = Modifier.padding(top = 5.dp), maxLines = 2)
                    }
                    if (true) HudDetailArt(category, Modifier.size(77.dp))
                    else HudItemArt(entry, Modifier.size(77.dp))
                }
                Row(Modifier.fillMaxWidth().height(33.dp).border(.6.dp, Edge, RoundedCornerShape(4.dp)),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    entry.tabs.forEach { name ->
                        Column(Modifier.width(IntrinsicSize.Min).clickable { onTab(name) }.semantics { selected = tab == name }.padding(horizontal = 2.dp, vertical = 6.dp).testTag("tab-$name")) {
                            HudText(name, 11, color = if (tab == name) White else Muted, weight = FontWeight.Bold)
                            if (tab == name) Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(1.5.dp).background(Ice))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(75.dp).padding(top = 13.dp)) {
                    HudTabBody(category, entry, tab, contextItems, onOpenVault)
                }
                Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    entry.tags.forEach { tag ->
                        HudText(tag, 11, color = if (tag == "ACTIVE") Ready else Muted, weight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(if (tag == "ACTIVE") Color(0x99306655) else Color(0xFF132637))
                                .border(1.dp, if (tag == "ACTIVE") Ready.copy(alpha = .4f) else Edge, RoundedCornerShape(7.dp))
                                .padding(horizontal = 7.dp, vertical = 4.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().height(42.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().graphicsLayer { shadowElevation = 13.dp.toPx(); ambientShadowColor = Ice; spotShadowColor = Ice }
                        .clip(RoundedCornerShape(7.dp)).background(Brush.verticalGradient(
                            if (inContext) listOf(Color(0xFF287E87), Color(0xFF164D53))
                            else listOf(Color(0xFF28BDEA), Color(0xFF058AB6), Color(0xFF0CA9D5))))
                        .border(1.5.dp, Ice.copy(alpha = .8f), RoundedCornerShape(7.dp))
                        .clickable { if (category == 1) onOpenVault() else onToggleContext(entry.objectRef) }
                        .testTag("primary-action"), contentAlignment = Alignment.Center) {
                        HudText(if (category == 1) "OPEN PROJECT" else if (inContext) "REMOVE FROM CONTEXT" else "ADD TO CONTEXT", 12, weight = FontWeight.Bold)
                    }
                    Box(Modifier.width(46.dp).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(Ink)
                        .border(1.dp, Edge, RoundedCornerShape(7.dp)).clickable { onMenu(true) }.testTag("object-menu"), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.MoreHoriz, "Object actions", tint = White, modifier = Modifier.size(23.dp))
                        DropdownMenu(menu, { onMenu(false) }) {
                            DropdownMenuItem(text = { Text(if (inContext) "Remove from context" else "Add to context") }, onClick = { onToggleContext(entry.objectRef); onMenu(false) })
                            DropdownMenuItem(text = { Text("Open record and actions") }, onClick = { onOpenVault(); onMenu(false) })
                        }
                    }
                }
            }
            Column(Modifier.width(210.dp)) {
                Box(Modifier.fillMaxWidth().height(121.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, Edge, RoundedCornerShape(9.dp))) {
                    if (true) {
                        when (category) {
                            0 -> HudCrop("reference-models.jpg", 425, 789, 205, 115, Modifier.fillMaxSize())
                            1 -> HudCrop("reference-projects.jpg", 434, 739, 207, 112, Modifier.fillMaxSize())
                            2 -> HudItemArt(entry, Modifier.size(118.dp).align(Alignment.Center))
                            else -> HudCrop("reference-knowledge.jpg", 495, 793, 199, 119, Modifier.fillMaxSize())
                        }
                    } else HudItemArt(entry, Modifier.size(113.dp).align(Alignment.Center))
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, Edge, RoundedCornerShape(9.dp)).padding(13.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HudText(listOf("PERFORMANCE", "PROJECT", "CONNECTION", "KNOWLEDGE")[category], 10, color = Muted, weight = FontWeight.Bold)
                    entry.metrics.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            HudText(label, 11, color = Muted)
                            HudText(value, 11, color = if (value == "Ready" || value == "Active") Ready else White, weight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HudTabBody(category: Int, entry: HudItem, tab: String, contextItems: Set<String>, onOpenVault: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        HudText(entry.content.tabs[tab] ?: entry.description, 13, color = White.copy(alpha = .85f), lineHeight = 18)
        if (tab != "OVERVIEW" && tab != "CONTEXT") TextButton(onOpenVault, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(30.dp).testTag("open-vault")) {
            HudText("OPEN RECORD AND ACTIONS →", 11, color = Ice)
        }
    }
}

@Composable
private fun HudCarousel(items: List<HudItem>, start: Int, onScroll: (Int) -> Unit, onSelect: (Orb) -> Unit, modifier: Modifier) {
    Row(modifier.size(620.dp, 78.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.ChevronLeft, "Previous objects", tint = White, modifier = Modifier.size(20.dp, 52.dp).clickable { onScroll(-1) })
        repeat(5) { index ->
            if(index>=items.size){Spacer(Modifier.weight(1f).fillMaxHeight());return@repeat}
            val entry = items[(start + index) % items.size]
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color(0xDA091625))
                .border(1.dp, Edge, RoundedCornerShape(8.dp)).clickable { onSelect(entry.objectRef) }.testTag("carousel-${entry.record.id}"), horizontalAlignment = Alignment.CenterHorizontally) {
                HudThumbnail(entry, Modifier.fillMaxWidth().height(53.dp))
                HudText(entry.objectRef.title, 11, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(horizontal = 3.dp))
            }
        }
        Icon(Icons.Outlined.ChevronRight, "Next objects", tint = White, modifier = Modifier.size(20.dp, 52.dp).clickable { onScroll(1) })
    }
}

@Composable
private fun HudDock(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.offset(19.dp, 1194.dp).size(661.dp, 76.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        repeat(6) { index ->
            val active = selected == index
            Column(Modifier.weight(1f).fillMaxHeight().graphicsLayer {
                if (active) { shadowElevation = 16.dp.toPx(); ambientShadowColor = Ice; spotShadowColor = Ice }
            }.clip(RoundedCornerShape(6.dp)).background(if (active) Color(0xEC103346) else Color(0xDF081422))
                .border(if (active) 1.6.dp else .8.dp, if (active) Ice else Edge, RoundedCornerShape(6.dp))
                .clickable { onSelect(index) }.testTag("dock-$index").padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val dockX = listOf(46, 157, 262, 377, 484, 591)[index]
                HudRegion("reference-models.jpg", dockX, 1204, 48, 46, Modifier.size(45.dp))
                HudText(HudNavNames[index], 10, color = if (active) White else Muted, weight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun HudItemArt(entry: HudItem, modifier: Modifier) {
    val source = when (entry.objectRef.title) {
        "Qwen 3.5 2B" -> listOf(0, 262, 234, 58, 51, 16)
        "NeverSoft" -> listOf(1, 441, 240, 61, 55, -14)
        "MicroPPT" -> listOf(1, 526, 579, 55, 51, -31)
        "Veras" -> listOf(1, 274, 612, 56, 49, -30)
        "Engineering Vault" -> listOf(3, 281, 240, 69, 65, 16)
        "Personal Notes" -> listOf(3, 483, 251, 53, 60, -14)
        "Music Knowledge" -> listOf(3, 610, 337, 55, 60, -28)
        "Relationships" -> listOf(3, 586, 614, 64, 56, -33)
        "Device Data" -> listOf(3, 331, 637, 57, 58, -24)
        "Ghost Key" -> listOf(1, 195, 369, 54, 52, -15)
        "GhostGPT" -> listOf(1, 203, 507, 42, 40, 13)
        "MVE" -> listOf(1, 386, 632, 51, 47, 1)
        "Notion" -> listOf(2, 235, 526, 55, 51, 13)
        "Work Knowledge" -> listOf(3, 241, 521, 57, 49, 14)
        else -> null
    }
    if (source != null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            HudRegion("reference-${listOf("models", "projects", "connectors", "knowledge")[source[0]]}.jpg",
                source[1], source[2], source[3], source[4], Modifier.fillMaxSize(.76f).graphicsLayer { rotationZ = source[5].toFloat() })
        }
    } else if (entry.art >= 0) HudSprite("cards.png", entry.art, 4, modifier)
    else if (entry.hubArt >= 0) HudSprite("hubs.png", entry.hubArt, 2, modifier)
    else Icon(entry.objectRef.icon, null, tint = if (entry.objectRef.title == "Relationships") Color(0xFFFF638C) else Violet, modifier = modifier.padding(8.dp))
}

/** Keep the supplied artwork's proportions while leaving its surrounding labels native. */
@Composable
private fun HudHubArt(category: Int, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (category) {
            0 -> HudRegion("reference-models.jpg", 342, 430, 122, 127, Modifier.fillMaxSize(.82f))
            1 -> HudRegion("reference-projects.jpg", 371, 449, 74, 54, Modifier.fillMaxWidth(.56f).fillMaxHeight(.46f))
            2 -> HudRegion("reference-connectors.jpg", 398, 471, 105, 72, Modifier.fillMaxWidth(.78f).fillMaxHeight(.55f))
            else -> HudRegion("reference-knowledge.jpg", 410, 467, 80, 78, Modifier.fillMaxSize(.57f))
        }
    }
}

@Composable
private fun HudDetailArt(category: Int, modifier: Modifier) {
    when (category) {
        0 -> HudRegion("reference-models.jpg", 321, 787, 77, 88, modifier)
        1 -> HudRegion("reference-projects.jpg", 323, 738, 65, 61, modifier)
        2 -> HudRegion("reference-connectors.jpg", 307, 801, 91, 90, modifier)
        else -> HudRegion("reference-knowledge.jpg", 325, 796, 82, 92, modifier)
    }
}

@Composable
private fun HudThumbnail(entry: HudItem, modifier: Modifier) {
    val crop = when (entry.objectRef.title) {
        "Qwen 3.5 2B" -> listOf(0, 53, 1095, 113, 48)
        "Gemma 4 2B" -> listOf(0, 175, 1095, 112, 48)
        "Llama 3.2 3B" -> listOf(0, 298, 1095, 111, 48)
        "Mixtral 8x7B" -> listOf(0, 420, 1095, 109, 48)
        "Claude 3.5" -> listOf(0, 541, 1095, 106, 48)
        "NeverSoft" -> listOf(1, 57, 1027, 109, 53)
        "File Organizer" -> listOf(1, 180, 1027, 103, 53)
        "OSINT Tools" -> listOf(1, 301, 1027, 108, 53)
        "MicroPPT" -> listOf(1, 421, 1027, 102, 53)
        "Veras" -> listOf(1, 539, 1027, 94, 53)
        "Google Drive" -> listOf(2, 72, 1117, 109, 54)
        "Dropbox" -> listOf(2, 197, 1117, 111, 54)
        "Browser" -> listOf(2, 328, 1117, 107, 54)
        "Termux" -> listOf(2, 453, 1117, 108, 54)
        "Telegram" -> listOf(2, 578, 1117, 108, 54)
        "Personal Notes" -> listOf(3, 73, 1109, 109, 53)
        "Music Knowledge" -> listOf(3, 198, 1109, 111, 53)
        "OSINT Database" -> listOf(3, 326, 1109, 107, 53)
        "Relationships" -> listOf(3, 458, 1109, 105, 53)
        "Device Data" -> listOf(3, 591, 1109, 103, 53)
        else -> null
    }
    if (crop == null) HudItemArt(entry, modifier)
    else HudCrop("reference-${listOf("models", "projects", "connectors", "knowledge")[crop[0]]}.jpg",
        crop[1], crop[2], crop[3], crop[4], modifier)
}

@Composable
private fun hudBitmap(path: String): ImageBitmap {
    val context = LocalContext.current
    return remember(path, context) { HudBitmapCache.get(context, path) }
}

// Each card uses a region of an atlas. Decode that atlas once, rather than once per card.
private object HudBitmapCache {
    private val images = mutableMapOf<String, ImageBitmap>()
    @Synchronized fun get(context: Context, path: String): ImageBitmap = images.getOrPut(path) {
        context.assets.open("hud/$path").use { BitmapFactory.decodeStream(it).asImageBitmap() }
    }
}

@Composable private fun HudImage(path: String, modifier: Modifier, contentScale: ContentScale = ContentScale.Fit) { Image(hudBitmap(path), null, modifier, contentScale = contentScale) }
@Composable private fun HudRegion(path: String, x: Int, y: Int, width: Int, height: Int, modifier: Modifier) {
    val bitmap = hudBitmap(path)
    Canvas(modifier) { drawImage(bitmap, srcOffset = IntOffset(x,y), srcSize = IntSize(width,height), dstSize = IntSize(size.width.roundToInt(),size.height.roundToInt()), colorFilter = ArtworkAlpha, filterQuality = FilterQuality.High) }
}
@Composable private fun HudCrop(path: String, x: Int, y: Int, width: Int, height: Int, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Image(BitmapPainter(hudBitmap(path), IntOffset(x,y), IntSize(width,height)), null, modifier, contentScale = contentScale)
}
@Composable private fun HudText(text: String, size: Int, modifier: Modifier = Modifier, color: Color = White,
    weight: FontWeight = FontWeight.Normal, spacing: Float = 0f, lineHeight: Int = size + 3, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, color = color, fontSize = size.sp, fontFamily = HudFont, fontWeight = weight,
        letterSpacing = spacing.sp, lineHeight = lineHeight.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun HudSprite(path: String, cell: Int, columns: Int, modifier: Modifier) {
    val bitmap = hudBitmap(path)
    val cellWidth = bitmap.width / columns
    val cellHeight = bitmap.height / columns
    Canvas(modifier) {
        drawImage(bitmap, srcOffset = IntOffset((cell % columns) * cellWidth, (cell / columns) * cellHeight),
            srcSize = IntSize(cellWidth, cellHeight), dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            colorFilter = ArtworkAlpha, filterQuality = FilterQuality.High)
    }
}
