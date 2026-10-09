#!/usr/bin/env python3
"""Measures what Android's DynamicsProcessing and Visualizer do, on the CI emulator. Research, not a gate.

The debug app plays known signals through a DynamicsProcessing it owns (app/.../diag/EffectsLab.kt) and records the
Visualizer's readings. This script finds each segment in the emulator's host capture of the output, measures it, and
sets the result beside what the framework's own source predicts (AOSP android-14-release, dynamicsproc DPFrequency
and EffectVisualizer). Measurements and predictions are printed as INFO lines. Only the infrastructure checks
(program ran, capture held every segment, signal reached the output, Visualizer answered) are PASS or FAIL.

Usage: system_effects_lab.py <adb-serial> <output-dir>
       system_effects_lab.py --selftest
"""
import array
import json
import math
import os
import struct
import subprocess
import sys
import tempfile
import time
from pathlib import Path

RATE = 48000
BANDS = 128
CENTERS = [20.0 * (20000.0 / 20.0) ** (i / (BANDS - 1)) for i in range(BANDS)]
CUTOFFS = [math.sqrt(CENTERS[i] * CENTERS[i + 1]) if i + 1 < BANDS else 22000.0 for i in range(BANDS)]
WIN = 480                 # 10 ms silence/activity windows
STEADY = (0.45, 0.8)      # fraction of a segment used for its steady level
VISUALIZER_MS = (1100, 2300)  # steady part of a 2 s tone that starts after the 0.6 s lead-in
EXPECTED_RUNS = 11
EXPECTED_SEGMENTS = 50
LEAD_S = 0.6
TAIL_S = 0.6


def to_db(value):
    return 20.0 * math.log10(max(value, 1e-12))


def band_for(hz):
    """The band whose centre is nearest hz on the log scale (EffectsLab.bandFor)."""
    return min(BANDS - 1, max(0, int(math.floor(127.0 * math.log10(hz / 20.0) / 3.0 + 0.5))))


def aosp_bin_gains(block, boosted_band, boost_db):
    """Per-bin gain the framework applies to one boosted pre-EQ band.

    DPFrequency::ChannelBuffer::computeBinStartStop sets binStop = (int)(0.5 + cutoff * block / rate), and each band
    fills the bins from the previous band's binStop + 1 up to its own binStop. A band whose binStop does not pass the
    previous one fills no bin, so its gain is never applied. Bins that no band reaches keep unity here (an assumption:
    the source leaves them as they were)."""
    half = block // 2 + 1
    factor = [1.0] * half
    boost = 10.0 ** (boost_db / 20.0)
    next_bin = 0
    dropped = []
    for band in range(BANDS):
        stop = int(0.5 + CUTOFFS[band] * block / RATE)
        if stop < next_bin:
            dropped.append(band)
        gain = boost if band == boosted_band else 1.0
        for k in range(next_bin, min(stop, half - 1) + 1):
            factor[k] = gain
        next_bin = stop + 1
    return factor, dropped


def hann_power_kernel(d):
    """Power response of a Hann window at d bins from a sinusoid's frequency: |sin(pi d) / (pi d (1 - d^2))|^2."""
    if abs(d) < 1e-9:
        return 1.0
    if abs(abs(d) - 1.0) < 1e-9:
        return 0.25
    return (math.sin(math.pi * d) / (math.pi * d * (1.0 - d * d))) ** 2


def predicted_tone_db(block, hz, boosted_band, boost_db):
    """Framework-model gain for a steady tone at hz: (nearest-bin dB, Hann-weighted dB, bands that get no bins).

    The Hann-weighted value averages the bin gains by the power kernel a steady sinusoid leaks into each bin; the
    nearest-bin value is the crude upper bound on how sharp the bin mapping can be."""
    factor, dropped = aosp_bin_gains(block, boosted_band, boost_db)
    centre = hz * block / RATE
    nearest = min(len(factor) - 1, int(math.floor(centre + 0.5)))
    weights = []
    gains = []
    for k in range(max(0, int(centre) - 8), min(len(factor) - 1, int(centre) + 9) + 1):
        w = hann_power_kernel(k - centre)
        weights.append(w)
        gains.append(factor[k] * factor[k])
    weighted = 10.0 * math.log10(sum(w * g for w, g in zip(weights, gains)) / sum(weights))
    return to_db(factor[nearest]), weighted, dropped


def predicted_static_gain_db(amp, threshold_db, ratio, detector="block"):
    """Steady gain of a compressor band for a sine of peak amplitude amp.

    The framework's detector is a block energy. For a sine with a Hann-like window that reads about 0.578 * amp
    (an estimate from the window constants, not measured here); 'rms' uses the sine's RMS for comparison."""
    env = to_db(0.578 * amp) if detector == "block" else to_db(amp / math.sqrt(2.0))
    if env <= threshold_db:
        return 0.0
    return (env + (1.0 / ratio - 1.0) * (env - threshold_db)) - env


