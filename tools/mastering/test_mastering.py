"""Run: python3 -I -m unittest test_mastering   (from tools/mastering; numpy only)"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import features  # noqa: E402

RATE = 48000


def colored(seconds, slope_db_oct, seed=0):
    """Stereo noise whose third-octave band levels fall slope_db_oct per octave (the analyser's tilt).
    Band power = PSD x bandwidth (+3 dB/oct), so the PSD slope is slope_db_oct - 3."""
    rng = np.random.default_rng(seed)
    n = int(seconds * RATE)
    out = []
    for _ in range(2):
        spec = np.fft.rfft(rng.standard_normal(n))
        f = np.maximum(np.fft.rfftfreq(n, 1 / RATE), 20.0)
        spec *= (f / 1000.0) ** ((slope_db_oct - 3.0) / 6.0206)  # amplitude exponent = PSD slope / 6.02
        x = np.fft.irfft(spec, n)
        out.append(x / np.max(np.abs(x)) * 0.5)
    return np.stack(out, axis=1)


def add_hf_clicks(x, level, every_s=0.25):
    y = x.copy()
    hp = np.zeros(RATE // 100)
    hp[0], hp[1] = 1, -1  # crude high-passed click
    for t in np.arange(0.1, len(x) / RATE - 0.1, every_s):
        i = int(t * RATE)
        y[i:i + len(hp), :] += level * hp[:, None]
    return y


class FeatureTests(unittest.TestCase):
    def test_tilt_follows_spectrum_slope(self):
        for slope in (-3.0, -6.0):
            f = features.analyse(colored(8, slope), RATE)
            self.assertAlmostEqual(f["tiltDbPerOct"], slope, delta=0.8)

    def test_brighter_material_has_lower_spine_and_higher_tilt(self):
        g = features.analyse(colored(10, -5.0, 1), RATE)
        a = features.analyse(colored(10, -2.0, 1), RATE)
        self.assertGreater(g["spineDb"], a["spineDb"] + 3.0)
        self.assertGreater(a["tiltDbPerOct"], g["tiltDbPerOct"])

    def test_added_top_end_spikes_are_seen_against_the_same_material(self):
        base = colored(10, -3.5, 5)
        b, c = features.analyse(base, RATE), features.analyse(add_hf_clicks(base, 0.6), RATE)
        self.assertGreater(c["hfSpikeFrac"], b["hfSpikeFrac"] + 0.0005)  # spikes are brief: a fraction of a percent of the time
        self.assertGreater(c["hfSpikeDb"], b["hfSpikeDb"])

    def test_plr_separates_limited_from_dynamic(self):
        x = colored(8, -4.0, 2)
        limited = np.clip(x * 6.0, -1.0, 1.0)
        self.assertLess(features.analyse(limited, RATE)["plrDb"], features.analyse(x, RATE)["plrDb"] - 3.0)
        self.assertGreater(features.analyse(limited, RATE)["clipsPerSecond"], 1.0)

    def test_stereo_measures(self):
        mono = np.repeat(colored(6, -3.0, 3)[:, :1], 2, axis=1)
        f = features.analyse(mono, RATE)
        self.assertGreater(f["correlation"], 0.999)
        self.assertLess(f["sideToMidDb"], -60)
        wide = colored(6, -3.0, 4)
        self.assertLess(features.analyse(wide, RATE)["correlation"], 0.2)

    def test_short_audio_is_not_trusted(self):
        self.assertFalse(features.analyse(colored(0.5, -3.0), RATE)["valid"])

    def test_wav_round_trip_and_cli_pipeline(self):
        with tempfile.TemporaryDirectory() as d:
            for genre, slope, n in (("calm", -5.0, 22), ("bright", -2.0, 22)):
                os.makedirs(os.path.join(d, "corpus", genre))
                for k in range(n):
                    features.write_wav(os.path.join(d, "corpus", genre, f"{k}.wav"), colored(3, slope + 0.05 * k, k), RATE)
            os.makedirs(os.path.join(d, "corpus", "_weak", "calm"))
            features.write_wav(os.path.join(d, "corpus", "_weak", "calm", "w.wav"), add_hf_clicks(colored(3, -2.0, 99), 0.7), RATE)
            here = os.path.dirname(os.path.abspath(__file__))
            jl, tj = os.path.join(d, "f.jsonl"), os.path.join(d, "t.json")
            subprocess.run([sys.executable, "-I", os.path.join(here, "analyze_corpus.py"), os.path.join(d, "corpus"), jl], check=True, capture_output=True)
            subprocess.run([sys.executable, "-I", os.path.join(here, "build_targets.py"), jl, tj], check=True, capture_output=True)
            t = json.load(open(tj))
            self.assertEqual(sorted(t["genres"]), ["bright", "calm"])
            self.assertGreater(t["genres"]["bright"]["tiltDbPerOct"]["median"], t["genres"]["calm"]["tiltDbPerOct"]["median"] + 1.5)
            self.assertEqual(len(t["genres"]["calm"]["shapeDb"]), 30)
            self.assertIn("hfSpikeFrac", t["weakVsReference"])
            self.assertNotIn("file", open(jl).readline())  # no paths leak into the dataset


if __name__ == "__main__":
    unittest.main()
