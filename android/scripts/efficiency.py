#!/usr/bin/env python3
"""Observe lifecycle scheduling on the real Android app; no battery-percentage claim.

Receipts are read with run-as while Svan stays in the background. Reading a receipt
must not bring the Activity forward and invalidate the observation.
"""
import json
import subprocess
import sys
import time
from pathlib import Path

serial, directory = sys.argv[1:3]
out = Path(directory)
out.mkdir(parents=True, exist_ok=True)
results = []


def adb(*args):
    return subprocess.check_output(["adb", "-s", serial, *args], timeout=60).decode(errors="replace")


def command(name, *args):
    adb("shell", "am", "start", "-W", "-n", "app.svan/.Command", "--es", "cmd", name, *args)


def check(ok, text):
    result = ("PASS " if ok else "FAIL ") + text
    results.append(result)
    (out / "results.txt").write_text("\n".join(results) + "\n")
    print(result, flush=True)
    if not ok:
        raise AssertionError(text)


def observe(name, seconds, warmup, hidden):
    command("efficiency_watch", "--ei", "seconds", str(seconds), "--ei", "warmup", str(warmup))
    if hidden:
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
    deadline = time.monotonic() + seconds + warmup + 30
    while time.monotonic() < deadline:
        read = subprocess.run(["adb", "-s", serial, "shell", "run-as", "app.svan", "cat",
                               "files/efficiency-watch-end.json"], capture_output=True, timeout=60)
        if read.returncode == 0:
            try:
                end = json.loads(read.stdout)
                break
            except json.JSONDecodeError:
                pass
        time.sleep(1)
    else:
        raise AssertionError("Watch receipt missing: " + name)
    start = json.loads(adb("shell", "run-as", "app.svan", "cat", "files/efficiency-watch-start.json"))
    delta = {key: end[key] - start[key] for key in ("uiPolls", "plannerRequests", "plannerRuns",
                                                  "savedEqWrites", "detectionScans", "curveRevision", "elapsedMs")}
    (out / (name + ".json")).write_text(json.dumps({"start": start, "end": end, "delta": delta}, indent=2))
    return start, end, delta


try:
    command("stop_capture")
    command("stop_system")
    command("reset_sound")
    command("mix_fallback", "--ez", "on", "false")
    command("shared_output", "--ez", "on", "false")
    adb("shell", "pm", "revoke", "app.svan", "android.permission.DUMP")
    command("start_system")
    start, end, hidden = observe("hidden-idle", 32, 15, True)
    check(hidden["uiPolls"] == 0, "hidden Activity performs zero UI polls")
    check(hidden["plannerRequests"] == hidden["plannerRuns"] == 0, "disabled smart controller performs zero periodic planning")
    check(hidden["savedEqWrites"] == 0, "idle does not rewrite saved EQ")
    check(0 <= hidden["detectionScans"] <= 2, "idle watchdog stays at thirty seconds after recovery settles")
    check(start["settings"] == end["settings"] and start["eq"] == end["eq"], "idle optimization preserves saved sound and quality settings")
    _, _, visible = observe("visible", 8, 3, False)
    check(visible["uiPolls"] >= 5, "foreground UI polling resumes")
    command("svaramanas", "--ez", "on", "true", "--es", "mode", "SVARESA")
    start, end, smart = observe("hidden-smart", 10, 6, True)
    check(smart["uiPolls"] == 0, "smart processing does not restart hidden UI polling")
    check(2 <= smart["plannerRuns"] <= 5, "enabled smart processing retains three-second planning")
    check(smart["savedEqWrites"] == 0, "live smart updates do not rewrite persisted EQ")
    check(start["settings"] == end["settings"] and start["quality"] == end["quality"], "smart optimization keeps the chosen audio quality")
    command("svaramanas", "--ez", "on", "false")
finally:
    (out / "logcat.txt").write_text(adb("logcat", "-d"))
