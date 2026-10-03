"""Builds the Svan श mark: glyph outline from an OFL font, fitted into the
icon's safe zone, with an optional full-width shirorekha (headline bar)."""
import math, sys
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.recordingPen import RecordingPen

def glyph_path(font_file, char="श", box=(54, 54), height=34.0, wght=None):
    t = TTFont(font_file)
    gs = t.getGlyphSet(location={"wght": wght} if wght else None)
    name = t.getBestCmap()[ord(char)]
    bp = BoundsPen(gs); gs[name].draw(bp)
    x0, y0, x1, y1 = bp.bounds
    s = height / (y1 - y0)
    cx, cy = box
    # font units (y up) -> icon units (y down), centred on the box
    tx = cx - (x0 + x1) / 2 * s
    ty = cy + (y0 + y1) / 2 * s
    rec = RecordingPen(); gs[name].draw(TransformPen(rec, (s, 0, 0, -s, tx, ty)))
    pen = SVGPathPen(None); rec.replay(pen)
    w = (x1 - x0) * s
    return pen.getCommands(), (cx - w / 2, cy - height / 2, cx + w / 2, cy + height / 2)

def headline(bounds, thick=3.6, overhang=2.2):
    x0, y0, x1, _ = bounds
    r = thick / 2
    a, b, y = x0 - overhang, x1 + overhang, y0
    return (f"M{a+r:.2f},{y:.2f} L{b-r:.2f},{y:.2f} A{r:.2f},{r:.2f} 0 0,1 {b-r:.2f},{y+thick:.2f} "
            f"L{a+r:.2f},{y+thick:.2f} A{r:.2f},{r:.2f} 0 0,1 {a+r:.2f},{y:.2f} Z")

def ring_marks():
    pts = []
    for k in range(9):
        a = math.radians(-90 + 40 * k)
        pts.append(f"M{54+31.5*math.cos(a):.2f},{54+31.5*math.sin(a):.2f} L{54+29*math.cos(a):.2f},{54+29*math.sin(a):.2f}")
    return " ".join(pts)

if __name__ == "__main__":
    font, out, with_head = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
    d, b = glyph_path(font)
    head = headline(b) if with_head else ""
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="216" height="216" viewBox="0 0 108 108">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#F3D58F"/><stop offset="0.55" stop-color="#D9A84E"/><stop offset="1" stop-color="#9A6B2A"/></linearGradient></defs>
<rect width="108" height="108" fill="#0C0A08"/>
<circle cx="54" cy="54" r="29" fill="none" stroke="#D9A84E" stroke-width="1.8"/>
<circle cx="54" cy="54" r="24.5" fill="none" stroke="#9A6B2A" stroke-width="0.9"/>
<path d="{ring_marks()}" stroke="#9A6B2A" stroke-width="1" stroke-linecap="round"/>
<path d="{d}" fill="url(#g)"/>
<path d="{head}" fill="url(#g)"/>
</svg>'''
    open(out, "w").write(svg)