def predicted_limited_amp(amp, threshold_db=-0.5, ratio=10.0):
    """Steady output peak after the framework's limiter, from the same block-energy detector (no hop lag)."""
    env = to_db(0.578 * amp)
    if env <= threshold_db:
        return amp
    return amp * 10.0 ** (((1.0 / ratio) - 1.0) * (env - threshold_db) / 20.0)


def read_host_mono(path, first_frame):
    """Mono (L+R)/2 of the frames the emulator appended after first_frame. No length limit, unlike capture_wav."""
    with path.open("rb") as source:
        header = source.read(44)
        if len(header) != 44:
            raise ValueError("Emulator output has no complete WAV header")
        fields = struct.unpack("<4sI4s4sIHHIIHH4sI", header)
        if (fields[0], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7], fields[8],
                fields[9], fields[10], fields[11]) != (b"RIFF", b"WAVE", b"fmt ", 16, 1, 2, 48000, 192000, 4, 16, b"data"):
            raise ValueError("Expected emulator host PCM16 stereo at 48 kHz")
        end_frame = (path.stat().st_size - 44) // 4
        if not 0 <= first_frame <= end_frame:
            raise ValueError("Capture start is beyond the emulator output")
        source.seek(44 + first_frame * 4)
        payload = source.read((end_frame - first_frame) * 4)
    pcm = array.array("h")
    pcm.frombytes(payload)
    if sys.byteorder != "little":
        pcm.byteswap()
    return array.array("f", ((left + right) / 65536.0 for left, right in zip(pcm[0::2], pcm[1::2])))


def regions(mono, min_gap_s=0.5, threshold=1e-4):
    """Sample spans of signal, separated by at least min_gap_s of silence (the lead-in and tail are longer)."""
    count = len(mono) // WIN
    active = []
    for w in range(count):
        s = w * WIN
        energy = sum(x * x for x in mono[s:s + WIN])
        active.append(math.sqrt(energy / WIN) > threshold)
    runs = []
    w = 0
    while w < count:
        if not active[w]:
            w += 1
            continue
        start = w
        while w < count and active[w]:
            w += 1
        runs.append([start, w])
    gap = int(min_gap_s * RATE / WIN)
    merged = []
    for a, b in runs:
        if merged and a - merged[-1][1] < gap:
            merged[-1][1] = b
        else:
            merged.append([a, b])
    return [(a * WIN, b * WIN) for a, b in merged]


def rms(samples):
    return math.sqrt(sum(x * x for x in samples) / len(samples)) if len(samples) else 0.0


def steady_db(mono, span):
    start, end = span
    length = end - start
    return to_db(rms(mono[start + int(STEADY[0] * length):start + int(STEADY[1] * length)]))


def visualizer_mean(readings):
    values = [db for t, db in readings if VISUALIZER_MS[0] <= t <= VISUALIZER_MS[1] and db > -90.0]
    return sum(values) / len(values) if values else None


