package com.asoraksh.hermeseditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentAnchorsTest {

    @Test
    fun `preview paragraph centroid survives zoom in and out and clear removes anchors`() {
        val anchors = PreviewAnchors()
        anchors.update(PreviewBlock(100, 200, 400f, 100f))
        val beforeScroll = 300f
        val fingerY = 140f
        val anchor = anchors.capture(beforeScroll + fingerY, fingerY)
        assertEquals(140, anchor.offset)

        anchors.update(PreviewBlock(100, 200, 600f, 200f))
        val newContentY = requireNotNull(anchors.y(anchor.offset))
        val newScroll = newContentY - anchor.screenY
        assertEquals(540f, newScroll, 0.001f)
        assertEquals(fingerY, newContentY - newScroll, 0.001f)

        anchors.update(PreviewBlock(100, 200, 400f, 100f))
        assertEquals(beforeScroll, requireNotNull(anchors.y(anchor.offset)) - anchor.screenY, 0.001f)

        anchors.clear()
        assertNull(anchors.y(140))
    }
}
