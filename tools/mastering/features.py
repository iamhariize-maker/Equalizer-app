"""Offline feature extraction for the Svan mastering-reference corpus.

numpy only. Mirrors what Svaramanas hears on the phone (core/include/eqcore/analyzer.h) and adds the
three "grounding" measures defined in docs/SONIC_IDENTITY.md:

  spine_db     body (100 Hz-1 kHz) power minus top-band (>3.5 kHz) power. High = grounded, low = airy/thin.
  hf_spike_frac  share of time the top band's fast level exceeds its own slow level by more than the
                 Grounding threshold (5 dB): how spiky the top end is.
  hf_spike_db    mean excess over that threshold while spiking.

Reading: WAV (PCM 16/24/32, float32) natively. FLAC/MP3 need `soundfile` or a prior ffmpeg conversion
(`ffmpeg -i in.flac -ac 2 out.wav`). Only features are ever stored, never audio.
"""
import json
import struct
import wave

import numpy as np

BAND_CENTRES = 1000.0 * 2.0 ** (np.arange(-16, 14) / 3.0)  # 30 third-octaves, ~25 Hz .. 20 kHz
BODY_HZ = (100.0, 1000.0)
TOP_HZ = 3500.0
SPIKE_THRESHOLD_DB = 5.0   # keep equal to kCrestThresholdDb in core/src/grounding.cpp


