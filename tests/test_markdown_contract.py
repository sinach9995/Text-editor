"""Lightweight source-contract checks; not Android runtime/device tests.
Run: python3 -m unittest discover -s /data/workspace/Text-editor/tests -p 'test_markdown_contract.py'
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/asoraksh/hermeseditor'


class MarkdownContractTests(unittest.TestCase):
    def test_source_uses_original_annotations_and_identity_mapping(self):
        path = SOURCE / 'MarkdownSourceStyling.kt'
        self.assertTrue(path.exists(), 'source transformation is missing')
        code = path.read_text()
        self.assertIn('AnnotatedString.Builder(text)', code)
        self.assertIn('OffsetMapping.Identity', code)
        self.assertIn('IncludeSourceSpans.BLOCKS_AND_INLINES', code)
        self.assertIn('activeMatch: TextRange? = null', code)

    def test_preview_scales_document_without_scaling_app_chrome(self):
        code = (SOURCE / 'MarkdownPreview.kt').read_text()
        self.assertIn('fontSize: Float = 17f', code)
        self.assertIn('fontSize / 17f', code)
        self.assertIn('stringResource(R.string.preview_empty)', code)
        self.assertNotIn('"Nothing to preview."', code)
        self.assertIn('remember(node, linkColor, codeBg, scale)', code)
        self.assertIn('fontSize = 15.sp * scale', code)
        self.assertIn('fontSize = 14.sp * scale', code)
        self.assertIn('pinchScale: Float = 1f', code)
        self.assertIn('.graphicsLayer {', code)
        # The temporary gesture layer belongs to the Markdown document; the
        # application top and bottom bars stay outside MarkdownPreview.
        activity = (SOURCE / 'MainActivity.kt').read_text()
        chrome = activity.split('topBar = {', 1)[1].split('contentWindowInsets', 1)[0]
        self.assertNotIn('.graphicsLayer {', chrome)


if __name__ == '__main__':
    unittest.main()
