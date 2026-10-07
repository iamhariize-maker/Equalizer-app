"""Validate visible accessibility controls and first-row order from emulator XML."""
import re
import xml.etree.ElementTree as ET


def visible_controls(xml, width, height):
    controls = {}
    for node in ET.fromstring(xml).iter("node"):
        label = node.attrib.get("content-desc", "") or node.attrib.get("text", "")
        bounds = [int(x) for x in re.findall(r"-?\d+", node.attrib.get("bounds", ""))]
        if len(bounds) != 4:
            continue
        x1, y1, x2, y2 = bounds
        if x2 <= x1 or y2 <= y1 or y1 >= height or y2 <= 0:
            continue
        for key in ("Backing vocals", "Binaural", "Space", "Instruments", "Resolve", "Svaresa manages Resolve"):
            if label == key or label.startswith(key + "\n") or label.startswith(key + ","):
                assert 0 <= x1 < x2 <= width, (label, bounds)
                if 0 <= y1 < y2 <= height:
                    controls[key] = bounds
    if all(key in controls for key in ("Backing vocals", "Binaural")):
        assert abs(controls["Backing vocals"][1] - controls["Binaural"][1]) <= 3
        assert controls["Backing vocals"][2] <= controls["Binaural"][0]
        for key in ("Space", "Instruments"):
            if key in controls:
                assert controls[key][1] > controls["Backing vocals"][1], (key, controls)
    return controls


if __name__ == "__main__":
    import pathlib
    import sys
    print("\n".join(visible_controls(pathlib.Path(sys.argv[1]).read_text(), int(sys.argv[2]), int(sys.argv[3]))))
