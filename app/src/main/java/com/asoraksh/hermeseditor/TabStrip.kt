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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The tab strip: one tab per open document, directly under the top bar.
 *
 * The active tab is a cut-out of the page, not a chip floating on the bar: it
 * takes the page colour, starts where the top bar ends, drops its bottom
 * corners for two concave feet that bend into the text below, and is drawn per
 * tab, so the feet travel with the tab as the strip scrolls.
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
            modifier = modifier.fillMaxWidth().height(42.dp),
            state = listState,
            // No top padding: the active tab starts exactly where the top bar
            // ends. The closed tabs are just 2dp shorter, so their gap reads as
            // "not open" instead of pushing the whole row down.
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
    val barColor = MaterialTheme.colorScheme.surface
    val chipColor = MaterialTheme.colorScheme.surfaceVariant
    val background = if (active) MaterialTheme.colorScheme.background
    else chipColor.copy(alpha = if (barColor.perceivedBrightness() > 0.5f) 0.7f else 0.55f)
    val textColor =
        if (active) MaterialTheme.colorScheme.onBackground
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    // The open tab owns the full 42dp and is welded to the page; a closed one is
    // 40dp, so it hangs 2dp below the top bar and reads as a separate sheet.
    val height = if (active) 42.dp else 40.dp
    val shape = if (active) MergedTabShape() else RoundedCornerShape(12.dp)
    // The active tab's outline is inset by the foot width so the feet have room
    // to flare outwards; content is pushed in by the same amount to stay centred
    // in the visible body.
    val inset = if (active) FOOT else 0.dp
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
                    .padding(horizontal = inset)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Row(
            modifier = Modifier.weight(1f).padding(start = 12.dp + inset, end = 6.dp + inset),
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
 * A title wider than its tab can be dragged sideways to reveal the end, which
 * an ellipsis can never do.
 *
 * The overflow decision comes from measuring the raw text, not from reading the
 * layout result: with `softWrap = false` a clipped [Text] does not reliably
 * report `didOverflowWidth`, and an earlier version that waited for it never
 * enabled the drag at all. Measuring up front also avoids the feedback loop of
 * a scrollable that re-measures its child with unbounded width.
 */
@Composable
private fun ScrollingTitle(title: String, color: Color, active: Boolean) {
    val style = TextStyle(
        fontSize = if (active) 14.sp else 13.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
    )
    val maxWidth = if (active) 176.dp else 160.dp
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val overflow = remember(title, style, maxWidth, density) {
        val natural = measurer.measure(AnnotatedString(title), style, softWrap = false, maxLines = 1)
        natural.size.width > with(density) { maxWidth.toPx() }
    }
    val scroll = rememberScrollState()
    Box(
        Modifier
            .widthIn(max = maxWidth)
            .then(if (overflow) Modifier.horizontalScroll(scroll) else Modifier)
    ) {
        Text(
            text = title,
            color = color,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = if (overflow) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
}

/**
 * The active tab's outline: rounded on top, and flared at the bottom by two
 * concave quarter-circles, so the tab is 2 x [FOOT] wider at the page edge than
 * in its body and its sides splay onto the text below.
 *
 * The feet are concave: each arc's centre lies outside the tab, so the corner is
 * carved away and only a tapering sliver of the tab reaches the page edge. A
 * convex version looked like a bump glued to the corner.
 *
 * It is a per-tab shape, so the feet travel with the tab while the strip scrolls.
 */
private class MergedTabShape(
    private val topRadius: Dp = 14.dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val h = size.height
        val tr = with(density) { topRadius.toPx() }.coerceAtMost(h / 2f)
        val f = with(density) { FOOT.toPx() }.coerceAtMost(size.width / 4f)
        val left = f
        val right = size.width - f
        val path = Path().apply {
            moveTo(left, tr)
            quadraticBezierTo(left, 0f, left + tr, 0f)
            lineTo(right - tr, 0f)
            quadraticBezierTo(right, 0f, right, tr)
            lineTo(right, h - f)
            // Right foot: a concave quarter circle. Its centre sits outside the
            // tab, above the page edge, so the material under the corner is
            // carved away and the foot tapers onto the page instead of ending
            // in a bump.
            arcTo(Rect(right, h - 2f, right + 2f, h), 180f, -90f, false)
            lineTo(left - f, h)
            // Left foot, mirrored.
            arcTo(Rect(left - 2f, h - 2f, left, h), 90f, -90f, false)
            close()
        }
        return Outline.Generic(path)
    }
}

/** Width the feet claim on each side of the active tab. */
private val FOOT = 12.dp

/** Cheap perceived brightness, used only to pick the inactive chip's alpha. */
private fun Color.perceivedBrightness(): Float = 0.299f * red + 0.587f * green + 0.114f * blue