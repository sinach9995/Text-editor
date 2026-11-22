"""Source regression guards; not Android gesture or keyboard tests."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/asoraksh/hermeseditor'
class ViewportFollowup(unittest.TestCase):
    def test_no_resize_scroll_race(self):
        text = (ROOT / 'MainActivity.kt').read_text(encoding='utf-8')
        self.assertNotIn('val oldScroll = editorScroll.value', text)
        self.assertIn('.bringIntoViewRequester(cursorRequester)', text)
        self.assertNotIn('padding(top = 72.dp', text)
    def test_pinch_lifecycle_and_both_modes(self):
        text = (ROOT / 'MainActivity.kt').read_text(encoding='utf-8')
        self.assertEqual(text.count('.documentPinchZoom(zoomDocument, startPinch, endPinch)'), 2)
        self.assertIn('textAnchors = previewTextAnchors', text)
        gesture = (ROOT / 'DocumentPinchZoom.kt').read_text(encoding='utf-8')
        self.assertIn('if (!multiTouch)', gesture)
        self.assertIn('finally', gesture)
    def test_preview_anchors_use_rendered_text(self):
        text = (ROOT / 'MarkdownPreview.kt').read_text(encoding='utf-8')
        self.assertIn('LocalPreviewTextAnchors', text)
        self.assertIn('onTextLayout =', text)
        main = (ROOT / 'MainActivity.kt').read_text(encoding='utf-8')
        self.assertIn('previewTextAnchors.capture(centroid)', main)
    def test_actions_are_an_overlay_not_a_bottom_bar_child(self):
        # 1.7 removed the Scaffold bottomBar entirely; the whole stack is an
        # overlay in the content box so no window inset can squeeze it.
        text = (ROOT / 'MainActivity.kt').read_text(encoding='utf-8')
        self.assertNotIn('bottomBar = {', text)
        self.assertIn('FloatingActions(', text)
        self.assertIn('Modifier.align(Alignment.BottomEnd)', text)
    def test_pinch_has_exclusive_scroll_ownership(self):
        text = (ROOT / 'MainActivity.kt').read_text(encoding='utf-8')
        self.assertIn('MutatePriority.PreventUserInput', text)
        self.assertIn('lastPinchLayout !== layout', text)
        self.assertIn('cursorVisibilityRequest', text)
        preview = (ROOT / 'PreviewTextAnchors.kt').read_text(encoding='utf-8')
        self.assertIn('correctedLayout === layout', preview)
    def test_corner_buttons_are_glyphs_not_labels(self):
        text = (ROOT / 'MainActivity.kt').read_text(encoding='utf-8')
        controls = (ROOT / 'EditorFloatingControls.kt').read_text(encoding='utf-8')
        self.assertNotIn('CombinedActionLabel', text)
        self.assertIn('.size(DOT)', controls)
        self.assertIn('private val DOT = 46.dp', controls)
        self.assertIn('contentAlignment = Alignment.Center', controls)
        self.assertIn('Canvas(modifier)', controls)
        self.assertIn('CircleShape', controls)
    def test_opposite_side_fast_scrollbar_wired_with_pinch_guard(self):
        main = (ROOT / 'MainActivity.kt').read_text(encoding='utf-8')
        scrollbar_path = ROOT / 'DocumentScrollbar.kt'
        self.assertTrue(scrollbar_path.exists())
        scrollbar = scrollbar_path.read_text(encoding='utf-8')
        self.assertIn('DocumentScrollbar(', main)
        self.assertIn('.align(Alignment.TopStart)', main)
        self.assertIn('event.changes.count { it.pressed } >= 2', scrollbar)
        self.assertTrue((Path(__file__).resolve().parents[1] / 'app/src/test/java/com/asoraksh/hermeseditor/DocumentScrollbarTest.kt').exists())
if __name__ == '__main__': unittest.main()
