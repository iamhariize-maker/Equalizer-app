"""Offline numerical reference; NOT a ready-to-ship Android processor.

Dry-plus-delta WOLA demonstrates the stereo design and its exact neutral path.
Output is latency-aligned offline. Streaming integration must report/delay N
frames, preallocate everything, and implement the remaining design gates.
No stem classifier, true-peak governor, binaural renderer, or HRTF is included.
"""
import math
import numpy as np


def window_size(fs):
    return 1 << math.ceil(math.log2(fs * 1024 / 48000))


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def quadratic_scale(pm, ps, pd, q):
    limit = min(.5 * pm, ps * 10 ** .4)
    if pm <= 1e-20 or pd <= 1e-30 or ps >= limit:
        return 0.
    if ps + 2*q + pd <= limit:
        return 1.
    slack = limit - ps
    disc = math.sqrt(max(0., q*q + pd*slack))
    root = slack / (disc + q) if q >= 0 else (disc - q) / pd
    return float(np.clip(root, 0, 1))


def render_delta_wola(audio, fs, transform):
    """transform(M, S, Hz, hopSeconds) -> complex side delta for one frame."""
    audio = np.asarray(audio, dtype=np.float64)
    assert audio.ndim == 2 and audio.shape[1] == 2
    assert np.isfinite(audio).all() and fs > 0
    n = window_size(fs)
    hop = n // 4
    w = np.sqrt(.5 - .5 * np.cos(2*np.pi*np.arange(n)/n))
    padded = np.pad(audio, ((n, n), (0, 0)))
    delta = np.zeros(len(padded))
    norm = np.zeros(len(padded))
    hz = np.fft.rfftfreq(n, 1/fs)
    for start in range(0, len(padded)-n+1, hop):
        frame = padded[start:start+n] * w[:, None]
        m = np.fft.rfft((frame[:, 0]+frame[:, 1])*.5)
        s = np.fft.rfft((frame[:, 0]-frame[:, 1])*.5)
        d = np.asarray(transform(m, s, hz, hop/fs), dtype=np.complex128)
        assert d.shape == s.shape and np.isfinite(d).all()
        # DC and Nyquist cannot carry an arbitrary complex phase.
        d[0] = d[-1] = 0
        delta[start:start+n] += np.fft.irfft(d, n=n) * w
        norm[start:start+n] += w*w
    sl = slice(n, n+len(audio))
    assert np.all(norm[sl] > 1e-12)
    d = delta[sl] / norm[sl]
    out = audio.copy()  # Identity does not depend on FFT round-trip error.
    out[:, 0] += d
    out[:, 1] -= d
    return out


class ResidualDetail:
    """Conservative residual estimator, not a backing-vocal separator.

    Time/frequency masks and budgets are starting design values, not listening
    results. Apply after the legacy side-only allpass path has been removed.
    """
    def __init__(self, backing=0., binaural=0.):
        self.backing = float(np.clip(backing, 0, 1))
        self.binaural = float(np.clip(binaural, 0, 1))
        self.pm = self.ps = self.sm = None
        self.elapsed = 0.

    def __call__(self, m, s, hz, dt):
        if self.pm is None:
            self.pm = np.zeros_like(hz)
            self.ps = np.zeros_like(hz)
            self.sm = np.zeros_like(m)
        a = math.exp(-dt/.150)
        self.pm = a*self.pm + (1-a)*np.abs(m)**2
        self.ps = a*self.ps + (1-a)*np.abs(s)**2
        self.sm = a*self.sm + (1-a)*s*np.conj(m)
        self.elapsed += dt
        if self.elapsed < .2 or self.backing+self.binaural == 0:
            return np.zeros_like(s)
        coherence = np.abs(self.sm)**2 / np.maximum(self.pm*self.ps, 1e-30)
        eligible = (self.pm > 1e-12) & (self.ps > 1e-12) & (coherence < .98) & (self.ps < .5*self.pm)
        beta = self.sm / np.maximum(self.pm + 1e-12*(self.pm+self.ps), 1e-30)
        residual = s - beta*m
        vocal = smoothstep(250, 450, hz) * (1-smoothstep(3500, 6000, hz))
        # Include lower-mid body; do not make 'detail' just a treble shelf.
        space = smoothstep(180, 350, hz) * (1-.7*smoothstep(4000, 12000, hz))
        space *= 1-smoothstep(12000, min(18000, hz[-1]), hz)
        db = np.minimum(4., 4*self.backing*vocal + 3*self.binaural*space)
        d = (10**(db/20)-1) * residual * eligible
        groups = np.floor(21.4*np.log10(1+.00437*hz)).astype(int)
        for group in np.unique(groups):
            j = groups == group
            pm, ps, pd = (float(np.vdot(x[j], x[j]).real) for x in (m, s, d))
            q = float(np.vdot(s[j], d[j]).real)
            d[j] *= quadratic_scale(pm, ps, pd, q)
        return d
