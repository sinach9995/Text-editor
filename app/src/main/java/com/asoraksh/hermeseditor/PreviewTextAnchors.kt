package com.asoraksh.hermeseditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult

/** Gesture-local rendered character anchor; no proportional source/block estimates. */
class PreviewTextAnchors {
    private data class Entry(val layout: TextLayoutResult, val coordinates: LayoutCoordinates)
    private data class Anchor(val key: Any, val offset: Int, val lineFraction: Float, val screenY: Float)
    private val entries = mutableMapOf<Any, Entry>()
    var viewport: LayoutCoordinates? = null
    var onCorrection: (Float) -> Unit = {}
    private var anchor: Anchor? = null
    private var correctedLayout: TextLayoutResult? = null

    fun remove(key: Any) { entries.remove(key) }
    fun release() { anchor = null }
    fun capture(centroid: Offset): Boolean {
        anchor = null
        val parent = viewport?.takeIf { it.isAttached } ?: return false
        val entry = entries.entries.filter { it.value.coordinates.isAttached }.minByOrNull {
            val origin = parent.localPositionOf(it.value.coordinates, Offset.Zero)
            val bottom = origin.y + it.value.layout.size.height
            when {
                centroid.y < origin.y -> origin.y - centroid.y
                centroid.y > bottom -> centroid.y - bottom
                else -> 0f
            }
        } ?: return false
        val local = entry.value.coordinates.localPositionOf(parent, centroid)
        val layout = entry.value.layout
        val offset = layout.getOffsetForPosition(local)
        val rect = layout.getCursorRect(offset)
        val fraction = ((local.y - rect.top) / rect.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val origin = parent.localPositionOf(entry.value.coordinates, Offset.Zero)
        correctedLayout = layout
        anchor = Anchor(entry.key, offset, fraction, origin.y + rect.top + fraction * rect.height)
        return true
    }
    fun update(key: Any, layout: TextLayoutResult, coordinates: LayoutCoordinates) {
        entries[key] = Entry(layout, coordinates)
        val fixed = anchor ?: return
        val parent = viewport?.takeIf { it.isAttached } ?: return
        if (key != fixed.key || !coordinates.isAttached || correctedLayout === layout) return
        // A scroll-only placement must not feed the same geometry back into the
        // controller. Correct once for each new measured typography layout.
        correctedLayout = layout
        val rect = layout.getCursorRect(fixed.offset.coerceIn(0, layout.layoutInput.text.length))
        val origin = parent.localPositionOf(coordinates, Offset.Zero)
        onCorrection(origin.y + rect.top + fixed.lineFraction * rect.height - fixed.screenY)
    }
}
