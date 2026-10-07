#!/usr/bin/env python3
"""Median stereo RMS of emulator host output captured after Android effects.

This measures emulator host output after Android's output-mix effects, unlike
session-0 Visualizer. It is not a physical speaker, DAC or acoustic measurement.
"""
import array
import math
from pathlib import Path
import statistics
import struct
import sys
import time


def read_new_wav_frames(path: Path, first_frame: int) -> array.array:
    # QEMU's live WAV has a canonical PCM header, but its RIFF/data lengths are
    # patched only on close. Use the actual complete frames appended to the file.
    with path.open('rb') as source:
        header = source.read(44)
        if len(header) != 44:
            raise ValueError('Emulator output has no complete WAV header')
        fields = struct.unpack('<4sI4s4sIHHIIHH4sI', header)
        if (fields[0], fields[2], fields[3], fields[4], fields[5], fields[6],
                fields[7], fields[8], fields[9], fields[10], fields[11]) != (
                b'RIFF', b'WAVE', b'fmt ', 16, 1, 2, 48000, 192000, 4, 16, b'data'):
            raise ValueError('Expected emulator host PCM16 stereo at 48 kHz')
        end_frame = (path.stat().st_size - 44) // 4
        if not 0 <= first_frame <= end_frame or end_frame - first_frame > 48000 * 10:
            raise ValueError('Emulator host output was truncated or capture exceeded ten seconds')
        source.seek(44 + first_frame * 4)
        payload = source.read((end_frame - first_frame) * 4)
    pcm = array.array('h')
    pcm.frombytes(payload)
    if sys.byteorder != 'little':
        pcm.byteswap()
    return array.array('f', (value / 32768 for value in pcm))


def capture_wav(source: Path, target: Path) -> float:
    first_frame = max(0, (source.stat().st_size - 44) // 4)
    time.sleep(4)
    samples = read_new_wav_frames(source, first_frame)
    if sys.byteorder != 'little':
        samples.byteswap()
    target.write_bytes(samples.tobytes())
    return level_db(target)


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
    if sys.argv[1] == '--capture-wav':
        print(f"{capture_wav(Path(sys.argv[2]), Path(sys.argv[3])):.4f}")
    else:
        print(f"{level_db(Path(sys.argv[1])):.4f}")
