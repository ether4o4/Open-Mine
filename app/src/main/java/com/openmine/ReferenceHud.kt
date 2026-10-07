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
private val Glass = Color(0xED071321)
private val Edge = Color(0xFF293D53)
private val Ice = Color(0xFF76E7FF)
private val White = Color(0xFFF0F6FB)
private val Muted = Color(0xFFAFBED0)
private val Violet = Color(0xFFAC7CF6)
private val HudFont = FontFamily(Font(R.font.ubuntu_regular), Font(R.font.ubuntu_medium, FontWeight.Medium), Font(R.font.ubuntu_bold, FontWeight.Bold))
private val ArtworkAlpha = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(1f,0f,0f,0f,0f, 0f,1f,0f,0f,0f, 0f,0f,1f,0f,0f, .75f,.75f,.75f,0f,0f)))
private val HudNavIcons = listOf(Icons.Outlined.Memory, Icons.Outlined.FolderOpen, Icons.Outlined.Hub,
    Icons.Outlined.LibraryBooks, Icons.Outlined.HourglassEmpty, Icons.Outlined.TaskAlt,
    Icons.Outlined.Construction, Icons.Outlined.Folder, Icons.Outlined.Language,
    Icons.Outlined.Terminal, Icons.Outlined.MonitorHeart, Icons.Outlined.Settings, Icons.Outlined.Chat)
private val HudNavNames = listOf("AI MODELS", "PROJECTS", "CONNECTORS", "KNOWLEDGE", "SKILLS", "MISSIONS",
    "TOOLS", "FILES", "BROWSER", "TERMINAL", "DIAGNOSTICS", "SETTINGS", "AI CHAT")

