package com.openmine

import android.graphics.BitmapFactory
import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
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
private val Violet = Color(0xFFAC7CF6)
private val Ready = Color(0xFF78DBB8)
private val HudFont = FontFamily(
    Font(R.font.ubuntu_regular, FontWeight.Normal),
    Font(R.font.ubuntu_medium, FontWeight.Medium),
    Font(R.font.ubuntu_bold, FontWeight.Bold)
)
// Dark atlas pixels contribute little opacity; bright artwork keeps its full color.
private val ArtworkAlpha = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    1f, 0f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f, 0f,
    0f, 0f, 1f, 0f, 0f,
    .75f, .75f, .75f, 0f, 0f
)))

internal data class HudItem(
    val objectRef: Orb,
    val art: Int = -1,
    val description: String,
    val tags: List<String>,
    val metrics: List<Pair<String, String>>,
    val tabs: List<String>,
    val hubArt: Int = -1
)

private fun item(title: String, sub: String, art: Int, icon: ImageVector,
                 description: String, tags: List<String>, metrics: List<Pair<String, String>>,
                 tabs: List<String>, hub: Int = -1) = HudItem(
    Orb(title, sub, Violet, icon), art, description, tags, metrics, tabs, hub
)

private val ModelTabs = listOf("OVERVIEW", "PARAMETERS", "CONTEXT", "TOOLS", "RELATED")
private val ProjectTabs = listOf("OVERVIEW", "FILES", "TASKS", "AGENTS", "CONTEXT")
private val ConnectorTabs = listOf("OVERVIEW", "PERMISSIONS", "TOOLS", "CONTEXT", "LOGS")
private val KnowledgeTabs = listOf("OVERVIEW", "DOCUMENTS", "CHUNKS", "CONTEXT", "RELATED")

