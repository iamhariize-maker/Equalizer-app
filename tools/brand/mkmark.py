"""Builds the Svan mark: text shaped with HarfBuzz (so conjuncts like स्व join
correctly), outlined from an OFL font, fitted into the icon's yantra ring, with
an optional continuous shirorekha (headline bar)."""
import math

import uharfbuzz as hb
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont


def _shaped(font_file, text, wght=None):
    """Glyph names and pen offsets (font units) for `text`, shaped like Android would."""
    blob = hb.Blob.from_file_path(font_file)
    face = hb.Face(blob)
    font = hb.Font(face)
    if wght:
        font.set_variations({"wght": wght})
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(font, buf, {})
    tt = TTFont(font_file)
    order = tt.getGlyphOrder()
    out, x = [], 0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        out.append((order[info.codepoint], x + pos.x_offset, pos.y_offset))
        x += pos.x_advance
    return tt, out


def glyph_path(font_file, text="स्व", box=(54, 54), height=34.0, max_width=None, wght=None):
    tt, glyphs = _shaped(font_file, text, wght)
    gs = tt.getGlyphSet(location={"wght": wght} if wght else None)
    rec = RecordingPen()
    for name, dx, dy in glyphs:
        gs[name].draw(TransformPen(rec, (1, 0, 0, 1, dx, dy)))
    bp = BoundsPen(None)
    rec.replay(bp)
    x0, y0, x1, y1 = bp.bounds
    s = height / (y1 - y0)
    if max_width and (x1 - x0) * s > max_width:
        s = max_width / (x1 - x0)
    cx, cy = box
    tx = cx - (x0 + x1) / 2 * s
    ty = cy + (y0 + y1) / 2 * s
    # 1 decimal (0.1 of a 108-unit viewport, ~0.2 px on a 192 px icon) keeps the path short.
    pen = SVGPathPen(None, ntos=lambda v: f"{v:.1f}".rstrip("0").rstrip("."))
    rec.replay(TransformPen(pen, (s, 0, 0, -s, tx, ty)))
    w, h = (x1 - x0) * s, (y1 - y0) * s
    return pen.getCommands(), (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)


def headline(bounds, thick=3.6, overhang=2.2):
    x0, y0, x1, _ = bounds
    r = thick / 2
    a, b, y = x0 - overhang, x1 + overhang, y0
    return (f"M{a+r:.1f},{y:.1f} L{b-r:.1f},{y:.1f} A{r:.1f},{r:.1f} 0 0,1 {b-r:.1f},{y+thick:.1f} "
            f"L{a+r:.1f},{y+thick:.1f} A{r:.1f},{r:.1f} 0 0,1 {a+r:.1f},{y:.1f} Z")


def ring_marks():
    pts = []
    for k in range(9):
        a = math.radians(-90 + 40 * k)
        pts.append(f"M{54+31.5*math.cos(a):.2f},{54+31.5*math.sin(a):.2f} L{54+29*math.cos(a):.2f},{54+29*math.sin(a):.2f}")
    return " ".join(pts)
