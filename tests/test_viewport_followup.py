"""Source regression guards; not Android gesture or keyboard tests."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/asoraksh/hermeseditor'
class ViewportFollowup(unittest.TestCase):
    def test_no_resize_scroll_race(self):
        text = (ROOT / 'MainActivity.kt').read_text()
        self.assertNotIn('val oldScroll = editorScroll.value', text)
        self.assertIn('.bringIntoViewRequester(cursorRequester)', text)
        self.assertNotIn('padding(top = 72.dp', text)
    def test_pinch_lifecycle_and_both_modes(self):
        text = (ROOT / 'MainActivity.kt').read_text()
        self.assertEqual(text.count('.documentPinchZoom(zoomDocument, startPinch, endPinch)'), 2)
        self.assertIn('textAnchors = previewTextAnchors', text)
        gesture = (ROOT / 'DocumentPinchZoom.kt').read_text()
        self.assertIn('if (!multiTouch)', gesture)
        self.assertIn('finally', gesture)
    def test_preview_anchors_use_rendered_text(self):
        text = (ROOT / 'MarkdownPreview.kt').read_text()
        self.assertIn('LocalPreviewTextAnchors', text)
        self.assertIn('onTextLayout =', text)
        main = (ROOT / 'MainActivity.kt').read_text()
        self.assertIn('previewTextAnchors.capture(centroid)', main)
    def test_word_centered_independently(self):
        text = (ROOT / 'MainActivity.kt').read_text()
        self.assertEqual(text.count('CenteredActionLabel(stringResource('), 2)
        self.assertIn('label.placeRelative((width - label.width) / 2', text)
if __name__ == '__main__': unittest.main()
