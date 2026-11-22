package com.asoraksh.hermeseditor

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentScrollbarTest {

    @Test
    fun `thumbHeightPx returns zero when content fits viewport or geometry is invalid`() {
        assertEquals(0f, ScrollbarMath.thumbHeightPx(trackHeight = 1000f, viewportHeight = 800f, maxScroll = 0, minThumbPx = 88f))
        assertEquals(0f, ScrollbarMath.thumbHeightPx(trackHeight = 0f, viewportHeight = 800f, maxScroll = 1000, minThumbPx = 88f))
        assertEquals(0f, ScrollbarMath.thumbHeightPx(trackHeight = 1000f, viewportHeight = 0f, maxScroll = 1000, minThumbPx = 88f))
    }

    @Test
    fun `thumbHeightPx respects minThumbPx floor on huge documents and caps at trackHeight`() {
        val tinyThumb = ScrollbarMath.thumbHeightPx(trackHeight = 1000f, viewportHeight = 800f, maxScroll = 500_000, minThumbPx = 96f)
        assertEquals(96f, tinyThumb)

        val shortTrackThumb = ScrollbarMath.thumbHeightPx(trackHeight = 60f, viewportHeight = 800f, maxScroll = 500_000, minThumbPx = 96f)
        assertEquals(60f, shortTrackThumb)

        val halfThumb = ScrollbarMath.thumbHeightPx(trackHeight = 1000f, viewportHeight = 1000f, maxScroll = 1000, minThumbPx = 96f)
        assertEquals(500f, halfThumb, 0.01f)
    }

    @Test
    fun `thumbOffsetPx maps scroll range from top edge to bottom travel limit`() {
        assertEquals(0f, ScrollbarMath.thumbOffsetPx(1000f, 100f, scrollValue = 0, maxScroll = 4000))
        assertEquals(450f, ScrollbarMath.thumbOffsetPx(1000f, 100f, scrollValue = 2000, maxScroll = 4000), 0.01f)
        assertEquals(900f, ScrollbarMath.thumbOffsetPx(1000f, 100f, scrollValue = 4000, maxScroll = 4000), 0.01f)
    }

    @Test
    fun `scrollValueForThumbTop and scrollValueForY clamp cleanly and round-trip`() {
        val track = 1000f
        val thumb = 100f
        val max = 9000

        assertEquals(0, ScrollbarMath.scrollValueForThumbTop(-50f, track, thumb, max))
        assertEquals(max, ScrollbarMath.scrollValueForThumbTop(1200f, track, thumb, max))
        assertEquals(4500, ScrollbarMath.scrollValueForThumbTop(450f, track, thumb, max))

        assertEquals(0, ScrollbarMath.scrollValueForY(-20f, track, thumb, max))
        assertEquals(4500, ScrollbarMath.scrollValueForY(500f, track, thumb, max))
        assertEquals(max, ScrollbarMath.scrollValueForY(1000f, track, thumb, max))

        val offset = ScrollbarMath.thumbOffsetPx(track, thumb, 6300, max)
        assertEquals(6300, ScrollbarMath.scrollValueForThumbTop(offset, track, thumb, max))
    }
}
