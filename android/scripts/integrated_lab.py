#!/usr/bin/env python3
"""Synthetic, DUMP-free session churn and integrated Lab runtime checks. No commercial-player claim."""
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

serial, output = sys.argv[1:3]
out = Path(output)
out.mkdir(parents=True, exist_ok=True)
player = "app.svan.testsource.capturable"
results = []


def adb(*args):
    return subprocess.check_output(["adb", "-s", serial, *args], timeout=60).decode(errors="replace")


def command(name, *args):
    adb("shell", "am", "start", "-W", "-n", "app.svan/.Command", "--es", "cmd", name, *args)


def status(kind):
    path = "files/" + kind.replace("_", "-") + "-status.json"
    # onNewIntent can finish after am start -W. Require a fresh complete receipt.
    adb("shell", "run-as", "app.svan", "rm", "-f", path)
    command(kind + "_status")
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        receipt = subprocess.run(["adb", "-s", serial, "shell", "run-as", "app.svan", "cat", path],
                                 stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=60)
        if receipt.returncode == 0:
            try:
                return json.loads(receipt.stdout)
            except json.JSONDecodeError:
                pass
        time.sleep(.1)
    raise AssertionError("Fresh status not completed: " + kind)


def wait_for(kind, predicate, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        s = status(kind)
        if predicate(s):
            return s
        time.sleep(.3)
    raise AssertionError("Timed out: " + kind + " " + json.dumps(s))


def check(ok, label):
    text = ("PASS " if ok else "FAIL ") + label
    results.append(text)
    (out / "results.txt").write_text("\n".join(results) + "\n")
    print(text, flush=True)
    assert ok, label


def announce(action, sid):
    adb("shell", "am", "broadcast", "-n", "app.svan/.SessionReceiver", "-a",
        "android.media.action." + action + "_AUDIO_EFFECT_CONTROL_SESSION",
        "--ei", "android.media.extra.AUDIO_SESSION", str(sid),
        "--es", "android.media.extra.PACKAGE_NAME", player)


def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/svan-integrated-lab.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/svan-integrated-lab.xml"))


def tap(label):
    for _ in range(5):
        matches = [n for n in tree().iter("node") if label in (n.get("text"), n.get("content-desc"))
                   and n.get("bounds") != "[0,0][0,0]"]
        if matches:
            x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", matches[-1].get("bounds")))
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            time.sleep(.7)
            return
        time.sleep(.5)
    raise AssertionError("Native control missing: " + label)


def capture(name):
    time.sleep(.8)
    root = tree()
    (out / (name + ".xml")).write_text(ET.tostring(root, encoding="unicode"))
    (out / (name + ".png")).write_bytes(subprocess.check_output(
        ["adb", "-s", serial, "exec-out", "screencap", "-p"], timeout=60))


def capture_lab():
    adb("shell", "wm", "size", "780x1688")
    adb("shell", "wm", "density", "320")
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    for _ in range(12):
        adb("shell", "input", "swipe", "390", "400", "390", "1400", "200")
    if any(n.get("text") == "Got it" for n in tree().iter("node")):
        tap("Got it")
    tap("Appearance")
    tap("Mint Circuit")
    tap("Done")
    tap("Lab")
    tap("Shape")
    capture("mint_circuit-shape-applied")
    tap("Engine")
    capture("mint_circuit-engine-controls")
    for _ in range(5):
        adb("shell", "input", "swipe", "390", "1400", "390", "400", "250")
    capture("mint_circuit-engine-fit")
    for _ in range(8):
        adb("shell", "input", "swipe", "390", "400", "390", "1400", "200")
    tap("Measure")
    capture("mint_circuit-measure")


try:
    adb("shell", "pm", "grant", "app.svan", "android.permission.POST_NOTIFICATIONS")
    command("stop_capture")
    time.sleep(2)
    for pkg in (player, "app.svan.testsource.blocked"):
        adb("shell", "am", "start", "-W", "-n", pkg + "/app.svan.testsource.ToneActivity", "--ez", "stop", "true")
    command("stop_system")
    time.sleep(2)
    command("reset_sound")
    command("svaramanas", "--ez", "on", "false")
    command("mix_fallback", "--ez", "on", "false")
    command("engine_mode", "--ez", "system_only", "true")
    command("start_system")
    adb("shell", "sh", "-c", "'for p in $(pidof shizuku_server); do kill $p; done'")
    adb("shell", "pm", "revoke", "app.svan", "android.permission.DUMP")
    adb("shell", "am", "start", "-W", "-n", player + "/app.svan.testsource.ToneActivity",
        "--ef", "freq", "1000", "--ef", "amp", "0.05", "--ez", "broadcast", "true", "--ez", "component", "true")
    first = wait_for("basic", lambda s: any(r["pkg"] == player and r["owner"] == "ENGINE_A"
                     and r["sid"] in s["attached"] for r in s["routes"]))
    sid = next(r["sid"] for r in first["routes"] if r["pkg"] == player and r["sid"] in first["attached"])
    check(not first["dump"] and not first["reportAccess"], "basic connection without enhanced detection")
    for _ in range(5):
        announce("CLOSE", sid)
        announce("OPEN", sid)
    time.sleep(2)
    check(sid in status("basic")["attached"], "same-session track changes retain the connection")
    command("lab_fit", "--ei", "rate", "48000", "--ei", "block", "4096")
    fit = wait_for("lab", lambda s: not s["busy"] and s["ready"], seconds=180)
    p = fit["plan"]
    check(len(p["cutoffsHz"]) == 64 and len(set(p["cutoffsHz"])) == 64, "integrated fit has unique-bin controls")
    command("lab_apply")
    selected = wait_for("lab", lambda s: not s["busy"] and s["applied"])
    check(sid in selected["healthy"] and selected["bands"] == 64, "fit reaches existing healthy system-effect owner")
    announce("CLOSE", sid)
    announce("OPEN", sid)
    time.sleep(2)
    check(sid in status("lab")["healthy"], "fitted effect survives same-session CLOSE OPEN churn")
    capture_lab()
    command("eq_band", "--ef", "frequency", "1000", "--ef", "gain", "-3")
    restored = wait_for("lab", lambda s: not s["applied"] and not s["busy"])
    check(restored["bands"] == 128 and sid in restored["healthy"], "curve edit restores normal architecture")
    announce("CLOSE", sid)
    time.sleep(2)
    check(sid not in status("basic")["attached"], "real CLOSE expires and releases the effect")
    (out / "fit.json").write_text(json.dumps(fit, indent=2))
finally:
    (out / "logcat.txt").write_text(adb("logcat", "-d"))
    adb("shell", "pm", "grant", "app.svan", "android.permission.DUMP")
