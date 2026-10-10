#!/usr/bin/env python3
"""Synthetic, DUMP-free session churn and integrated Lab runtime checks. No commercial-player claim."""
import json
import re
import subprocess
import sys
import time
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
    command(kind + "_status")
    return json.loads(adb("shell", "run-as", "app.svan", "cat", "files/" + kind.replace("_", "-") + "-status.json"))


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


try:
    command("stop_capture")
    time.sleep(2)
    adb("shell", "am", "start", "-W", "-n", player + "/app.svan.testsource.ToneActivity", "--ez", "stop", "true")
    command("reset_sound")
    command("svaramanas", "--ez", "on", "false")
    command("mix_fallback", "--ez", "on", "false")
    command("engine_mode", "--ez", "system_only", "true")
    command("start_system")
    adb("shell", "sh", "-c", "'for p in $(pidof shizuku_server); do kill $p; done'")
    adb("shell", "pm", "revoke", "app.svan", "android.permission.DUMP")
    adb("shell", "am", "start", "-W", "-n", player + "/app.svan.testsource.ToneActivity",
        "--ef", "freq", "1000", "--ef", "amp", "0.05", "--ez", "broadcast", "true", "--ez", "component", "true")
    first = wait_for("basic", lambda s: bool(s["attached"]))
    sid = next(r["sid"] for r in first["routes"] if r["pkg"] == player)
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
