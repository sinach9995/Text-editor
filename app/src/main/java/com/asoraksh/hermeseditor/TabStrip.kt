package com.asoraksh.hermeseditor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The tab strip: one tab per open document, directly under the top bar.
 *
 * The active tab is drawn as a cut-out of the page, not as a chip floating on
 * the bar: it takes the page colour, keeps its top corners rounded, loses its
 * bottom corners and sits flush against the content below, so the strip and the
 * text read as one surface. A 2dp accent line marks it in the light theme,
 * where the two colours are almost identical.
 *
 * Deliberately thin: it renders [TabState] snapshots and reports intent, so all
 * the state lives in the ViewModel and none of it is duplicated here.
 */
@Composable
fun TabStrip(
    tabs: List<TabState>,
    activeId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val activeIndex = tabs.indexOfFirst { it.id == activeId }
    // A newly created tab is appended off-screen; without this the strip would
    // look unchanged and the user would think New did nothing.
    LaunchedEffect(activeIndex, tabs.size) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        LazyRow(
            modifier = modifier.fillMaxWidth().height(54.dp),
            state = listState,
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            // Bottom-aligned, so the active tab touches the page and the
            // inactive ones stay visibly docked above it.
            verticalAlignment = Alignment.Bottom,
        ) {
            items(tabs, key = { it.id }) { tab ->
                TabChip(
                    title = tab.title,
                    dirty = tab.isDirty,
                    active = tab.id == activeId,
                    onSelect = { onSelect(tab.id) },
                    onClose = { onClose(tab.id) },
                )
            }
        }
    }
}

@Composable
private fun TabChip(
    title: String,
    dirty: Boolean,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val pageColor = MaterialTheme.colorScheme.background
    val barColor = MaterialTheme.colorScheme.surface
    val chipColor = MaterialTheme.colorScheme.surfaceVariant
    val background = if (active) pageColor else chipColor.copy(alpha = if (barColor.perceivedBrightness() > 0.5f) 0.7f else 0.55f)
    val textColor =
        if (active) MaterialTheme.colorScheme.onBackground
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    val shape = if (active) RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    else RoundedCornerShape(12.dp)
    // Only the active tab reaches the page edge; the others keep a small gap
    // so the strip reads as a shelf with one open drawer.
    val height = if (active) 42.dp else 38.dp
    Column(
        modifier = Modifier
            .height(height)
            .clip(shape)
            .background(background)
            .clickable(onClick = onSelect),
    ) {
        if (active) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Row(
            modifier = Modifier.weight(1f).padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dirty) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Box(Modifier.size(6.dp))
            }
            ScrollingTitle(title, textColor, active)
            Box(Modifier.size(4.dp))
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = textColor, fontSize = 17.sp)
            }
        }
    }
}

/**
 * A title wider than the tab can be dragged sideways to reveal its end, which
 * a plain ellipsis can never do. The drag is enabled only after a measurement
 * says the text actually overflows: attaching the scrollable unconditionally
 * would swallow the strip's own horizontal drag, and re-measuring inside it
 * would then report "no overflow" and flip it back every frame. So the flag
 * latches on first overflow and resets only when the title itself changes.
 */
@Composable
private fun ScrollingTitle(title: String, color: Color, active: Boolean) {
    var overflow by remember(title) { mutableStateOf(false) }
    val scroll = rememberScrollState()
    Box(
        Modifier
            .widthIn(max = 172.dp)
            .then(if (overflow) Modifier.horizontalScroll(scroll) else Modifier)
    ) {
        Text(
            text = title,
            color = color,
            fontSize = if (active) 14.sp else 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> if (!overflow && result.didOverflowWidth) overflow = true },
        )
    }
}

/** Cheap perceived brightness, used only to pick the inactive chip's alpha. */
private fun Color.perceivedBrightness(): Float = 0.299f * red + 0.587f * green + 0.114f * blue