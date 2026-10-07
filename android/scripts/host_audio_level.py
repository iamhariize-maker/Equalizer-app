#!/usr/bin/env python3
"""Median stereo RMS of a 48 kHz float32le PulseAudio monitor capture.

This measures emulator host output after Android's output-mix effects, unlike
session-0 Visualizer. It is not a physical speaker, DAC or acoustic measurement.
"""
import array
import math
from pathlib import Path
import statistics
import sys


def level_db(path: Path) -> float:
    samples = array.array("f")
    payload = path.read_bytes()
    if len(payload) % 8:
        raise ValueError("Incomplete stereo float32 PCM frames")
    samples.frombytes(payload)
    if sys.byteorder != "little":
        samples.byteswap()
    rate, channels = 48000, 2
    if len(samples) < rate * channels * 2:
        raise ValueError("Host capture shorter than two seconds")
    # Ignore stream start-up; evaluate complete 200 ms blocks from the second second.
    chunk = rate * channels // 5
    levels = []
    for start in range(rate * channels, len(samples) - chunk + 1, chunk):
        block = samples[start:start + chunk]
        if any(not math.isfinite(value) for value in block):
            raise ValueError("Non-finite host PCM")
        rms = math.sqrt(sum(value * value for value in block) / chunk)
        levels.append(20 * math.log10(max(rms, 1e-15)))
    result = statistics.median(levels)
    if result <= -90:
        raise ValueError("No usable host output signal above -90 dBFS")
    return result


if __name__ == "__main__":
    print(f"{level_db(Path(sys.argv[1])):.4f}")
