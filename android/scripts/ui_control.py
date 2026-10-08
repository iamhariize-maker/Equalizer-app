"""Find an enabled, visible control or the already-selected navigation item."""
import re
import sys
from xml.etree import ElementTree


def bounds(node):
    values = list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))
    return values if len(values) == 4 else None


def control_point(root, text):
    parents = {child: parent for parent in root.iter() for child in parent}
    for label in root.iter("node"):
        if label.get("text") != text:
            continue
        chain = [label]
        while chain[-1] in parents:
            chain.append(parents[chain[-1]])
        # Compose removes the click action from the already-selected tab.
        # It remains a valid navigation target; tapping it is a harmless no-op.
        control = next((n for n in chain if n.get("clickable") == "true"
                        or n.get("selected") == "true"), None)
        if control is None or any(n.get("enabled") == "false" for n in chain):
            continue
        rect = bounds(label)
        if rect is None:
            continue
        # Compose can retain a label outside the scroll viewport. Its clickable
        # ancestor and the enclosing viewport must share a visible tap point.
        for node in chain:
            clip = bounds(node)
            if clip is not None:
                rect = [max(rect[0], clip[0]), max(rect[1], clip[1]),
                        min(rect[2], clip[2]), min(rect[3], clip[3])]
        if rect[0] < rect[2] and rect[1] < rect[3]:
            return (rect[0] + rect[2]) // 2, (rect[1] + rect[3]) // 2
    raise ValueError(f"enabled visible control missing: {text}")


if __name__ == "__main__":
    try:
        print(*control_point(ElementTree.parse(sys.argv[1]).getroot(), sys.argv[2]))
    except (ValueError, ElementTree.ParseError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
