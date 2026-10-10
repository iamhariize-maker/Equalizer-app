"""Generate original AOSP-reference WOLA matrices. Requires numpy; no vendor calibration."""
from pathlib import Path
import struct
import numpy as np


def band_stops(block, rate):
    edge = int(np.floor(300 * block / rate))
    return np.unique(np.r_[np.arange(edge + 1), np.rint(
        np.geomspace(edge + 1, block // 2, min(64, 128 - edge - 1))).astype(int)])


def matrices(frequencies, mod_frequencies, block, rate, stops):
    n = np.arange(block)
    hop = block // 2
    count = len(stops)
    window = np.sqrt(.5 * (1 - np.cos(2 * np.pi * n / (block - 1))))
    labels = np.searchsorted(stops, np.minimum(n, block - n))
    labels[hop] = count
    masks = np.eye(count + 1)[labels]
    power = abs(np.fft.fft(window[None, :] * np.exp(
        2j * np.pi * frequencies[:, None] * n[None, :] / rate), axis=1)) ** 2 / (block * hop)
    response = power @ masks
    quadratics = []
    for f in mod_frequencies:
        carrier = np.exp(2j * np.pi * f * n / rate)
        spectrum = np.fft.fft(window * carrier)
        filtered = np.fft.ifft(spectrum[:, None] * masks, axis=0)
        phases = filtered * (window / carrier)[:, None]
        phases = phases[:hop] + phases[hop:]
        delta = phases - phases.mean(axis=0)
        quadratics.append((delta.conj().T @ delta).real / hop)
    return response[:, :-1], response[:, -1], np.asarray(quadratics)


def main():
    root = Path(__file__).resolve().parents[2]
    out = root / "android/app/src/main/assets/lab/models"
    out.mkdir(parents=True, exist_ok=True)
    for rate in [44100, 48000]:
        for block in [2048, 4096, 8192]:
            stops = band_stops(block, rate)
            f = np.unique(np.r_[np.geomspace(20, 300, 220), np.geomspace(300, 20000, 140), 31.5, 60, 100])
            rf = np.geomspace(20, 300, 49)
            matrix, fixed, q = matrices(f, rf, block, rate, stops)
            with (out / f"{rate}_{block}.bin").open("wb") as w:
                w.write(b"DPM1")
                w.write(struct.pack(">iiiii", rate, block, len(stops), len(f), len(rf)))
                w.write(stops.astype(">i4").tobytes())
                for a in [f, matrix, fixed, rf, q]:
                    w.write(a.astype(">f4").tobytes())
            print(rate, block, len(stops), flush=True)


if __name__ == "__main__":
    main()
