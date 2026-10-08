"""Aggregate corpus features into genre target envelopes for Svaramanas.

Input : JSONL from analyze_corpus.py (one object per track, with "genre" and "tier").
Output: targets.json with, per genre and for "all": the median and inter-quartile band of every
        scalar feature and a 30-band median spectrum *relative to the track's own tilt line*, so the
        curve describes shape (what a healthy balance looks like), not level.

Only tracks of tier "reference" (well-mastered, licensed) shape the targets. "weak" tracks are kept
separately so we can measure the gap between good and poor masters.
Usage: python build_targets.py features.jsonl targets.json
"""
import json
import sys
from collections import defaultdict

import numpy as np

SCALARS = ["loudnessLufs", "plrDb", "tiltDbPerOct", "boomDb", "mudDb", "harshDb", "airDb", "correlation",
           "sideToMidDb", "lowSideToMidDb", "spineDb", "hfSpikeFrac", "hfSpikeDb"]
MIN_TRACKS = 20  # below this a genre falls back to "all" rather than inventing a shape


def shape(band_db, centres):
    b = np.asarray(band_db, dtype=float)
    ok = b > -100
    lf = np.log2(centres[ok] / 1000.0)
    slope, icpt = np.polyfit(lf, b[ok], 1)
    rel = np.full(len(b), np.nan)
    rel[ok] = b[ok] - (icpt + slope * lf)
    return rel


def summarise(rows, centres):
    out = {"tracks": len(rows)}
    for k in SCALARS:
        v = np.array([r[k] for r in rows if k in r], dtype=float)
        if len(v):
            q1, med, q3 = np.percentile(v, [25, 50, 75])
            out[k] = {"median": round(float(med), 3), "q1": round(float(q1), 3), "q3": round(float(q3), 3)}
    rel = np.array([shape(r["bandDb"], centres) for r in rows])
    out["shapeDb"] = [None if np.all(np.isnan(c)) else round(float(np.nanmedian(c)), 2) for c in rel.T]
    return out


def main(src, dst):
    sys.path.insert(0, __file__.rsplit("/", 1)[0] if "/" in __file__ else ".")
    from features import BAND_CENTRES
    by_genre, weak = defaultdict(list), []
    for line in open(src):
        r = json.loads(line)
        if not r.get("valid"):
            continue
        (by_genre[r.get("genre", "unknown")] if r.get("tier", "reference") == "reference" else weak).append(r)
    allrows = [r for rows in by_genre.values() for r in rows]
    result = {"version": 1, "bandCentresHz": [round(float(c), 1) for c in BAND_CENTRES],
              "all": summarise(allrows, BAND_CENTRES) if allrows else {}, "genres": {}}
    for g, rows in sorted(by_genre.items()):
        if len(rows) >= MIN_TRACKS:
            result["genres"][g] = summarise(rows, BAND_CENTRES)
    if weak and allrows:
        result["weakVsReference"] = {k: round(float(np.median([r[k] for r in weak if k in r]) - result["all"][k]["median"]), 3)
                                     for k in SCALARS if k in result["all"] and any(k in r for r in weak)}
    json.dump(result, open(dst, "w"), indent=1)
    print(f"{len(allrows)} reference tracks, {len(weak)} weak, {len(result['genres'])} genres -> {dst}")


if __name__ == "__main__":
    main(*sys.argv[1:3])