def rise_time_ms(mono, span, final_db, ref_db, window=762):
    """Time from the start of the span until the trailing one-period RMS has moved 63% of the way to its settled gain.

    One period of 63 Hz is about 762 samples, so the RMS of a pure tone over that window is level within a fraction
    of a percent whatever the phase."""
    start, end = span
    target = 0.63 * final_db
    for s in range(start + window, end, RATE // 1000):
        if to_db(rms(mono[s - window:s])) - ref_db <= target:
            return (s - start) * 1000.0 / RATE
    return None


def adb(serial, *args, timeout=100):
    return subprocess.check_output(["adb", "-s", serial, *args], timeout=timeout).decode().strip()


def command(serial, cmd, *extra):
    adb(serial, "shell", "am", "start", "-W", "-n", "app.svan/.Command", "--es", "cmd", cmd, *extra)


def wait_for_result(serial, limit):
    deadline = time.monotonic() + limit
    while time.monotonic() < deadline:
        proc = subprocess.run(["adb", "-s", serial, "shell", "run-as", "app.svan", "cat", "files/effects-lab.json"],
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=100)
        if proc.returncode == 0 and proc.stdout:
            try:
                data = json.loads(proc.stdout)
            except json.JSONDecodeError:
                data = None
            if data and data.get("done"):
                return data
        time.sleep(2)
    raise AssertionError("effects lab did not finish")


def analyse(data, mono, out_dir):
    spans = regions(mono)
    segments = [(run, seg) for run in data["runs"] for seg in run["segments"]]
    checks = []
    lines = []

    def info(text):
        lines.append("INFO " + text)
        print(lines[-1], flush=True)

    def check(name, ok):
        checks.append((name, bool(ok)))
        lines.append(("PASS " if ok else "FAIL ") + name)
        print(lines[-1], flush=True)

    check("effects lab completed every run", data.get("done") and not data.get("errors")
          and len(data["runs"]) == EXPECTED_RUNS and len(segments) == EXPECTED_SEGMENTS)
    check("host capture holds every programmed segment", len(spans) == len(segments))
    check("signal reached the host output", spans and max(steady_db(mono, s) for s in spans) > -60.0)

    summary = {"regions": len(spans), "segments": len(segments), "runs": {}}
    index = 0
    for run in data["runs"]:
        count = len(run["segments"])
        if index + count > len(spans):
            info(f"{run['label']}: no capture for these segments")
            index += count
            continue
        mapped = list(zip(run["segments"], spans[index:index + count]))
        index += count
        label = run["label"]
        block = round(run["blockMs"] * 48)
        if label == "visualizer_tap":
            (flat, fs), (boost, bs) = mapped
            host = steady_db(mono, bs) - steady_db(mono, fs)
            vis = [visualizer_mean(s["visualizer"]) for s in (flat, boost)]
            k = band_for(1000.0)
            pred, pred_weighted, _ = predicted_tone_db(block, 1000.0, k, 12.0)
            vis_delta = (vis[1] - vis[0]) if None not in vis else None
            vis_text = "no reading" if vis_delta is None else f"{vis_delta:+.2f} dB"
            info(f"visualizer_tap: host output change at 1 kHz {host:+.2f} dB (framework model {pred:+.2f}); "
                 f"Visualizer change {vis_text} (an input tap ahead of DynamicsProcessing predicts +0 dB, "
                 f"an output tap predicts {host:+.2f} dB)")
            check("Visualizer answered during the tap run", vis_delta is not None)
            summary["runs"][label] = {"host_delta_db": host, "visualizer_delta_db": vis_delta, "model_db": pred}
        elif label.startswith("bass_block_"):
            per_band = []
            for (flat, fs), (boost, bs) in zip(mapped[0::2], mapped[1::2]):
                hz = boost["hz"]
                measured = steady_db(mono, bs) - steady_db(mono, fs)
                boosted = band_for(hz)
                pred, pred_weighted, dropped = predicted_tone_db(block, hz, boosted, 6.0)
                lost = boosted in dropped
                per_band.append({"hz": hz, "measured_db": measured, "model_nearest_db": pred,
                                 "model_hann_db": pred_weighted, "band_dropped": lost})
                info(f"block {block} {hz:.0f} Hz: measured {measured:+.2f} dB, framework model {pred_weighted:+.2f} dB "
                     f"(nearest bin {pred:+.2f}), band {boosted} {'gets no bins (its gain is lost)' if lost else 'applied'}")
            below = [b for b in range(BANDS) if CUTOFFS[b] < 200.0]
            _, dropped_all = aosp_bin_gains(block, -1, 0.0)
            info(f"block {block}: {len([b for b in dropped_all if b in below])} of {len(below)} bands below 200 Hz get no bins")
            summary["runs"][label] = per_band
        elif label.startswith("clip_") or label.startswith("transient_"):
            (seg, span), = mapped
            region = mono[span[0]:span[1]]
            peak = max(abs(x) for x in region)
            clipped = sum(1 for x in region if abs(x) >= 0.9999) / len(region)
            amp = 0.9 * 10.0 ** (12.0 / 20.0)
            model = {"clip_limiter_on": predicted_limited_amp(amp), "clip_limiter_off": amp,
                     "clip_headroom_minus12": predicted_limited_amp(0.9), "transient_limiter_on": predicted_limited_amp(amp)}[label]
            info(f"{label}: output peak {peak:.4f} ({to_db(peak):+.2f} dBFS), {clipped * 100:.1f}% of samples at full scale; "
                 f"framework model steady peak {model:.2f} (full scale is 1.0)")
            summary["runs"][label] = {"peak": peak, "clipped_fraction": clipped, "model_peak": model}
        elif label.startswith("mbc_attack_"):
            (ref, rs), (comp, cs) = mapped
            ref_db = steady_db(mono, rs)
            static = steady_db(mono, cs) - ref_db
            detector_block = predicted_static_gain_db(0.1, -30.0, 4.0, "block")
            detector_rms = predicted_static_gain_db(0.1, -30.0, 4.0, "rms")
            rise = rise_time_ms(mono, cs, static, ref_db)
            info(f"{label}: steady gain {static:+.2f} dB (model {detector_block:+.2f} with the block detector, "
                 f"{detector_rms:+.2f} with an RMS detector); 63% of it reached {rise if rise is None else round(rise, 1)} ms "
                 f"after the output starts")
            summary["runs"][label] = {"static_db": static, "model_block_db": detector_block,
                                      "model_rms_db": detector_rms, "rise63_ms": rise}
        else:
            info(f"{label}: no analysis defined")
    results = {"checks": checks, "summary": summary}
    (out_dir / "results.json").write_text(json.dumps(results, indent=2))
    (out_dir / "results.txt").write_text("\n".join(lines) + "\n")
    return all(ok for _, ok in checks)


def write_wav(path, frames):
    payload = array.array("h", frames).tobytes()
    header = struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", 36 + len(payload), b"WAVE", b"fmt ", 16, 1, 2, RATE,
                         RATE * 4, 4, 16, b"data", len(payload))
    path.write_bytes(header + payload)


