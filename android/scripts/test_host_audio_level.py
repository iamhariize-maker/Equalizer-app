import array
import importlib.util
import math
from pathlib import Path
import tempfile
import unittest


class HostAudioLevelTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("host_audio_level", Path(__file__).with_name("host_audio_level.py"))
        self.meter = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.meter)

    def level(self, amplitude, invert_right=False):
        samples = array.array("f")
        for frame in range(48000 * 3):
            value = amplitude * math.sin(2 * math.pi * 1000 * frame / 48000)
            samples.extend((value, -value if invert_right else value))
        with tempfile.NamedTemporaryFile() as f:
            samples.tofile(f)
            f.flush()
            return self.meter.level_db(Path(f.name))

    def test_known_stereo_tone_and_six_db_cut(self):
        flat = self.level(0.25)
        cut = self.level(0.25 * 10 ** (-6 / 20))
        self.assertAlmostEqual(flat, 20 * math.log10(0.25 / math.sqrt(2)), delta=0.001)
        self.assertAlmostEqual(cut - flat, -6, delta=0.001)

    def test_opposite_channels_have_the_same_rms(self):
        self.assertAlmostEqual(self.level(0.25, True), self.level(0.25), delta=0.001)

    def test_silent_capture_is_not_a_successful_measurement(self):
        with tempfile.NamedTemporaryFile() as f:
            array.array("f", [0.0] * (48000 * 3 * 2)).tofile(f)
            f.flush()
            with self.assertRaises(ValueError):
                self.meter.level_db(Path(f.name))

    def test_short_capture_is_rejected(self):
        with tempfile.NamedTemporaryFile() as f:
            array.array("f", [0.25] * 100).tofile(f)
            f.flush()
            with self.assertRaises(ValueError):
                self.meter.level_db(Path(f.name))


if __name__ == "__main__":
    unittest.main()
