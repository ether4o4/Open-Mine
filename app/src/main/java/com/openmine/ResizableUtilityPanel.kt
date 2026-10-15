package com.openmine

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private const val DefaultPanelHeight = 909f
private const val MinPanelHeight = 420f
private const val MaxPanelHeight = 1040f

/** Keeps the original portrait width, bottom edge, artwork, and contained screen intact. */
@Composable internal fun ResizableUtilityPanel(screen: Int, portrait: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("open_mine_panel_sizes", Context.MODE_PRIVATE) }
    val key = if (screen == 8) "browser_height" else "shell_height"
    val title = if (screen == 8) "Browser" else "Shell"
    var height by remember(key) {
        mutableFloatStateOf(preferences.getFloat(key, DefaultPanelHeight).takeIf { it.isFinite() }
            ?.coerceIn(MinPanelHeight, MaxPanelHeight) ?: DefaultPanelHeight)
    }
    var editor by remember(key) { mutableStateOf(false) }
    val maxHeight = if (portrait) MaxPanelHeight else DefaultPanelHeight
    val shownHeight = height.coerceIn(MinPanelHeight, maxHeight)
    fun update(value: Float) { if (value.isFinite()) height = value.coerceIn(MinPanelHeight, maxHeight) }
    fun save() { preferences.edit().putFloat(key, height).apply() }
    fun reset() { height = DefaultPanelHeight; preferences.edit().remove(key).apply() }
    val density = LocalDensity.current
    @Composable fun handle(modifier: Modifier, heightUnitsPerDp: Float = 1f) {
        Box(modifier
            .testTag("utility-resize-$screen")
            .semantics {
                contentDescription = "$title panel height"
                customActions = listOf(
                    CustomAccessibilityAction("Increase height") { update(shownHeight + 60); save(); true },
                    CustomAccessibilityAction("Decrease height") { update(shownHeight - 60); save(); true },
                    CustomAccessibilityAction("Reset height") { reset(); true },
                )
            }
            .draggable(rememberDraggableState { pixels ->
                update(height - with(density) { pixels.toDp().value } * heightUnitsPerDp)
            }, Orientation.Vertical, onDragStopped = { save() })
            .clickable { editor = true }, contentAlignment = Alignment.Center) {
            Box(Modifier.width(56.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF76E7FF)))
        }
    }
    if (portrait) {
        // 207/466/1174 are the existing HUD panel's left, width and bottom in artboard dp.
        Box(Modifier.offset(207.dp, (1174f - shownHeight).dp).width(466.dp).height(shownHeight.dp)
            .testTag("utility-panel-$screen")
            .clip(RoundedCornerShape(16.dp)).background(Color(0xDA071321))
            .border(1.dp, Color(0xFF293D53), RoundedCornerShape(16.dp))) { content() }
        handle(Modifier.offset(366.dp, (1174f - shownHeight - 32f).dp).width(148.dp).height(32.dp))
    } else {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val availableHeight = this.maxHeight
            val panelHeight = availableHeight * (shownHeight / DefaultPanelHeight)
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(panelHeight).testTag("utility-panel-$screen")) {
                handle(Modifier.fillMaxWidth().height(48.dp), DefaultPanelHeight / availableHeight.value.coerceAtLeast(1f))
                Box(Modifier.fillMaxWidth().weight(1f)) { content() }
            }
        }
    }
    if (editor) AlertDialog(onDismissRequest = { save(); editor = false },
        title = { Text("$title panel height") },
        text = { Column {
            Text("Drag the top handle up or down, or adjust the height here.")
            Slider(value = shownHeight, onValueChange = ::update, onValueChangeFinished = ::save,
                valueRange = MinPanelHeight..maxHeight, modifier = Modifier.testTag("utility-height-slider"))
        } },
        confirmButton = { TextButton({ save(); editor = false }) { Text("Done") } },
        dismissButton = { TextButton({ reset(); editor = false }) { Text("Reset height") } })
}
