#!/usr/bin/env python3
"""Emulator checks for the full diagnostic engine, using real Android recorders and measured downstream PCM.

These establish the engine's behaviour on Android 13/14 with the fake player, not on any commercial player or OEM
skin. Kept separate from capture_recovery.py so that script's fixed assertion count is untouched.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from host_audio_level import capture_wav


def main():
    serial, target = sys.argv[1:3]
    out = Path(target)
    out.mkdir(parents=True, exist_ok=True)
    results = out / "results.txt"
    results.write_text("")
    cap = "app.svan.testsource.capturable"
    wav = Path(os.environ["QEMU_WAV_PATH"])

    def adb(*args):
        return subprocess.check_output(["adb", "-s", serial, *args], timeout=100).decode().strip()

    def eq(command, *args):
        adb("shell", "am", "start", "-W", "-n", "app.svan/.Command", "--es", "cmd", command, *args)

    def tone(pkg=cap, *args):
        adb("shell", "am", "start", "-W", "-n", f"{pkg}/app.svan.testsource.ToneActivity", *args)

    def loud(*extras):
        tone(cap, "--ef", "freq", "1000", "--ef", "amp", "0.25", "--ez", "broadcast", "true", "--ez", "component", "true", *extras)

    def status(name):
        adb("shell", "run-as", "app.svan", "rm", "-f", "files/capture-report.json")
        eq("capture_report")
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            report = subprocess.run(["adb", "-s", serial, "shell", "run-as", "app.svan", "cat", "files/capture-report.json"],
                                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=100)
            if report.returncode == 0:
                try:
                    data = json.loads(report.stdout)
                except json.JSONDecodeError:
                    pass
                else:
                    (out / f"{name}.json").write_text(json.dumps(data, indent=2))
                    return data
            time.sleep(.1)
        raise AssertionError(f"Fresh capture report was not completed: {name}")

    def wait(name, predicate, limit=60):
        deadline = time.monotonic() + limit
        while time.monotonic() < deadline:
            data = status(name)
            if predicate(data):
                return data
            time.sleep(1)
        raise AssertionError(f"Timed out: {name}; last state in {name}.json")

    def route(data):
        return next((r for r in data["routes"] if r["pkg"] == cap), {})

    def idle_on_system_effects(d):
        return d["capture"] and not d["admitted"] and d["recorder"] is None and route(d).get("owner") == "ENGINE_A"

    def full(d):
        r = route(d)
        return d["capture"] and r.get("owner") == "ENGINE_B_MUTED" and r.get("uid") in d["admitted"]

    def diagnostic(name, *extra, limit=150):
        adb("shell", "run-as", "app.svan", "rm", "-f", "files/diag-report.json", "files/diag-report.txt")
        eq("diagnostic", "--es", "pkg", cap, *extra)
        deadline = time.monotonic() + limit
        while time.monotonic() < deadline:
            report = subprocess.run(["adb", "-s", serial, "shell", "run-as", "app.svan", "cat", "files/diag-report.json"],
                                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=100)
            if report.returncode == 0 and report.stdout:
                try:
                    data = json.loads(report.stdout)
                except json.JSONDecodeError:
                    pass  # still being written
                else:
                    (out / f"{name}.json").write_text(json.dumps(data, indent=2))
                    text = subprocess.run(["adb", "-s", serial, "shell", "run-as", "app.svan", "cat", "files/diag-report.txt"],
                                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=100)
                    (out / f"{name}.txt").write_bytes(text.stdout)
                    return data
            time.sleep(1)
        raise AssertionError(f"Diagnostic report was not produced: {name}")

    def trial(data, trial_id):
        return next((t for t in data["trials"] if t["id"] == trial_id), {})

    def stage(data, stage_id):
        return next((x["status"] for x in data["pipeline"] if x["id"] == stage_id), "MISSING")

    def timeline(data):
        return " | ".join(x["text"] for x in data["engineTimeline"])

    def codes(data):
        return {f["code"] for f in data["findings"]}

    def level(name):
        value = capture_wav(wav, out / f"{name}.float32le")
        (out / f"{name}.db").write_text(str(value))
        print(f"INFO {name}: {value:.4f} dBFS", flush=True)
        return value

    def check(name, condition):
        line = ("PASS " if condition else "FAIL ") + name
        print(line, flush=True)
        with results.open("a") as file:
            file.write(line + "\n")
        if not condition:
            raise AssertionError(name)

    def stop():
        eq("stop_capture")
        time.sleep(3)
        tone(cap, "--ez", "stop", "true")
        time.sleep(3)

    def start():
        adb("shell", "appops", "set", "app.svan", "PROJECT_MEDIA", "allow")
        eq("start_capture", "--es", "quality", "EFFICIENT")
        time.sleep(1)

    try:
        adb("shell", "pm", "grant", "app.svan", "android.permission.DUMP")
        adb("shell", "pm", "grant", "app.svan", "android.permission.RECORD_AUDIO")
        adb("shell", "pm", "grant", "app.svan", "android.permission.POST_NOTIFICATIONS")
        adb("shell", "appops", "set", "app.svan", "PROJECT_MEDIA", "allow")
        stop()
        eq("shared_output", "--ez", "on", "false")
        eq("stop_system")
        time.sleep(2)
        eq("reset_sound")
        eq("svaramanas", "--ez", "on", "false")
        eq("mix_fallback", "--ez", "on", "false")
        eq("engine_mode", "--ez", "system_only", "false")
        eq("app_engine", "--es", "pkg", cap, "--ez", "system_only", "true")  # stays on system effects while capture runs idle
        eq("forget_verdicts")
        eq("start_system")

        # 1. A capturable player on an idle engine: the lab must hear it and the engine must have seen its announcement.
        loud()
        wait("connected", lambda d: route(d).get("owner") == "ENGINE_A")
        flat = level("flat")
        eq("eq_band", "--ef", "frequency", "1000", "--ef", "gain", "-6")
        time.sleep(2)
        start()
        wait("idle", idle_on_system_effects)
        works = diagnostic("works")
        check("diagnostic capture lab hears a capturable player with Svan's current settings",
              trial(works, "uid_media_f32").get("heardAudio") is True and "CAPTURE_WORKS" in codes(works) and not works["verdict"].startswith("FAIL"))
        check("diagnostic ledger saw the player's announcement and the receiver self-test arrived",
              works["announcements"]["accepted"] >= 1 and works["selfTest"]["ran"] is True and works["selfTest"]["ms"] is not None)

        check("flight recorder saw the engine start: foreground, permission token and negotiated format",
              stage(works, "C1") == "PASS" and stage(works, "C2") == "PASS" and stage(works, "C4") == "PASS"
              and "milestone RATE_NEGOTIATED" in timeline(works) and works["format"] == 2)

        # 2. The disruptive tests must hear the player and must always restore system effects.
        disruptive = diagnostic("disruptive", "--ez", "disruptive", "true", limit=200)
        restored = wait("restored", idle_on_system_effects)
        time.sleep(2)
        check("disruptive tests ran and system effects were restored at the measured -6 dB",
              trial(disruptive, "uid_any_f32_effect_off").get("heardAudio") is True and trial(disruptive, "uid_any_f32_muted").get("heardAudio") is True
              and route(restored).get("owner") == "ENGINE_A" and abs(level("after-disruptive") - flat + 6) <= .75)

        # 3. A UID-wide capture opt-out must be named, and nothing must be heard.
        stop()
        loud("--ei", "uid_capture_policy", "3")
        wait("policy-connected", lambda d: route(d).get("owner") == "ENGINE_A")
        start()
        wait("policy-idle", idle_on_system_effects)
        blocked = diagnostic("uid-policy")
        check("diagnostic names a UID-wide capture opt-out and hears nothing from the opted-out player",
              "UID_POLICY_BLOCKS_CAPTURE" in codes(blocked) and blocked["verdict"].startswith("FAIL")
              and not any(t["heardAudio"] for t in blocked["trials"] if t["scope"].startswith("uid")))

        # 4. While the engine is carrying the player, the lab must stand down and say why, without disturbing playback.
        stop()
        eq("app_engine", "--es", "pkg", cap, "--ez", "system_only", "false")
        loud()
        start()
        carried = wait("carried", full, limit=90)
        busy = diagnostic("busy")
        after = status("busy-after")
        check("diagnostic stands down while the engine carries the player and leaves it carried",
              "LAB_NOT_RUN" in codes(busy) and busy["lab"]["ran"] is False and "already capturing" in (busy["lab"]["skipReason"] or "")
              and full(after) and route(after).get("sid") == route(carried).get("sid"))
        live = [stage(busy, s) for s in ("C1", "C2", "C3", "C4", "C5", "C7", "C8", "C9")]
        check("pipeline follows the live engine: recorder, frames and sound observed with no enhanced-report evidence needed",
              all(v == "PASS" for v in live) and stage(busy, "C11") != "FAIL" and len(busy["engineWindows"]) >= 2
              and busy["firstBrokenStage"] is None and any(w["captured"] > 0 and w["inDb"] > -60 for w in busy["engineWindows"]))
        stop()
        idle = diagnostic("stopped", "--ez", "lab", "false")
        check("pipeline reports a stopped engine as waiting, keeps the flight recorder and raises no engine finding",
              stage(idle, "C1") == "WAITING" and "milestone STOPPED" in timeline(idle)
              and not any(c in codes(idle) for c in ("ENGINE_B_START_FAILED", "NO_FRAMES_DELIVERED", "CAPTURE_DELIVERS_SILENCE")))
    except Exception as error:
        with results.open("a") as file:
            file.write(f"FAIL diagnostic lab suite: {error}\n")
        raise
    finally:
        (out / "logcat.txt").write_text(adb("logcat", "-d"))


if __name__ == "__main__":
    main()
