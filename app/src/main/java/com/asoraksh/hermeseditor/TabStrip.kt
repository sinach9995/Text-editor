package com.asoraksh.hermeseditor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The tab strip: one chip per open document, 38dp tall, directly under TopBar.
 *
 * Deliberately thin: it renders [TabState] snapshots and reports intent, so all
 * the state lives in the ViewModel and none of it is duplicated here.
 * An unsaved dot precedes a dirty title; the active chip is filled, the others
 * are dimmed, which is enough to tell ten tabs apart without a second colour.
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
            modifier = modifier.fillMaxWidth().height(38.dp),
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
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
    val background =
        if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    val textColor =
        if (active) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dirty) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Box(Modifier.size(4.dp))
        }
        Text(
            text = title,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 132.dp),
        )
        Box(Modifier.size(2.dp))
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text("×", color = textColor, fontSize = 15.sp)
        }
    }
}