def read_audio(path, max_seconds=120.0):
    """Return (samples[n, ch] float64 in -1..1, rate). Takes the middle `max_seconds` of long files."""
    if str(path).lower().endswith((".flac", ".ogg", ".mp3")):
        try:
            import soundfile as sf
        except ImportError as e:  # pragma: no cover
            raise RuntimeError("install soundfile or convert to WAV first (ffmpeg -i in.flac out.wav)") from e
        info = sf.info(path)
        n = int(max_seconds * info.samplerate)
        start = max(0, (info.frames - n) // 2)
        x, rate = sf.read(path, start=start, frames=n, dtype="float64", always_2d=True)
        return x, rate
    with wave.open(str(path), "rb") as w:
        ch, width, rate, frames = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        n = min(frames, int(max_seconds * rate))
        w.setpos(max(0, (frames - n) // 2))
        raw = w.readframes(n)
    if width == 2:
        x = np.frombuffer(raw, "<i2").astype(np.float64) / 32768.0
    elif width == 3:
        b = np.frombuffer(raw, np.uint8).reshape(-1, 3)
        v = (b[:, 0].astype(np.int32) | (b[:, 1].astype(np.int32) << 8) | (b[:, 2].astype(np.int32) << 16))
        v = np.where(v & 0x800000, v - 0x1000000, v)
        x = v.astype(np.float64) / 8388608.0
    elif width == 4:
        x = np.frombuffer(raw, "<i4").astype(np.float64) / 2147483648.0
    else:
        raise ValueError(f"unsupported sample width {width}")
    return x.reshape(-1, ch), rate


def write_wav(path, x, rate):
    """16-bit PCM writer for tests and demos."""
    x = np.atleast_2d(x.T).T if x.ndim == 1 else x
    pcm = np.clip(x, -1, 1) * 32767.0
    with wave.open(str(path), "wb") as w:
        w.setnchannels(x.shape[1]); w.setsampwidth(2); w.setframerate(rate)
        w.writeframes(np.round(pcm).astype("<i2").tobytes())


def _k_weight_db(f):
    """Analytic approximation of the BS.1770 K-weighting magnitude (shelf ~+4 dB above 1.7 kHz, 38 Hz HP)."""
    f = np.maximum(f, 1e-3)
    shelf = 10 * np.log10((1 + (f / 1681.0) ** 2 * 1.585 ** 2) / (1 + (f / 1681.0) ** 2))  # +4 dB top
    hp = 10 * np.log10((f / 38.0) ** 4 / (1 + (f / 38.0) ** 4))
    return shelf + hp


def _band_edges(rate):
    edges = BAND_CENTRES * 2.0 ** (-1.0 / 6.0)
    return np.append(edges, BAND_CENTRES[-1] * 2.0 ** (1.0 / 6.0))


def _boxcar(x, n):
    c = np.cumsum(np.concatenate(([0.0], x)))
    n = max(1, int(n))
    y = (c[n:] - c[:-n]) / n
    return y


def analyse(x, rate):
    """Feature dict for one excerpt (samples [n, ch])."""
    x = np.asarray(x, dtype=np.float64)
    if x.shape[1] == 1:
        x = np.repeat(x, 2, axis=1)
    L, R = x[:, 0], x[:, 1]
    mid, side = 0.5 * (L + R), 0.5 * (L - R)
    mono = mid
    n = len(mono)
    out = {"rate": int(rate), "seconds": n / rate}
    if n < rate:  # too short to say anything
        out["valid"] = False
        return out
    out["valid"] = True

    # --- spectrum: Hann STFT, long-term average power per third-octave band (relative dB) ---
    nfft, hop = 4096, 2048
    win = np.hanning(nfft)
    nwin = 1 + (n - nfft) // hop
    idx = np.arange(nfft)[None, :] + hop * np.arange(nwin)[:, None]
    def spec(sig):
        return (np.abs(np.fft.rfft(sig[idx] * win, axis=1)) ** 2).mean(axis=0) / np.sum(win ** 2)
    pm, ps = spec(mid), spec(side)
    f = np.fft.rfftfreq(nfft, 1 / rate)
    edges = _band_edges(rate)
    bands = np.full(len(BAND_CENTRES), -120.0)
    for i in range(len(BAND_CENTRES)):
        m = (f >= edges[i]) & (f < edges[i + 1])
        if m.any() and edges[i] < 0.5 * rate:
            bands[i] = 10 * np.log10(pm[m].sum() + ps[m].sum() + 1e-30)
    out["bandDb"] = [round(float(v), 2) for v in bands]

    # --- loudness (approximate K-weighted, ungated) and dynamics ---
    kw = 10 ** (_k_weight_db(np.fft.rfftfreq(n, 1 / rate)) / 20)
    lw = np.fft.irfft(np.fft.rfft(L) * kw, n)
    rw = np.fft.irfft(np.fft.rfft(R) * kw, n)
    out["loudnessLufs"] = float(-0.691 + 10 * np.log10(np.mean(lw ** 2) + np.mean(rw ** 2) + 1e-30))
    peak = float(np.max(np.abs(x)))
    out["peakDbfs"] = 20 * np.log10(peak + 1e-30)
    out["plrDb"] = out["peakDbfs"] - out["loudnessLufs"]
    out["clipsPerSecond"] = float(np.sum(np.abs(x) >= 0.9999) / (n / rate))

    # --- stereo ---
    out["correlation"] = float(np.corrcoef(L, R)[0, 1]) if np.std(L) > 0 and np.std(R) > 0 else 1.0
    out["sideToMidDb"] = float(10 * np.log10((np.mean(side ** 2) + 1e-30) / (np.mean(mid ** 2) + 1e-30)))
    low = f < 120.0
    out["lowSideToMidDb"] = float(10 * np.log10((ps[low].sum() + 1e-30) / (pm[low].sum() + 1e-30)))

    # --- tonal balance: tilt and local excesses (same definitions as analyzer.h) ---
    valid = bands > -100
    lf = np.log2(BAND_CENTRES[valid] / 1000.0)
    slope, icpt = np.polyfit(lf, bands[valid], 1)
    out["tiltDbPerOct"] = float(slope)
    def excess(lo, hi):
        sel = valid & (BAND_CENTRES >= lo) & (BAND_CENTRES <= hi)
        if not sel.any():
            return 0.0
        line = icpt + slope * np.log2(BAND_CENTRES[sel] / 1000.0)
        return float(np.mean(bands[sel] - line))
    out["boomDb"], out["mudDb"], out["harshDb"], out["airDb"] = excess(63, 125), excess(200, 500), excess(2500, 5000), excess(10000, 16000)
    live = np.where(bands > np.max(bands) - 60)[0]
    out["cutoffHz"] = float(BAND_CENTRES[live.max()]) if len(live) else 0.0

    # --- grounding measures ---
    spec_m = np.fft.rfft(mono)
    fm = np.fft.rfftfreq(n, 1 / rate)
    body = np.fft.irfft(spec_m * ((fm >= BODY_HZ[0]) & (fm <= BODY_HZ[1])), n)
    top = np.fft.irfft(spec_m * (fm >= TOP_HZ), n)
    out["spineDb"] = float(10 * np.log10((np.mean(body ** 2) + 1e-30) / (np.mean(top ** 2) + 1e-30)))
    fast = _boxcar(top ** 2, 0.0006 * rate)          # ~ the 0.3 ms detector time constant
    slow = _boxcar(top ** 2, 0.080 * rate)           # ~ the 40 ms time constant
    m = min(len(fast), len(slow)) 
    fast, slow = fast[len(fast) - m:], slow[len(slow) - m:]
    active = slow > 1e-8
    crest = 10 * np.log10((fast + 1e-30) / (slow + 1e-30))
    ex = crest[active] - SPIKE_THRESHOLD_DB
    out["hfSpikeFrac"] = float(np.mean(ex > 0)) if len(ex) else 0.0
    out["hfSpikeDb"] = float(np.mean(ex[ex > 0])) if len(ex) and np.any(ex > 0) else 0.0
    return out


def analyse_file(path, max_seconds=120.0):
    x, rate = read_audio(path, max_seconds)
    d = analyse(x, rate)
    d["file"] = str(path)
    return d


if __name__ == "__main__":  # python features.py a.wav b.wav
    import sys
    for p in sys.argv[1:]:
        print(json.dumps(analyse_file(p)))
