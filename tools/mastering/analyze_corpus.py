"""Walk a folder of audio and write one feature record per track (features only, never audio).

Layout (the genre is the first sub-folder, the tier is optional):
    corpus/rock/*.wav            -> genre "rock", tier "reference"
    corpus/_weak/rock/*.wav      -> genre "rock", tier "weak"   (poorly mastered examples, for the gap)
Usage: python analyze_corpus.py corpus features.jsonl [--seconds 120]
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from features import analyse_file  # noqa: E402

EXT = (".wav", ".flac", ".ogg", ".mp3")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root"); ap.add_argument("out"); ap.add_argument("--seconds", type=float, default=120.0)
    a = ap.parse_args()
    n = bad = 0
    with open(a.out, "w") as out:
        for dirpath, _, files in os.walk(a.root):
            rel = os.path.relpath(dirpath, a.root).split(os.sep)
            tier = "weak" if rel[0] == "_weak" else "reference"
            genre = (rel[1] if tier == "weak" and len(rel) > 1 else rel[0]) if rel != ["."] else "unknown"
            for fn in sorted(files):
                if not fn.lower().endswith(EXT):
                    continue
                try:
                    d = analyse_file(os.path.join(dirpath, fn), a.seconds)
                except Exception as e:  # keep going: one corrupt file must not stop a corpus run
                    bad += 1
                    print(f"skip {fn}: {e}", file=sys.stderr)
                    continue
                d.update(genre=genre, tier=tier)
                d.pop("file", None)  # no paths in the dataset; the corpus stays on the maker's PC
                out.write(json.dumps(d) + "\n")
                n += 1
    print(f"{n} tracks analysed, {bad} skipped -> {a.out}")


if __name__ == "__main__":
    main()
