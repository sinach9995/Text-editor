"""Source wiring guards only; these do not substitute for touch/device tests."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/asoraksh/hermeseditor'

class PinchWiring(unittest.TestCase):
    def test_document_only_zoom_is_connected_and_persisted(self):
        activity = (SOURCE / 'MainActivity.kt').read_text()
        self.assertEqual(activity.count('.documentPinchZoom('), 2)
        self.assertIn('putFloat("document_font_size", fontSize)', activity)
        self.assertIn('fontSize = fontSize,', activity)
        self.assertIn('coerceIn(13f, 30f)', activity)

    def test_single_finger_is_not_consumed_before_multitouch(self):
        source = (SOURCE / 'DocumentPinchZoom.kt').read_text()
        self.assertIn('PointerEventPass.Initial', source)
        self.assertIn('pressed.size >= 2', source)
        self.assertIn('it.previousPressed', source)
        self.assertIn('if (multiTouch)', source)
        self.assertIn('rememberUpdatedState(onZoom)', source)
        self.assertIn('pointerInput(Unit)', source)

if __name__ == '__main__':
    unittest.main()
