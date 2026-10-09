#!/usr/bin/env python3
"""Check the native appearance palettes' functional text and control contrast."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "android/app/src/main/java/app/svan/ui/AppearancePalette.kt"


def luminance(color):
    linear = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in color]
    return sum(a * b for a, b in zip(linear, (0.2126, 0.7152, 0.0722)))


def contrast(a, b):
    low, high = sorted((luminance(a), luminance(b)))
    return (high + 0.05) / (low + 0.05)


rows = []
for name, body in re.findall(r"private val (\w+) = AppearancePalette\((.*?)\n\)", SOURCE.read_text(), re.S):
    colors = {key: [int(value[i:i + 2], 16) / 255 for i in (0, 2, 4)]
              for key, value in re.findall(r"(\w+) = Color\(0xFF([0-9A-F]{6})\)", body)}

    def record(fg, bg, a, b, minimum):
        ratio = contrast(a, b)
        rows.append(dict(theme=name, foreground=fg, background=bg,
                         ratio=round(ratio, 2), minimum=minimum, passed=ratio >= minimum))

    for fg in ("text", "secondary", "muted", "accent", "highlight", "depth", "warning",
               "neutral", "live", "air", "space", "voice", "learned"):
        for bg in ("background", "surface", "raised", "tonal"):
            record(fg, bg, colors[fg], colors[bg], 4.5)
    for bg in ("background", "surface", "raised", "tonal"):
        record("control outline", bg, colors["border"], colors[bg], 3)
    for bg in ("accent", "highlight", "depth"):
        record("onAccent", bg, colors["onAccent"], colors[bg], 4.5)
    for bg in ("background", "surface", "raised"):
        selected = [0.16 * a + 0.84 * b for a, b in zip(colors["accent"], colors[bg])]
        record("selected pill", f"16% accent over {bg}", colors["accent"], selected, 4.5)

failed = [row for row in rows if not row["passed"]]
report = dict(result="FAIL" if failed else "PASS", pairs=rows)
(ROOT / "docs/appearance-contrast.json").write_text(json.dumps(report, indent=2) + "\n")
print(f"{report['result']}: {len(rows)} actual native contrast pairs; {len(failed)} failures")
for row in failed:
    print(row)
raise SystemExit(bool(failed))
