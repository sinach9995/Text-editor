package com.asoraksh.hermeseditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Pure track/thumb geometry math for [DocumentScrollbar], covered by JVM unit tests. */
internal object ScrollbarMath {

    fun thumbHeightPx(
        trackHeight: Float,
        viewportHeight: Float,
        maxScroll: Int,
        minThumbPx: Float,
    ): Float {
        if (trackHeight <= 0f || viewportHeight <= 0f || maxScroll <= 0) return 0f
        val totalContent = viewportHeight + maxScroll.toFloat()
        val proportional = trackHeight * (viewportHeight / totalContent)
        val floor = minThumbPx.coerceAtMost(trackHeight)
        return proportional.coerceIn(floor, trackHeight)
    }

    fun thumbOffsetPx(
        trackHeight: Float,
        thumbHeight: Float,
        scrollValue: Int,
        maxScroll: Int,
    ): Float {
        val travel = (trackHeight - thumbHeight).coerceAtLeast(0f)
        if (travel <= 0f || maxScroll <= 0) return 0f
        val fraction = (scrollValue.toFloat() / maxScroll.toFloat()).coerceIn(0f, 1f)
        return fraction * travel
    }

    fun scrollValueForThumbTop(
        thumbTop: Float,
        trackHeight: Float,
        thumbHeight: Float,
        maxScroll: Int,
    ): Int {
        val travel = (trackHeight - thumbHeight).coerceAtLeast(0f)
        if (travel <= 0f || maxScroll <= 0) return 0
        val fraction = (thumbTop / travel).coerceIn(0f, 1f)
        return (fraction * maxScroll.toFloat()).roundToInt().coerceIn(0, maxScroll)
    }

    fun scrollValueForY(
        touchY: Float,
        trackHeight: Float,
        thumbHeight: Float,
        maxScroll: Int,
    ): Int = scrollValueForThumbTop(touchY - thumbHeight / 2f, trackHeight, thumbHeight, maxScroll)
}

/**
 * Draggable fast-scrollbar anchored on the side opposite the bottom-end corner buttons.
 */
@Composable
internal fun DocumentScrollbar(
    scrollState: ScrollState,
    viewportHeightPx: Int,
    modifier: Modifier = Modifier,
) {
    if (scrollState.maxValue <= 0 || viewportHeightPx <= 0) return

    val scope = rememberCoroutineScope()
    val currentState by rememberUpdatedState(scrollState)
    val currentViewport by rememberUpdatedState(viewportHeightPx)
    var trackHeightPx by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    val primary = MaterialTheme.colorScheme.primary
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .width(24.dp)
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
            .pointerInput(Unit) {
                val minThumbPx = 44.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val state = currentState
                    val vp = currentViewport
                    val trackH = trackHeightPx
                    val maxScroll = state.maxValue
                    if (trackH <= 0f || vp <= 0 || maxScroll <= 0) return@awaitEachGesture

                    val thumbH = ScrollbarMath.thumbHeightPx(trackH, vp.toFloat(), maxScroll, minThumbPx)
                    val thumbY = ScrollbarMath.thumbOffsetPx(trackH, thumbH, state.value, maxScroll)
                    val downY = down.position.y
                    val grabOffset = if (downY in thumbY..(thumbY + thumbH)) downY - thumbY else thumbH / 2f
                    down.consume()
                    dragging = true

                    val initialTarget = ScrollbarMath.scrollValueForThumbTop(downY - grabOffset, trackH, thumbH, maxScroll)
                    scope.launch { state.scrollTo(initialTarget) }

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) break
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val liveMax = state.maxValue
                            if (liveMax > 0) {
                                val target = ScrollbarMath.scrollValueForThumbTop(
                                    change.position.y - grabOffset,
                                    trackH,
                                    thumbH,
                                    liveMax,
                                )
                                state.dispatchRawDelta((target - state.value).toFloat())
                            }
                            change.consume()
                        }
                    } finally {
                        dragging = false
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val maxScroll = scrollState.maxValue
            val minThumbPx = 44.dp.toPx()
            val thumbH = ScrollbarMath.thumbHeightPx(size.height, viewportHeightPx.toFloat(), maxScroll, minThumbPx)
            if (thumbH <= 0f) return@Canvas
            val thumbY = ScrollbarMath.thumbOffsetPx(size.height, thumbH, scrollState.value, maxScroll)

            val centerX = if (layoutDirection == LayoutDirection.Rtl) size.width - 7.dp.toPx() else 7.dp.toPx()
            val trackW = 2.dp.toPx()
            drawRoundRect(
                color = subtle.copy(alpha = if (dragging) 0.24f else 0.12f),
                topLeft = Offset(centerX - trackW / 2f, 0f),
                size = Size(trackW, size.height),
                cornerRadius = CornerRadius(trackW / 2f),
            )

            val thumbW = if (dragging) 8.dp.toPx() else 5.dp.toPx()
            val thumbColor = when {
                dragging -> primary.copy(alpha = 0.92f)
                scrollState.isScrollInProgress -> primary.copy(alpha = 0.72f)
                else -> subtle.copy(alpha = 0.45f)
            }
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(centerX - thumbW / 2f, thumbY),
                size = Size(thumbW, thumbH),
                cornerRadius = CornerRadius(thumbW / 2f),
            )
        }
    }
}