def selftest():
    """Offline checks of the analysis code: region finding, the bin model's dropped bands, and gain reading."""
    tone = [int(10000 * math.sin(2 * math.pi * 63.0 * n / RATE)) for n in range(int(1.6 * RATE))]
    stereo = []
    gains = [1.0, 2.0, 0.5]
    for g in gains:
        for s in tone:
            stereo += [int(s * g), int(s * g)]
        stereo += [0] * (2 * int(1.2 * RATE))
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "host.wav"
        write_wav(path, stereo)
        mono = read_host_mono(path, 0)
    spans = regions(mono)
    assert len(spans) == 3, f"expected 3 regions, got {len(spans)}"
    readings = [steady_db(mono, s) for s in spans]
    for measured, gain in zip(readings[1:], gains[1:]):
        assert abs((measured - readings[0]) - to_db(gain)) < 0.05, (measured, gain)
    gapped = regions(array.array("f", [0.5] * 480 * 10 + [0.0] * int(0.2 * RATE) + [0.5] * 480 * 10))
    assert len(gapped) == 1, "a 0.2 s gap must not split a segment"
    assert band_for(1000.0) == 72 and band_for(20.0) == 0 and band_for(20000.0) == 127
    for block in (2048, 4096, 8192, 16384):
        for hz in (25.0, 40.0, 63.0, 160.0):
            nearest, weighted, dropped = predicted_tone_db(block, hz, band_for(hz), 6.0)
            print(f"model block {block:5d} {hz:6.1f} Hz: +6 dB band -> {weighted:+.2f} dB Hann-weighted "
                  f"(nearest bin {nearest:+.2f})" + ('  (band gets no bins)' if band_for(hz) in dropped else ''))
    lost_4096 = predicted_tone_db(4096, 25.0, band_for(25.0), 6.0)
    assert band_for(25.0) in lost_4096[2], "a 25 Hz band should get no bins at block 4096"
    assert abs(hann_power_kernel(1.0) - 0.25) < 1e-12 and abs(hann_power_kernel(0.0) - 1.0) < 1e-12
    assert abs(hann_power_kernel(1.0 - 1e-7) - 0.25) < 1e-3
    assert abs(predicted_static_gain_db(0.1, -30.0, 4.0, "block") + 3.94) < 0.05
    assert predicted_limited_amp(0.9) == 0.9 and predicted_limited_amp(3.58) < 3.58
    print("selftest passed")


def main(argv):
    if argv[1:2] == ["--selftest"]:
        selftest()
        return 0
    serial, out_dir = argv[1], Path(argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    wav = Path(os.environ["QEMU_WAV_PATH"])
    # Quiet the emulator: no other test audio, full media volume for the clipping runs, and the Visualizer may read.
    adb(serial, "shell", "pm", "grant", "app.svan", "android.permission.RECORD_AUDIO")
    for pkg in ("app.svan.testsource.capturable", "app.svan.testsource.blocked"):
        adb(serial, "shell", "am", "force-stop", pkg)
    command(serial, "stop_capture")
    command(serial, "stop_system")
    time.sleep(2)
    adb(serial, "shell", "cmd", "media_session", "volume", "--stream", "3", "--set", "15")
    first_frame = max(0, (wav.stat().st_size - 44) // 4)
    command(serial, "effects_lab")
    try:
        data = wait_for_result(serial, limit=1200)
        time.sleep(3)
        mono = read_host_mono(wav, first_frame)
        (out_dir / "effects-lab.json").write_text(json.dumps(data, indent=2))
        analyse(data, mono, out_dir)
    finally:
        adb(serial, "shell", "cmd", "media_session", "volume", "--stream", "3", "--set", "4")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
