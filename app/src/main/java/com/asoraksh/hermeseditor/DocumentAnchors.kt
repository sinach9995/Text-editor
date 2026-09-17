package com.asoraksh.hermeseditor

/** Source offset plus desired viewport Y; never a pixel offset shared between modes. */
data class DocumentAnchor(val offset: Int, val screenY: Float)

data class PreviewBlock(val start: Int, val end: Int, val top: Float, val height: Float)

class PreviewAnchors {
    private val blocks = mutableMapOf<Int, PreviewBlock>()
    fun clear() = blocks.clear()
    fun update(block: PreviewBlock) { blocks[block.start] = block }
    fun capture(contentY: Float, screenY: Float): DocumentAnchor {
        val ordered = blocks.values.sortedBy { it.top }
        val block = ordered.lastOrNull { it.top <= contentY } ?: ordered.firstOrNull()
            ?: return DocumentAnchor(0, screenY)
        val fraction = ((contentY - block.top) / block.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return DocumentAnchor(block.start + ((block.end - block.start) * fraction).toInt(), screenY)
    }
    fun y(offset: Int): Float? {
        val ordered = blocks.values.sortedBy { it.start }
        val block = ordered.lastOrNull { it.start <= offset } ?: ordered.firstOrNull() ?: return null
        val fraction = ((offset - block.start).toFloat() / (block.end - block.start).coerceAtLeast(1)).coerceIn(0f, 1f)
        return block.top + fraction * block.height
    }
}
