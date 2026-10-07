import array
import importlib.util
import math
import struct
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

    def test_live_emulator_wav_uses_only_new_frames_without_final_chunk_sizes(self):
        header = struct.pack('<4sI4s4sIHHIIHH4sI', b'RIFF', 0, b'WAVE', b'fmt ', 16,
                             1, 2, 48000, 192000, 4, 16, b'data', 0)
        pcm = array.array('h', [3276, -3276] * (48000 * 2))
        with tempfile.NamedTemporaryFile() as f:
            f.write(header)
            array.array('h', [0, 0] * 48000).tofile(f)
            pcm.tofile(f)
            f.flush()
            values = self.meter.read_new_wav_frames(Path(f.name), 48000)
        self.assertEqual(len(values), 48000 * 2 * 2)
        self.assertEqual(values[0], 3276 / 32768)
        self.assertEqual(values[1], -3276 / 32768)

    def test_live_emulator_wav_rejects_an_unexpected_format(self):
        header = struct.pack('<4sI4s4sIHHIIHH4sI', b'RIFF', 0, b'WAVE', b'fmt ', 16,
                             1, 2, 44100, 176400, 4, 16, b'data', 0)
        with tempfile.NamedTemporaryFile() as f:
            f.write(header)
            f.flush()
            with self.assertRaises(ValueError):
                self.meter.read_new_wav_frames(Path(f.name), 0)


if __name__ == "__main__":
    unittest.main()
