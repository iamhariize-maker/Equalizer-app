import unittest
from assert_audio_quality_ui import visible_controls


class AudioQualityUiTest(unittest.TestCase):
    def test_first_row_must_be_backing_and_binaural(self):
        good = '<hierarchy><node content-desc="Backing vocals" bounds="[10,100][100,180]"/><node content-desc="Binaural" bounds="[150,100][240,180]"/><node content-desc="Space" bounds="[10,220][100,300]"/></hierarchy>'
        self.assertIn("Binaural", visible_controls(good, 320, 1000))
        with self.assertRaises(AssertionError):
            visible_controls(good.replace("[10,220][100,300]", "[10,20][100,80]"), 320, 1000)

    def test_control_cannot_extend_past_screen(self):
        with self.assertRaises(AssertionError):
            visible_controls('<hierarchy><node content-desc="Resolve" bounds="[280,100][350,180]"/></hierarchy>', 320, 1000)