internal val HudItems: List<List<HudItem>> = listOf(
    listOf(
        item("Qwen 3.5 2B", "Abliterated · GGUF · Local", 0, Icons.Outlined.Memory,
            "Lightweight, fast, and reliable local model optimized for reasoning, coding, and tool use. Quantized for on-device workspace tasks.",
            listOf("LOCAL", "GGUF", "2B", "Q4_K_M", "TOOL CALLING"),
            listOf("Context Window" to "32K", "RAM Usage" to "2.1 GB", "Quantization" to "Q4_K_M", "Status" to "Ready"), ModelTabs),
        item("Gemma 4 2B", "LITE RT · ANDROID", 1, Icons.Outlined.Memory,
            "A compact local model for responsive Android inference and everyday workspace assistance.", listOf("LOCAL", "LITE RT", "2B"), listOf("Context Window" to "32K", "Runtime" to "LiteRT", "Status" to "Available"), ModelTabs),
        item("Llama 3.2 3B", "OLLAMA · Q4", 2, Icons.Outlined.Memory,
            "General-purpose language model for local reasoning, writing, and coding through Ollama.", listOf("LOCAL", "OLLAMA", "3B", "Q4"), listOf("Context Window" to "128K", "Parameters" to "3B", "Status" to "Available"), ModelTabs),
        item("Hermes 3 3B", "TOOL CALLING", 5, Icons.Outlined.Build,
            "A workspace model focused on structured tool use and agent workflows.", listOf("LOCAL", "3B", "TOOLS"), listOf("Parameters" to "3B", "Capability" to "Tool calling", "Status" to "Available"), ModelTabs),
        item("RefinedToolCall", "CODING · AGENT", 8, Icons.Outlined.Code,
            "A coding and tool-calling object for reusable agent context.", listOf("CODING", "AGENT", "TOOLS"), listOf("Capability" to "Coding", "Status" to "Available"), ModelTabs),
        item("Suno", "MUSIC · CREATIVE", 7, Icons.Outlined.MusicNote,
            "Music and creative generation context for the workspace.", listOf("MUSIC", "CREATIVE"), listOf("Capability" to "Music", "Status" to "Available"), ModelTabs),
        item("Claude 3.5", "API · CLOUD", 6, Icons.Outlined.AutoAwesome,
            "Cloud model configuration for writing, reasoning, and development tasks.", listOf("API", "CLOUD", "REASONING"), listOf("Provider" to "Anthropic", "Status" to "Not configured"), ModelTabs),
        item("GPT-4o", "API · CLOUD", 4, Icons.Outlined.Psychology,
            "Multimodal model configuration for reasoning and workspace assistance.", listOf("API", "CLOUD", "MULTIMODAL"), listOf("Provider" to "OpenAI", "Status" to "Not configured"), ModelTabs),
        item("Mixtral 8x7B", "EXPERIMENTAL", 3, Icons.Outlined.Memory,
            "An experimental mixture-of-experts model configuration.", listOf("LOCAL", "MOE", "EXPERIMENTAL"), listOf("Parameters" to "8 × 7B", "Status" to "Available"), ModelTabs)
    ),
    listOf(
        item("Open Mine", "Android AI Workspace Environment", 3, Icons.Outlined.Workspaces,
            "A modern AI workspace for Android. Organize models, projects, connectors, and proven knowledge in one spatial environment.",
            listOf("ANDROID", "KOTLIN", "AI", "WORKSPACE", "ACTIVE"), listOf("Platform" to "Android", "Object Format" to "Strict V1", "Storage" to "Local", "Status" to "Active"), ProjectTabs),
        item("NeverSoft", "Agent Runtime", 0, Icons.Outlined.AutoAwesome, "Agent runtime project and development context.", listOf("AGENT", "RUNTIME"), listOf("Type" to "Runtime", "Status" to "Available"), ProjectTabs),
        item("File Organizer", "Utilities", -1, Icons.Outlined.Folder, "File organization project and utility context.", listOf("FILES", "UTILITY"), listOf("Type" to "Utility", "Status" to "Available"), ProjectTabs, 1),
        item("OSINT Tools", "Research Tools", 5, Icons.Outlined.Search, "Open-source research workflows and tool context.", listOf("OSINT", "RESEARCH"), listOf("Type" to "Research", "Status" to "Available"), ProjectTabs),
        item("MicroPPT", "Slide Builder", 8, Icons.Outlined.Slideshow, "Presentation and slide-building project context.", listOf("SLIDES", "BUILD"), listOf("Type" to "Presentation", "Status" to "Available"), ProjectTabs),
        item("MVE", "Kernel", -1, Icons.Outlined.Terminal, "Kernel and terminal project context.", listOf("KERNEL", "TERMINAL"), listOf("Status" to "Available"), ProjectTabs),
        item("Veras", "Creative", -1, Icons.Outlined.Favorite, "Creative project context for the workspace.", listOf("CREATIVE", "PROJECT"), listOf("Status" to "Available"), ProjectTabs),
        item("GhostGPT", "Chat Project", -1, Icons.Outlined.Chat, "Chat project and reusable assistant context.", listOf("CHAT", "PROJECT"), listOf("Status" to "Available"), ProjectTabs),
        item("Ghost Key", "File Explorer", 4, Icons.Outlined.Key, "File explorer project and workspace context.", listOf("FILES", "EXPLORER"), listOf("Status" to "Available"), ProjectTabs)
    ),
    listOf(
        item("GitHub MCP", "Repository & Development Tools", 9, Icons.Outlined.Code,
            "Connects to GitHub using MCP. Provides repository, issue, branch, and development tool context for the workspace.", listOf("MCP", "GITHUB", "REPO", "ISSUES", "ACTIVE"), listOf("Provider" to "GitHub", "Protocol" to "MCP", "Status" to "Not configured"), ConnectorTabs),
        item("Google Drive", "File Storage", 10, Icons.Outlined.Cloud, "File storage and document connector configuration.", listOf("FILES", "DRIVE", "CLOUD"), listOf("Provider" to "Google", "Status" to "Not configured"), ConnectorTabs),
        item("Dropbox", "File Storage", 11, Icons.Outlined.Cloud, "Cloud file synchronization connector configuration.", listOf("FILES", "DROPBOX", "CLOUD"), listOf("Provider" to "Dropbox", "Status" to "Not configured"), ConnectorTabs),
        item("Browser", "Web Automation", 13, Icons.Outlined.Language, "Web research and automation connector context.", listOf("WEB", "BROWSER"), listOf("Status" to "Not configured"), ConnectorTabs),
        item("Termux", "Command Tools", -1, Icons.Outlined.Terminal, "Android terminal and command tool connector context.", listOf("ANDROID", "TERMINAL"), listOf("Status" to "Not configured"), ConnectorTabs),
        item("Telegram", "Messaging", 14, Icons.Outlined.Send, "Messaging connector configuration for workspace workflows.", listOf("MESSAGING", "TELEGRAM"), listOf("Status" to "Not configured"), ConnectorTabs),
        item("Notion", "Docs & Notes", 12, Icons.Outlined.Description, "Document and note connector configuration.", listOf("DOCS", "NOTES", "NOTION"), listOf("Status" to "Not configured"), ConnectorTabs),
        item("Notion Key", "Docs & Notes", 12, Icons.Outlined.Description, "Reusable document connector credentials and permissions context.", listOf("DOCS", "NOTES", "NOTION"), listOf("Status" to "Not configured"), ConnectorTabs)
    ),
    listOf(
        item("Engineering Vault", "Project Specs & Documentation", 15, Icons.Outlined.Storage,
            "Complete repository of project documentation, architecture notes, build history, and technical specifications across all projects.", listOf("DOCS", "SPECS", "ARCHITECTURE", "TECHNICAL", "ACTIVE"), listOf("Object Format" to "Strict V1", "Storage" to "Local", "Status" to "Ready"), KnowledgeTabs),
        item("Personal Notes", "Everyday", -1, Icons.Outlined.Description, "Private notes and reusable everyday context.", listOf("NOTES", "PRIVATE"), listOf("Storage" to "Local", "Status" to "Ready"), KnowledgeTabs),
        item("Music Knowledge", "Lyrics & Audio", -1, Icons.Outlined.MusicNote, "Music references, lyrics, and audio context.", listOf("MUSIC", "AUDIO"), listOf("Storage" to "Local", "Status" to "Ready"), KnowledgeTabs),
        item("OSINT Database", "Links & Methods", 13, Icons.Outlined.Language, "Research sources, links, and open-source investigation methods.", listOf("OSINT", "LINKS", "RESEARCH"), listOf("Storage" to "Local", "Status" to "Ready"), KnowledgeTabs),
        item("Relationships", "People & Connections", -1, Icons.Outlined.Favorite, "Relationships and connected knowledge context.", listOf("PEOPLE", "CONNECTIONS"), listOf("Storage" to "Local", "Status" to "Ready"), KnowledgeTabs),
        item("Device Data", "Device Information", -1, Icons.Outlined.PhoneAndroid, "Device information and workspace configuration context.", listOf("DEVICE", "ANDROID"), listOf("Storage" to "Local", "Status" to "Ready"), KnowledgeTabs),
        item("Work Knowledge", "Tools & Methods", -1, Icons.Outlined.Build, "Reusable work methods and tool documentation.", listOf("WORK", "METHODS"), listOf("Storage" to "Local", "Status" to "Ready"), KnowledgeTabs),
        item("Work Methods", "Code & Methods", 9, Icons.Outlined.Code, "Repository references and reusable development methods.", listOf("WORK", "CODE"), listOf("Storage" to "Local", "Status" to "Ready"), KnowledgeTabs)
    )
)

