package com.asoraksh.hermeseditor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Single-pointer events pass through. One start/end pair owns each pinch. */
@Composable
fun Modifier.documentPinchZoom(
    onZoom: (Float) -> Unit,
    onStart: (Offset) -> Unit,
    onEnd: () -> Unit
): Modifier {
    val zoomCallback = rememberUpdatedState(onZoom)
    val startCallback = rememberUpdatedState(onStart)
    val endCallback = rememberUpdatedState(onEnd)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var multiTouch = false
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        if (!multiTouch) {
                            multiTouch = true
                            // calculateCentroid ignores newly down pointers; include both
                            // fingers on the arrival frame when capturing the one anchor.
                            val centroid = pressed.fold(Offset.Zero) { sum, change -> sum + change.position } / pressed.size.toFloat()
                            startCallback.value(centroid)
                        }
                        if (event.changes.all { it.pressed && it.previousPressed }) {
                            val zoom = event.calculateZoom()
                            if (zoom.isFinite() && zoom > 0f && zoom != 1f) zoomCallback.value(zoom)
                        }
                    }
                    // Consume the trailing finger too; never click a link after pinching.
                    if (multiTouch) event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            } finally {
                if (multiTouch) endCallback.value()
            }
        }
    }
}