private data class HudItem(val record: StrictObject, val content: HudRecordContent, val objectRef: Orb, val category: Int)
private fun hudItem(record: StrictObject, category: Int) = HudItem(record, hudRecordContent(record),
    Orb(record.title, record.status, Violet, HudNavIcons[category], record.id, record), category)

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
        if (screen !in 0..3) {
            HudImage("background.png", Modifier.fillMaxSize(), ContentScale.Crop)
            Column(Modifier.fillMaxSize()) {
                HudNativeToolbar(HudNavNames.getOrElse(screen) { "WORKSPACE" }) { navigation = true }
                Box(Modifier.weight(1f).fillMaxWidth().background(Glass.copy(alpha = .86f))) { utility(screen) }
            }
        } else if (adaptive) {
            HudImage("background.png", Modifier.fillMaxSize(), ContentScale.Crop)
            Column {
                HudNativeToolbar(HudNavNames[screen]) { navigation = true }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("accessible-workspace"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { HudText("${items.size} saved records · ${actualContext.size} in context", 14, color = Muted) }
                    item { HudText(runtimeStatus, 14, color = Ice) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ onManage(screen) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Manage records") }
                        if (screen == 0) Button(onRuntime, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Model runtime") }
                    } }
                    if (current == null) item { HudEmptyState(category, onManage, onRuntime, Modifier.fillMaxWidth()) }
                    else {
                        item { HudDetailPanel(current, tab, { tab = it }, actualContext, onToggleContext, onOpenRecord, onManage, Modifier.fillMaxWidth(), true) }
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
                if (current == null) {
                    HudEmptyState(category, onManage, onRuntime, Modifier.offset(219.dp, 603.dp).width(444.dp))
                    Column(Modifier.offset(28.dp, 903.dp).size(636.dp, 228.dp).clip(RoundedCornerShape(18.dp)).background(Glass)
                        .border(1.dp, Ice.copy(alpha = .5f), RoundedCornerShape(18.dp)).padding(26.dp)) {
                        HudText("YOUR WORKSPACE", 24, weight = FontWeight.Bold)
                        HudText("Create or import your own records. Saved model and connector records describe configuration; their presence does not establish a running model or a connection.", 17, modifier = Modifier.padding(top = 16.dp), color = Muted, lineHeight = 24)
                        TextButton(onAssistant, Modifier.heightIn(min = 48.dp)) { HudText("OPEN AI CHAT  →", 15, color = Ice) }
                    }
                } else {
                    val selectedIndex = items.indexOf(current)
                    val start = (selectedIndex / 9) * 9
                    HudText("%02d".format(selectedIndex + 1), 28, color = Muted, modifier = Modifier.offset(318.dp, 452.dp))
                    items.drop(start).take(9).forEachIndexed { index, entry ->
                        val pos = HudPositions[index]
                        HudOrbitCard(entry, start + index, current.record.id == entry.record.id,
                            Modifier.offset(pos.first.dp, pos.second.dp).graphicsLayer { rotationZ = pos.third }) { onSelect(entry.objectRef) }
                    }
                    HudDetailPanel(current, tab, { tab = it }, actualContext, onToggleContext, onOpenRecord, onManage,
                        Modifier.offset(28.dp, 765.dp).size(636.dp, 419.dp), false)
                    HudCarousel(items, selectedIndex, onSelect, Modifier.offset(40.dp, 1098.dp))
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

@Composable private fun HudHeader() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = Date() } }
    Row(Modifier.offset(22.dp, 17.dp).size(650.dp, 56.dp), verticalAlignment = Alignment.CenterVertically) {
        HudCrop("reference-models.jpg", 20, 18, 43, 44, Modifier.size(44.dp)); Spacer(Modifier.width(17.dp))
        Column(Modifier.weight(1f)) { Row(verticalAlignment = Alignment.CenterVertically) {
            HudText("Open Mine", 27, weight = FontWeight.Bold)
            HudText("  ${BuildConfig.VERSION_NAME}", 9, color = Muted, modifier = Modifier.padding(top = 7.dp))
        }; HudText("AI WORKSPACE ENVIRONMENT", 11, color = Muted, spacing = 1.7f) }
        Column(Modifier.width(130.dp)) {
            HudText(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now), 17, weight = FontWeight.Bold)
            HudText(SimpleDateFormat("EEE M/d/yyyy", Locale.getDefault()).format(now), 13)
        }
        Icon(Icons.Outlined.NightlightRound, null, tint = Ice, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(12.dp))
        Column { HudText("LOCAL", 16, weight = FontWeight.Bold); HudText("Storage", 12, color = Muted) }
        Spacer(Modifier.width(32.dp))
    }
}

@Composable private fun HudRail(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.offset(18.dp, 101.dp).width(174.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HudNavNames.forEachIndexed { index, name -> val active = index == selected
            Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(5.dp))
                .background(if (active) Color(0xCC10364A) else Color(0x6B061320))
                .border(if (active) 1.5.dp else .6.dp, if (active) Ice.copy(alpha = .8f) else Edge.copy(alpha = .4f), RoundedCornerShape(5.dp))
                .clickable(role = Role.Tab) { onSelect(index) }.semantics { this.selected = active }.testTag("nav-$index").padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(HudNavIcons[index], null, tint = if (active) Ice else Muted, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(12.dp))
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
    Column(Modifier.offset(521.dp, 89.dp).size(151.dp, 152.dp).clip(RoundedCornerShape(8.dp)).background(Glass)
        .border(.7.dp, Edge, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
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

@Composable private fun HudOrbitCard(entry: HudItem, index: Int, active: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    Box(modifier.size(143.dp, 128.dp).graphicsLayer { shadowElevation = if (active) 28.dp.toPx() else 8.dp.toPx(); ambientShadowColor = Ice; spotShadowColor = Ice }
        .clip(RoundedCornerShape(9.dp)).background(Color(0xF00D1634)).border(if (active) 2.dp else 1.4.dp, if (active) Ice else Color(0x997D8794), RoundedCornerShape(9.dp))
        .clickable(role = Role.Button, onClick = onSelect).semantics { selected = active; contentDescription = "${entry.record.title}, ${entry.content.subtitle}" }.testTag("orbit-${entry.record.id}")) {
        HudCrop("card-frame.png", 13, 66, 1298, 1050, Modifier.fillMaxSize(), ContentScale.FillBounds)
        Column(Modifier.fillMaxSize().padding(start = 5.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = Muted, modifier = Modifier.size(9.dp)); HudText("%02d".format(index + 1), 9, color = Muted)
            }
            HudItemArt(entry, Modifier.size(60.dp).align(Alignment.CenterHorizontally))
            HudText(entry.record.title, 13, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp), maxLines = 1)
            HudText(entry.record.status, 10, color = Muted, modifier = Modifier.padding(start = 10.dp, top = 3.dp), maxLines = 1)
        }
    }
}

@Composable private fun HudDetailPanel(entry: HudItem, tab: String, onTab: (String) -> Unit, contextItems: Set<String>,
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

@Composable private fun HudCarousel(items: List<HudItem>, selected: Int, onSelect: (Orb) -> Unit, modifier: Modifier) {
    val page = items.drop((selected / 5) * 5).take(5)
    Row(modifier.size(620.dp, 78.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ onSelect(items[(selected - 1 + items.size) % items.size].objectRef) }, Modifier.size(48.dp), enabled = items.size > 1) { Icon(Icons.Outlined.ChevronLeft, "Previous record", tint = White) }
        page.forEach { entry ->
            Column(Modifier.width(96.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color(0xDA091625)).border(1.dp, Edge, RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { onSelect(entry.objectRef) }, horizontalAlignment = Alignment.CenterHorizontally) {
                HudItemArt(entry, Modifier.fillMaxWidth().height(53.dp)); HudText(entry.record.title, 11, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(horizontal = 3.dp))
            }
        }
        if (page.size < 5) Spacer(Modifier.weight(1f))
        IconButton({ onSelect(items[(selected + 1) % items.size].objectRef) }, Modifier.size(48.dp), enabled = items.size > 1) { Icon(Icons.Outlined.ChevronRight, "Next record", tint = White) }
    }
}

@Composable private fun HudDock(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.offset(19.dp, 1194.dp).size(661.dp, 76.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        repeat(6) { index -> val active = selected == index
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(if (active) Color(0xEC103346) else Color(0xDF081422))
                .border(if (active) 1.6.dp else .8.dp, if (active) Ice else Edge, RoundedCornerShape(6.dp))
                .clickable(role = Role.Tab) { onSelect(index) }.semantics { this.selected = active }.testTag("dock-$index").padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HudRegion("reference-models.jpg", listOf(46,157,262,377,484,591)[index], 1204, 48, 46, Modifier.size(45.dp))
                HudText(HudNavNames[index], 10, color = if (active) White else Muted, weight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable private fun HudItemArt(entry: HudItem, modifier: Modifier) { HudHubArt(entry.category, modifier) }

@Composable private fun HudHubArt(category: Int, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (category) {
            0 -> HudRegion("reference-models.jpg", 342, 430, 122, 127, Modifier.fillMaxSize(.82f))
            1 -> HudRegion("reference-projects.jpg", 371, 449, 74, 54, Modifier.fillMaxWidth(.56f).fillMaxHeight(.46f))
            2 -> HudRegion("reference-connectors.jpg", 398, 471, 105, 72, Modifier.fillMaxWidth(.78f).fillMaxHeight(.55f))
            else -> HudRegion("reference-knowledge.jpg", 410, 467, 80, 78, Modifier.fillMaxSize(.57f))
        }
    }
}

@Composable private fun hudBitmap(path: String): ImageBitmap {
    val context = LocalContext.current
    return remember(path, context) { HudBitmapCache.get(context, path) }
}
private object HudBitmapCache {
    private val images = mutableMapOf<String, ImageBitmap>()
    @Synchronized fun get(context: Context, path: String): ImageBitmap = images.getOrPut(path) {
        context.assets.open("hud/$path").use { requireNotNull(BitmapFactory.decodeStream(it)) { "Invalid HUD asset: $path" }.asImageBitmap() }
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
