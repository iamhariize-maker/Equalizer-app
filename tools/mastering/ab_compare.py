"""Are these two files the same master?  (CD vs hi-res, or any two versions of one recording.)

    python ab_compare.py version_a.wav version_b.wav [--seconds 90]

Steps: read both, bring to a common sample rate, align in time (cross-correlation), match loudness, then
  1. null test: subtract one from the other after the best gain; a tiny residual means the SAME master and the
     difference you hear is the playback chain, the filter, or expectation;
  2. compare dynamics (PLR, DR) and the long-term spectrum (third-octave curve difference, tilt, spine, top-band
     spikiness) to see HOW a different master differs.
Only numbers are printed; nothing is stored. Needs numpy; reads WAV (FLAC with `pip install soundfile`).
"""
import argparse
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import features as F  # noqa: E402

NULL_SAME_DB = -60.0       # residual below this (relative to the signal) = effectively the same master
NULL_CLOSE_DB = -30.0      # between: same recording, light processing difference
PLR_DIFF_DB = 1.0
DR_DIFF_DB = 1.0
SHAPE_RMS_DB = 1.0
MAX_LAG_S = 2.0


def align(a, b, rate):
    """Integer-sample lag of b relative to a (positive: b is late), from mono cross-correlation."""
    ma, mb = a.mean(axis=1), b.mean(axis=1)
    n = int(2 ** np.ceil(np.log2(len(ma) + len(mb))))
    c = np.fft.irfft(np.fft.rfft(ma, n) * np.conj(np.fft.rfft(mb, n)), n)
    lim = int(MAX_LAG_S * rate)
    lags = np.concatenate([np.arange(0, lim), np.arange(-lim, 0)])
    vals = np.concatenate([c[:lim], c[-lim:]])
    return int(lags[np.argmax(vals)] * -1)  # c peaks at lag where a leads b; flip so +lag = b late


def trim(a, b, lag):
    if lag > 0:
        b = b[lag:]
    elif lag < 0:
        a = a[-lag:]
    n = min(len(a), len(b))
    return a[:n], b[:n]


def compare(a, rate_a, b, rate_b):
    rate = min(rate_a, rate_b)
    a, b = F.resample_fft(a, rate_a, rate), F.resample_fft(b, rate_b, rate)
    if a.shape[1] == 1: a = np.repeat(a, 2, axis=1)
    if b.shape[1] == 1: b = np.repeat(b, 2, axis=1)
    lag = align(a, b, rate)
    a, b = trim(a, b, lag)
    fa = F.analyse(a, rate)
    # Loudness-match b to a (gain on b), then a least-squares gain for the null test.
    gain = 10 ** ((fa["loudnessLufs"] - F.analyse(b, rate)["loudnessLufs"]) / 20)
    b_m = b * gain
    fb = F.analyse(b_m, rate)
    k = float(np.sum(a * b) / max(np.sum(b * b), 1e-30))
    resid = a - k * b
    null_db = float(10 * np.log10((np.mean(resid ** 2) + 1e-30) / (np.mean(a ** 2) + 1e-30)))
    shape = np.array(fb["bandDb"]) - np.array(fa["bandDb"])
    ok = (np.array(fa["bandDb"]) > -100) & (np.array(fb["bandDb"]) > -100)
    shape = shape[ok] - np.mean(shape[ok])  # shape only; level is already matched
    delta = {
        "lagSamples": lag, "rate": int(rate), "seconds": a.shape[0] / rate, "levelMatchGainDb": float(20 * np.log10(gain)),
        "nullResidualDb": null_db,
        "plrDb": (fa["plrDb"], fb["plrDb"]), "drTT": (fa["drTT"], fb["drTT"]),
        "tiltDbPerOct": (fa["tiltDbPerOct"], fb["tiltDbPerOct"]), "spineDb": (fa["spineDb"], fb["spineDb"]),
        "hfSpikeFrac": (fa["hfSpikeFrac"], fb["hfSpikeFrac"]), "clipsPerSecond": (fa["clipsPerSecond"], fb["clipsPerSecond"]),
        "sideToMidDb": (fa["sideToMidDb"], fb["sideToMidDb"]),
        "spectrumShapeRmsDb": float(np.sqrt(np.mean(shape ** 2))) if shape.size else 0.0,
    }
    delta["verdict"], delta["reasons"] = verdict(delta)
    return delta


def verdict(d):
    reasons = []
    dyn = abs(d["plrDb"][0] - d["plrDb"][1]) > PLR_DIFF_DB or abs(d["drTT"][0] - d["drTT"][1]) > DR_DIFF_DB
    spec = d["spectrumShapeRmsDb"] > SHAPE_RMS_DB
    if d["nullResidualDb"] < NULL_SAME_DB:
        return "SAME MASTER", ["null residual %.1f dB: the files carry the same signal; any difference you hear comes from the "
                               "playback chain (DAC/filter/codec/resampling), level, or expectation" % d["nullResidualDb"]]
    if dyn:
        reasons.append("dynamics differ (PLR %.1f vs %.1f dB, DR %.1f vs %.1f): one version is more limited" %
                       (*d["plrDb"], *d["drTT"]))
    if spec:
        reasons.append("long-term spectrum differs by %.1f dB RMS after level matching (tilt %+.1f vs %+.1f dB/oct)" %
                       (d["spectrumShapeRmsDb"], *d["tiltDbPerOct"]))
    if reasons:
        return "DIFFERENT MASTERS", reasons
    if d["nullResidualDb"] < NULL_CLOSE_DB:
        return "SAME RECORDING, LIGHT PROCESSING DIFFERENCE", ["null residual %.1f dB with matching dynamics and spectrum" % d["nullResidualDb"]]
    return "INCONCLUSIVE", ["files align poorly (null residual %.1f dB) but dynamics and spectrum match; check they are the same take" % d["nullResidualDb"]]


def render(d):
    t = lambda k, u="": "%s: %.2f -> %.2f %s" % (k, d[k][0], d[k][1], u)
    lines = ["VERDICT: " + d["verdict"]] + ["  - " + r for r in d["reasons"]]
    lines += ["", "aligned %d samples, compared at %d Hz over %.0f s; version B gain-matched by %+.2f dB" %
              (d["lagSamples"], d["rate"], d["seconds"], d["levelMatchGainDb"]),
              "null residual (A - gain*B): %.1f dB" % d["nullResidualDb"],
              t("plrDb", "dB (low = more limited)"), t("drTT", "dB (low = less dynamic)"),
              t("tiltDbPerOct", "dB/oct (higher = brighter)"), t("spineDb", "dB (higher = more body vs top)"),
              t("hfSpikeFrac", "(share of time with top-band spikes)"), t("clipsPerSecond", "clips/s"),
              "spectrum shape difference: %.2f dB RMS" % d["spectrumShapeRmsDb"]]
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("a"); ap.add_argument("b"); ap.add_argument("--seconds", type=float, default=90.0)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    a, ra = F.read_audio(args.a, args.seconds)
    b, rb = F.read_audio(args.b, args.seconds + 2 * MAX_LAG_S)  # a little extra so a small lead-in offset still overlaps
    d = compare(a, ra, b, rb)
    print(json.dumps(d, indent=1) if args.json else render(d))


if __name__ == "__main__":
    main()