private val HudNavIcons = listOf(Icons.Outlined.Memory, Icons.Outlined.FolderOpen, Icons.Outlined.Hub,
    Icons.Outlined.LibraryBooks, Icons.Outlined.HourglassEmpty, Icons.Outlined.TaskAlt,
    Icons.Outlined.Construction, Icons.Outlined.Folder, Icons.Outlined.Language,
    Icons.Outlined.Terminal, Icons.Outlined.MonitorHeart, Icons.Outlined.Settings)
private val HudNavNames = listOf("AI MODELS", "PROJECTS", "CONNECTORS", "KNOWLEDGE", "SKILLS", "MISSIONS",
    "TOOLS", "FILES", "BROWSER", "TERMINAL", "DIAGNOSTICS", "SETTINGS")

/** The reference is a portrait composition. Fit the whole artboard without clipping its dock. */
@Composable
private fun HudArtboard(content: @Composable BoxScope.() -> Unit) {
    Layout(content = { Box(Modifier.requiredSize(700.dp, 1280.dp).testTag("hud-artboard"), content = content) }) { measurables, constraints ->
        val designWidth = 700.dp.roundToPx()
        val designHeight = 1280.dp.roundToPx()
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val scale = min(width.toFloat() / designWidth, height.toFloat() / designHeight)
        val child = measurables.single().measure(Constraints.fixed(designWidth, designHeight))
        layout(width, height) {
            child.placeWithLayer(((width - designWidth * scale) / 2).toInt(), ((height - designHeight * scale) / 2).toInt()) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

@Composable
internal fun ReferenceHud(
    screen: Int, animations: Boolean, active: String, contextItems: Set<String>,
    onNavigate: (Int) -> Unit, onSelect: (Orb) -> Unit, onToggleContext: (Orb) -> Unit,
    onOpenVault: () -> Unit, utility: @Composable (Int) -> Unit
) {
    val category = screen.coerceIn(0, 3)
    val items = HudItems[category]
    val current = items.firstOrNull { it.objectRef.title == active } ?: items.first()
    val selectedIndex = items.indexOf(current)
    val pulse = if (animations) {
        val transition = rememberInfiniteTransition(label = "hub-glow")
        transition.animateFloat(.88f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "glow").value
    } else 1f
    var tab by remember(screen, current.objectRef.title) { mutableStateOf("OVERVIEW") }
    var menu by remember(screen, current.objectRef.title) { mutableStateOf(false) }
    val carouselItems = when (category) {
        0 -> listOf(0, 1, 2, 8, 6, 3, 4, 5, 7).map { items[it] }
        1 -> listOf(0, 1, 2, 3, 4, 6, 5, 7, 8).map { items[it] }
        else -> items
    }
    var carouselStart by remember(screen) { mutableIntStateOf(if (category == 0) 0 else 1) }

    Box(Modifier.fillMaxSize().background(Ink).testTag("hud-root")) {
        HudArtboard {
            HudImage("background.png", Modifier.offset((-22).dp, 0.dp).fillMaxSize().graphicsLayer { alpha = .78f }, contentScale = ContentScale.FillBounds)
            HudHeader()
            HudRail(screen, onNavigate)
            HudLocation()
            HudStats(category, contextItems.size)
            if (screen < 4) {
                Box(Modifier.offset(315.dp, 413.dp).size(195.dp, 190.dp)) {
                    HudText(HudNavNames[category], 14, weight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = if (category == 0) 0.dp else 30.dp))
                    HudHubArt(category, Modifier.size(if (category == 0) 151.dp else 127.dp)
                        .align(Alignment.TopCenter).offset(y = if (category == 0) 17.dp else 35.dp).graphicsLayer { alpha = pulse })
                    if (category != 0) HudText(HudNavNames[category] + "    %02d".format(category + 1), 12, color = White,
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = 148.dp))
                }
                HudText("%02d".format(selectedIndex + 1), 28, color = Muted, modifier = Modifier.offset(318.dp, if (category == 0) 440.dp else 452.dp))
                items.forEachIndexed { index, entry ->
                    val position = positions(items.size)[index]
                    HudOrbitCard(entry, index, current == entry,
                        Modifier.offset(position.first.dp, position.second.dp).graphicsLayer { rotationZ = position.third },
                        { onSelect(entry.objectRef) })
                }
                HudDetailPanel(category, current, tab, { tab = it }, contextItems, onToggleContext, onOpenVault,
                    menu, { menu = it }, Modifier.offset(28.dp, 765.dp))
                HudCarousel(carouselItems, carouselStart, { carouselStart = (carouselStart + it + carouselItems.size) % carouselItems.size },
                    onSelect, Modifier.offset(40.dp, 1098.dp))
            } else {
                Box(Modifier.offset(207.dp, 265.dp).size(466.dp, 909.dp).clip(RoundedCornerShape(16.dp))
                    .background(Glass).border(1.dp, Edge, RoundedCornerShape(16.dp))) { utility(screen) }
            }
            HudDock(screen, onNavigate)
        }
    }
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
                HudText("  v1.0", 10, color = Muted, modifier = Modifier.padding(top = 7.dp))
            }
            HudText("AI WORKSPACE ENVIRONMENT", 11, color = Muted, spacing = 1.7f)
        }
        Column(Modifier.width(122.dp)) {
            HudText(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now), 17, weight = FontWeight.Bold)
            HudText(SimpleDateFormat("EEE M/d/yyyy", Locale.getDefault()).format(now), 13)
        }
        Icon(Icons.Outlined.NightlightRound, "Local workspace", tint = Ice, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(14.dp))
        Column { HudText("LOCAL", 16, weight = FontWeight.Bold); HudText("Ready", 12, color = Muted) }
        Spacer(Modifier.width(14.dp))
        Icon(Icons.Outlined.Equalizer, null, tint = Muted, modifier = Modifier.size(42.dp))
    }
}

