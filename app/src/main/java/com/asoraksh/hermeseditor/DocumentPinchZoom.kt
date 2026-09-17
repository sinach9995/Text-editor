package com.asoraksh.hermeseditor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Only document content owns this modifier. Single-pointer events pass through
 * unchanged to Compose scrolling, selection and cursor handlers. Once a second
 * pointer arrives, claim that gesture before child handlers see it; consume its
 * remainder through all pointers lifting so the last finger cannot place a
 * cursor or activate a preview link after a pinch.
 */
@Composable
fun Modifier.documentPinchZoom(onZoom: (Float, Offset) -> Unit): Modifier {
    val currentOnZoom = rememberUpdatedState(onZoom)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var multiTouch = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    multiTouch = true
                    // Ignore arrival/lift frames: compare the same fingers in
                    // both samples, avoiding jumps when pointer count changes.
                    if (event.changes.all { it.pressed && it.previousPressed }) {
                        val zoom = event.calculateZoom()
                        if (zoom.isFinite() && zoom > 0f && zoom != 1f) {
                            currentOnZoom.value(zoom, event.calculateCentroid(useCurrent = false))
                        }
                    }
                }
                if (multiTouch) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}
