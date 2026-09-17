package com.asoraksh.hermeseditor

fun main() {
    val anchors = PreviewAnchors()
    // Mid-document block: fingers point 40% into the paragraph.
    anchors.update(PreviewBlock(100, 200, 400f, 100f))
    val beforeScroll = 300f
    val fingerY = 140f
    val anchor = anchors.capture(beforeScroll + fingerY, fingerY)
    check(anchor.offset == 140) { "Expected logical offset 140, got ${anchor.offset}" }
    // Zoom increases preceding content and paragraph height.
    anchors.update(PreviewBlock(100, 200, 600f, 200f))
    val newContentY = requireNotNull(anchors.y(anchor.offset))
    val newScroll = newContentY - anchor.screenY
    check(newScroll == 540f)
    check(newContentY - newScroll == fingerY) { "Pinch target moved on screen" }
    // Zoom back out must recover the original scroll, not jump to zero.
    anchors.update(PreviewBlock(100, 200, 400f, 100f))
    check(requireNotNull(anchors.y(anchor.offset)) - anchor.screenY == beforeScroll)
    anchors.clear()
    check(anchors.y(140) == null)
    println("PASS: preview paragraph centroid survives zoom in/out; clear removes stale anchors")
}
