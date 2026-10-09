#!/usr/bin/env python3
"""Exercise real native theme selection and capture evidence for visual review.

Usage: appearance_ui.py SERIAL OUTPUT [--production]
Debug verifies saved sound state; production uses only normal user controls.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("serial")
parser.add_argument("output", type=Path)
parser.add_argument("--production", action="store_true")
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
results = []
themes = [("svan_original", "Svan Original"), ("midnight_raga", "Midnight Raga"),
          ("sandstone_atelier", "Sandstone Atelier"), ("indigo_loom", "Indigo Loom")]


def adb(*command, binary=False):
    completed = subprocess.run(["adb", "-s", args.serial, *command],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               timeout=90, check=True)
    return completed.stdout if binary else completed.stdout.decode(errors="replace")


def check(condition, message):
    result = f"{'PASS' if condition else 'FAIL'} {message}"
    results.append(result)
    (args.output / "results.txt").write_text("\n".join(results) + "\n")
    print(result, flush=True)
    if not condition:
        raise AssertionError(message)


def tree():
    for _ in range(4):
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/svan-appearance.xml")
            return ET.fromstring(adb("exec-out", "cat", "/sdcard/svan-appearance.xml"))
        except (ET.ParseError, subprocess.CalledProcessError):
            time.sleep(1)
    raise RuntimeError("No native accessibility hierarchy")


def nodes(root, label):
    return [node for node in root.iter("node")
            if (label in (node.get("text"), node.get("content-desc"))
                or label in node.get("text", "").split("\n"))
            and node.get("bounds") != "[0,0][0,0]"]


def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.6)


def tap(label, last=False):
    found = []
    for attempt in range(5):
        found = nodes(tree(), label)
        if found:
            break
        time.sleep(0.5)
    if not found:
        raise AssertionError(f"Visible control missing: {label}")
    tap_node(found[-1] if last else found[0])


def scroll_top():
    for _ in range(12):
        if nodes(tree(), "Appearance"):
            return
        adb("shell", "input", "swipe", "390", "350", "390", "1300", "300")
    raise AssertionError("Screen header is unreachable")


def section(label):
    root = tree()
    if nodes(root, "All sections"):
        tap("All sections")
    tap(label, last=True)
    scroll_top()


def select_theme(title):
    scroll_top()
    tap("Appearance")
    tap(title)
    tap("Done")


def capture(name):
    time.sleep(0.8)
    root = tree()
    (args.output / f"{name}.xml").write_text(ET.tostring(root, encoding="unicode"))
    (args.output / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    return root


def eq_state():
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", "app.svan/.Command", "--es", "cmd", "state")
    for _ in range(15):
        log = adb("logcat", "-d", "-s", "EqSpike:I")
        found = re.findall(r"EQ_STATE (\{.*\})", log)
        if found:
            return json.loads(found[-1])
        time.sleep(0.5)
    raise AssertionError("Saved sound state was not returned")


try:
    adb("shell", "wm", "size", "780x1688")
    adb("shell", "wm", "density", "320")
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    adb("shell", "am", "force-stop", "app.svan")
    adb("shell", "am", "start", "-n", "app.svan/.MainActivity")
    time.sleep(5)
    # Dismiss the optional explanatory card through its ordinary control.
    if nodes(tree(), "Got it"):
        capture("svan_original-first-run")
        tap("Got it")
    before = None if args.production else eq_state()
    section("Sound")
    for theme_id, title in themes:
        select_theme(title)
        for label in ("Sound", "EQ", "Presets", "Hi-Fi", "Lab"):
            section(label)
            root = capture(f"{theme_id}-{label.lower()}")
            check(bool(nodes(root, "Appearance")), f"{title}: {label} header and theme control visible")
        # The same selected world applies to the separate translucent activity.
        section("Sound")
        dock = [node for node in tree().iter("node")
                if "Tap to open, hold to compare." in node.get("content-desc", "")]
        check(bool(dock), f"{title}: dedicated sound-guide dock available")
        tap_node(dock[-1])
        root = capture(f"{theme_id}-svaresa")
        check(bool(nodes(root, "Done")), f"{title}: Svaresa controls reachable")
        tap("Done")
        if not args.production:
            check(eq_state() == before, f"{title}: changing appearance retains saved sound")

    select_theme("Sandstone Atelier")
    adb("shell", "am", "force-stop", "app.svan")
    adb("shell", "am", "start", "-n", "app.svan/.MainActivity")
    time.sleep(5)
    tap("Appearance")
    root = capture("sandstone-restored-appearance")
    selected_rows = [node for node in root.iter("node")
                     if node.get("checkable") == "true"
                     and (node.get("checked") == "true" or node.get("selected") == "true")]
    check(any(nodes(row, "Sandstone Atelier") for row in selected_rows),
          "Selected appearance survives a cold launch")
    tap("Done")
    adb("shell", "settings", "put", "system", "font_scale", "2.0")
    time.sleep(3)
    for label in ("Sound", "EQ", "Presets", "Hi-Fi", "Lab"):
        section(label)
        root = capture(f"sandstone-large-text-{label.lower()}")
        check(bool(nodes(root, "All sections")), f"Large text: {label} keeps every destination reachable")
    tap("All sections")
    capture("sandstone-large-text-sections")
    tap("Sound", last=True)
    scroll_top()
    dock = [node for node in tree().iter("node")
            if "Tap to open, hold to compare." in node.get("content-desc", "")]
    tap_node(dock[-1])
    root = capture("sandstone-large-text-svaresa")
    check(bool(nodes(root, "Done")) and bool(nodes(root, "Hold to hear the original")),
          "Large text: Svaresa keeps Done and compare reachable")
    tap("Done")
    tap("Appearance")
    capture("sandstone-large-text-appearance")
    tap("Done")
finally:
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    time.sleep(2)
    try:
        section("Sound")
        select_theme("Svan Original")
    except Exception:
        pass
