"""Check visible setup controls in emulator UIAutomator output."""
import re
import sys
from xml.etree import ElementTree


def assert_screen(root, fixture, density, height):
    nodes = list(root.iter("node"))
    parents = {child: parent for parent in root.iter() for child in parent}

    def node(text):
        found = next((n for n in nodes if n.get("text") == text), None)
        assert found is not None, f"{fixture}: missing {text}"
        return found

    def control(text):
        # Compose exposes enabled text inside disabled clickable parents. A
        # paragraph can also repeat a button's label; search all occurrences.
        for label in nodes:
            if label.get("text") != text:
                continue
            current = label
            while current in parents:
                current = parents[current]
                if current.get("clickable") == "true":
                    return current, label
        raise AssertionError(f"{fixture}: missing control {text}")

    def bounds(n):
        values = list(map(int, re.findall(r"-?\d+", n.get("bounds", ""))))
        assert len(values) == 4, f"{fixture}: missing bounds"
        return values

    banner = bounds(node("UI test fixture · not live detection"))
    button, label = control("Back to Svan")
    back = bounds(button)
    text = bounds(label)
    assert 0 <= back[1] < back[3] <= height, f"{fixture}: footer is clipped"
    assert back[3] - back[1] >= 36 * density / 160, f"{fixture}: Back to Svan is clipped"
    assert back[1] <= text[1] < text[3] < back[3], f"{fixture}: footer text is clipped"
    if fixture.startswith("status-"):
        assert bounds(node("Is it working?"))[1] >= banner[3], f"{fixture}: heading overlaps fixture banner"
    if fixture == "error":
        assert control("Retry")[0].get("enabled") == "false", f"{fixture}: fixture retry must be disabled"
    if fixture == "working":
        assert control("Enabling detection…")[0].get("enabled") == "false", f"{fixture}: fixture progress must be disabled"


if __name__ == "__main__":
    assert_screen(ElementTree.parse(sys.argv[1]).getroot(), sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
