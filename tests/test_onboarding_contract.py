"""Source checks for the localized first-launch guide; not Android UI tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/asoraksh/hermeseditor/MainActivity.kt"
EN = ROOT / "app/src/main/res/values/strings.xml"
FA = ROOT / "app/src/main/res/values-fa/strings.xml"


class OnboardingContract(unittest.TestCase):
    def test_replaces_auto_dismissed_hints_with_four_steps(self):
        code = SOURCE.read_text()
        self.assertNotIn('"zoom_hint_seen"', code)
        self.assertNotIn('"save_hint_seen"', code)
        self.assertIn('onboarding_language_chosen', code)
        self.assertIn('onboarding_complete', code)
        self.assertIn('var lang by remember { mutableStateOf', code)
        self.assertIn('showOnboarding = true', code)
        self.assertIn('OnboardingGuide(', code)
        self.assertIn('onboardingStep >= 3', code)

    def test_guide_is_replayable_and_kept_clear_of_bottom_controls(self):
        code = SOURCE.read_text()
        self.assertIn('onboarding_replay', code)
        self.assertIn('contentAlignment = Alignment.BottomCenter', code)
        self.assertIn('top = 120.dp, end = 24.dp, bottom = 270.dp', code)
        self.assertIn('onDismissRequest = onSkip', code)

    def test_both_languages_include_all_guide_strings(self):
        for resources in (EN.read_text(), FA.read_text()):
            for name in (
                'onboarding_title', 'onboarding_new_title', 'onboarding_new_body',
                'onboarding_save_title', 'onboarding_save_body',
                'onboarding_history_title', 'onboarding_history_body',
                'onboarding_zoom_title', 'onboarding_zoom_body',
                'onboarding_back', 'onboarding_skip', 'onboarding_next',
                'onboarding_done', 'onboarding_replay',
            ):
                self.assertIn(f'name="{name}"', resources)
        self.assertIn('>راهنمای شروع<', FA.read_text())
        self.assertIn('>بازگرداندن<', FA.read_text())
        self.assertIn('>تکرار تغییر<', FA.read_text())


if __name__ == "__main__":
    unittest.main()