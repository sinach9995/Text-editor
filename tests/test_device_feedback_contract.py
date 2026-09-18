"""Wiring checks, not a replacement for physical IME/gesture tests."""
from pathlib import Path
import unittest
R = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/asoraksh/hermeseditor'
class FeedbackContract(unittest.TestCase):
    def test_pinch_has_centroid(self):
        self.assertIn('calculateCentroid', (R/'DocumentPinchZoom.kt').read_text())
    def test_preview_scroll_owned_by_editor(self):
        self.assertIn('scrollState: ScrollState', (R/'MarkdownPreview.kt').read_text())
    def test_help_and_new_markdown(self):
        text=(R/'MainActivity.kt').read_text()
        self.assertIn('HelpPage', text)
        self.assertIn('onNewMarkdown', text)
        self.assertNotIn('if (showHelp) HelpDialog', text)
    def test_floating_icons(self):
        self.assertTrue((R/'EditorFloatingControls.kt').exists())
if __name__ == '__main__': unittest.main()