@Composable
private fun HudRail(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.offset(18.dp, 101.dp).width(174.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HudNavNames.forEachIndexed { index, name ->
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

@Composable
private fun HudLocation() {
    Row(Modifier.offset(226.dp, 110.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.LocationOn, null, tint = Ice, modifier = Modifier.size(29.dp))
        Spacer(Modifier.width(27.dp))
        Column {
            HudText("LOCATION", 10, color = Muted, weight = FontWeight.Bold)
            HudText("Local workspace", 11, weight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
            HudText("ON DEVICE · PRIVATE STORAGE", 8, color = Muted, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun HudStats(category: Int, contextCount: Int) {
    val metrics = when (category) {
        0 -> listOf("MODELS" to "${HudItems[0].size}", "PROJECTS" to "${HudItems[1].size}", "CONNECTORS" to "${HudItems[2].size}", "IN CONTEXT" to "$contextCount")
        1 -> listOf("PROJECTS" to "${HudItems[1].size}", "FORMAT" to "V1", "STORAGE" to "LOCAL", "IN CONTEXT" to "$contextCount")
        2 -> listOf("AVAILABLE" to "${HudItems[2].size}", "PROTOCOL" to "MCP", "STORAGE" to "LOCAL", "IN CONTEXT" to "$contextCount")
        else -> listOf("SOURCES" to "${HudItems[3].size}", "FORMAT" to "V1", "STORAGE" to "LOCAL", "IN CONTEXT" to "$contextCount")
    }
    Column(Modifier.offset(521.dp, 89.dp).size(151.dp, 152.dp).clip(RoundedCornerShape(8.dp)).background(Glass.copy(alpha = .65f))
        .border(.7.dp, Edge.copy(alpha = .6f), RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
        metrics.forEachIndexed { index, metric ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(HudNavIcons[if (index < 3) index else 3], null, tint = Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                HudText(metric.first, 8, color = Muted, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                HudText(metric.second, 9, weight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HudOrbitCard(entry: HudItem, index: Int, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    Box(modifier.size(143.dp, 128.dp)
        .graphicsLayer { shadowElevation = if (selected) 28.dp.toPx() else 8.dp.toPx(); ambientShadowColor = if (selected) Ice else Color.Black; spotShadowColor = if (selected) Ice else Color.Black }
        .clip(RoundedCornerShape(9.dp)).background(if (selected) Color(0xF00D1634) else Color(0xD907101F))
        .border(if (selected) 2.dp else 1.4.dp, if (selected) Ice else Color(0xFF7D8794).copy(alpha = .62f), RoundedCornerShape(9.dp))
        .clickable(onClick = onSelect).testTag("orbit-${entry.objectRef.title}")) {
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

@Composable
private fun HudDetailPanel(category: Int, entry: HudItem, tab: String, onTab: (String) -> Unit,
                           contextItems: Set<String>, onToggleContext: (Orb) -> Unit, onOpenVault: () -> Unit,
                           menu: Boolean, onMenu: (Boolean) -> Unit, modifier: Modifier) {
    val inContext = entry.objectRef.title in contextItems
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
                    if (entry == HudItems[category].first()) HudDetailArt(category, Modifier.size(77.dp))
                    else HudItemArt(entry, Modifier.size(77.dp))
                }
                Row(Modifier.fillMaxWidth().height(33.dp).border(.6.dp, Edge, RoundedCornerShape(4.dp)),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    entry.tabs.forEach { name ->
                        Column(Modifier.width(IntrinsicSize.Min).clickable { onTab(name) }.padding(horizontal = 2.dp, vertical = 6.dp).testTag("tab-$name")) {
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
                        .clickable { if (category == 1) onTab("FILES") else onToggleContext(entry.objectRef) }
                        .testTag("primary-action"), contentAlignment = Alignment.Center) {
                        HudText(if (category == 1) "OPEN PROJECT" else if (inContext) "REMOVE FROM CONTEXT" else "ADD TO CONTEXT", 12, weight = FontWeight.Bold)
                    }
                    Box(Modifier.width(46.dp).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(Ink)
                        .border(1.dp, Edge, RoundedCornerShape(7.dp)).clickable { onMenu(true) }.testTag("object-menu"), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.MoreHoriz, "Object actions", tint = White, modifier = Modifier.size(23.dp))
                        DropdownMenu(menu, { onMenu(false) }) {
                            DropdownMenuItem(text = { Text(if (inContext) "Remove from context" else "Add to context") }, onClick = { onToggleContext(entry.objectRef); onMenu(false) })
                            DropdownMenuItem(text = { Text("Open object vault") }, onClick = { onOpenVault(); onMenu(false) })
                        }
                    }
                }
            }
            Column(Modifier.width(210.dp)) {
                Box(Modifier.fillMaxWidth().height(121.dp).clip(RoundedCornerShape(9.dp)).border(1.dp, Edge, RoundedCornerShape(9.dp))) {
                    if (entry == HudItems[category].first()) {
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
    if (category == 3 && tab in listOf("DOCUMENTS", "CHUNKS")) {
        Column {
            HudText("Validated objects · labeled chunks · exact-term retrieval", 12, color = Muted)
            TextButton(onOpenVault, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(34.dp).testTag("open-vault")) {
                HudText("OPEN OBJECT VAULT  →", 12, color = Ice, weight = FontWeight.Bold)
            }
        }
    } else {
        val copy = when (tab) {
            "OVERVIEW" -> entry.description
            "CONTEXT" -> if (entry.objectRef.title in contextItems) "${entry.objectRef.title} is included in your workspace context. ${contextItems.size} object(s) selected." else "Add ${entry.objectRef.title} to your workspace context using the action below."
            "PARAMETERS" -> entry.metrics.filter { it.first != "Status" }.joinToString("   ·   ") { "${it.first}: ${it.second}" }
            "TOOLS" -> if (category == 2) "Connector definition available. Configure the provider before using live tools." else "Tool calling and workspace context are described by this object."
            "PERMISSIONS" -> "No external access has been configured. This connector currently stores local workspace context."
            "LOGS" -> "No connection activity recorded."
            "FILES" -> "Project files and documentation are managed as strict objects in the local vault."
            "TASKS" -> "Build Open Mine · Verify Vault · Connect Local AI · Ship APK"
            "AGENTS" -> "Agent runtime context is available in the project and model objects."
            else -> "Related objects are available in the carousel below. Select one to inspect its workspace context."
        }
        Column {
            HudText(copy, 13, color = White.copy(alpha = .85f), lineHeight = 18, maxLines = if (tab == "FILES") 2 else 3)
            if (tab == "FILES") TextButton(onOpenVault, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(30.dp)) {
                HudText("OPEN PROJECT VAULT  →", 11, color = Ice)
            }
        }
    }
}

@Composable
private fun HudCarousel(items: List<HudItem>, start: Int, onScroll: (Int) -> Unit, onSelect: (Orb) -> Unit, modifier: Modifier) {
    Row(modifier.size(620.dp, 78.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.ChevronLeft, "Previous objects", tint = White, modifier = Modifier.size(20.dp, 52.dp).clickable { onScroll(-1) })
        repeat(5) { index ->
            val entry = items[(start + index) % items.size]
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color(0xDA091625))
                .border(1.dp, Edge, RoundedCornerShape(8.dp)).clickable { onSelect(entry.objectRef) }, horizontalAlignment = Alignment.CenterHorizontally) {
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

@Composable
private fun HudImage(path: String, modifier: Modifier, contentScale: ContentScale = ContentScale.Fit) {
    Image(hudBitmap(path), null, modifier, contentScale = contentScale)
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

@Composable
private fun HudRegion(path: String, x: Int, y: Int, width: Int, height: Int, modifier: Modifier) {
    val bitmap = hudBitmap(path)
    Canvas(modifier) {
        drawImage(bitmap, srcOffset = IntOffset(x, y), srcSize = IntSize(width, height),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            colorFilter = ArtworkAlpha, filterQuality = FilterQuality.High)
    }
}

/** Reuse supplied brand and detail artwork; all UI remains native, selectable text and controls. */
@Composable
private fun HudCrop(path: String, x: Int, y: Int, width: Int, height: Int, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Image(BitmapPainter(hudBitmap(path), IntOffset(x, y), IntSize(width, height)), null, modifier, contentScale = contentScale)
}

@Composable
private fun HudText(text: String, size: Int, modifier: Modifier = Modifier, color: Color = White,
                    weight: FontWeight = FontWeight.Normal, spacing: Float = 0f, lineHeight: Int = size + 3,
                    maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, color = color, fontSize = size.sp, fontFamily = HudFont, fontWeight = weight,
        letterSpacing = spacing.sp, lineHeight = lineHeight.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
