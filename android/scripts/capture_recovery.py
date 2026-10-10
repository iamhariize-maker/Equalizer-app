#!/usr/bin/env python3
"""Capture recovery regressions using real Android recorders and downstream host PCM.

These fixtures establish routing behavior on Android 13/14, not Spotify/OEM compatibility.
No assertion substitutes an engine label for measured audio.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from host_audio_level import capture_wav


def main():
    serial, target = sys.argv[1:3]
    out = Path(target)
    out.mkdir(parents=True, exist_ok=True)
    results = out / "results.txt"
    results.write_text("")
    cap, blocked = "app.svan.testsource.capturable", "app.svan.testsource.blocked"
    wav = Path(os.environ["QEMU_WAV_PATH"])

    def adb(*args):
        return subprocess.check_output(["adb", "-s", serial, *args], timeout=100).decode().strip()

    def eq(command, *args):
        adb("shell", "am", "start", "-W", "-n", "app.svan/.Command", "--es", "cmd", command, *args)

    def tone(pkg=cap, *args):
        adb("shell", "am", "start", "-W", "-n", f"{pkg}/app.svan.testsource.ToneActivity", *args)

    def status(name):
        # am start -W can return before onNewIntent writes the report. Delete the
        # previous receipt, then await a complete new one instead of reading stale state.
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
                    pass  # A direct file write may still be in progress.
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

    def route(data, pkg=cap):
        return next((r for r in data["routes"] if r["pkg"] == pkg), {})

    def full(data):
        r = route(data)
        return data["capture"] and r.get("owner") == "ENGINE_B_MUTED" and r.get("uid") in data["admitted"]

    def heard_both(data):
        report = route(data)["report"]
        unmuted = re.search(r"phase=unmuted result=audio frames=[1-9]\d* peak=", report)
        muted = re.search(r"phase=muted result=audio frames=[1-9]\d* peak=.*? elapsedMs=(\d+)", report)
        return unmuted is not None and muted is not None and int(muted[1]) >= 200

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
        tone(blocked, "--ez", "stop", "true")
        time.sleep(3)

    def start():
        # Each projection needs fresh consent. Do not let the singleTask status command
        # clear Android's permission activity before its result reaches the app.
        adb("shell", "appops", "set", "app.svan", "PROJECT_MEDIA", "allow")
        eq("start_capture", "--es", "quality", "EFFICIENT")
        time.sleep(1)

    def loud(pkg=cap, *extras):
        tone(pkg, "--ef", "freq", "1000", "--ef", "amp", "0.25", "--ez", "broadcast", "true", "--ez", "component", "true", *extras)

    def screen(name, text):
        # Keep screenshots anchored to the new controls/reasons, using actual UI text.
        for attempt in range(24):
            adb("shell", "uiautomator", "dump", "/sdcard/capture-recovery-ui.xml")
            xml = adb("shell", "cat", "/sdcard/capture-recovery-ui.xml")
            nodes = list(ET.fromstring(xml).iter("node"))
            if attempt == 0:
                tab = next((n for n in nodes if n.get("text") == "Hi-Fi"), None)
                if tab is not None:
                    bounds = list(map(int, re.findall(r"\d+", tab.get("bounds"))))
                    adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                    time.sleep(1)
                    # Tab state intentionally retains scroll. The preceding Retry capture check
                    # stops below the per-app status card; searching only downward misses a new
                    # reason above it. Return to the top before looking for the exact policy text.
                    for _ in range(12):
                        adb("shell", "input", "swipe", "160", "200", "160", "510", "100")
                    continue
            if any(text in n.get("text", "") for n in nodes):
                (out / f"{name}.xml").write_text(xml)
                break
            adb("shell", "input", "swipe", "160", "480", "160", "190", "350")
            time.sleep(1)
        else:
            raise AssertionError(f"Capture UI text missing: {text}")
        (out / f"{name}.png").write_bytes(subprocess.check_output(["adb", "-s", serial, "exec-out", "screencap", "-p"], timeout=100))

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
        for pkg in (cap, blocked):
            eq("app_engine", "--es", "pkg", pkg, "--ez", "system_only", "false")
        eq("forget_verdicts")
        eq("start_system")
        loud()
        wait("system-flat", lambda d: route(d).get("owner") == "ENGINE_A")
        flat = level("system-flat")
        eq("eq_band", "--ef", "frequency", "1000", "--ef", "gain", "-6")
        time.sleep(2)
        cut = level("system-cut")
        check("system baseline is one measured -6 dB EQ path", abs(cut - flat + 6) <= .75)

        eq("test_legacy_capture_block", "--es", "pkg", cap)
        seeded = status("legacy-seeded")
        adb("shell", "am", "force-stop", "app.svan")
        adb("shell", "am", "start", "-W", "-n", "app.svan/.MainActivity")
        migrated = wait("legacy-migrated", lambda d: route(d).get("owner") == "ENGINE_A")
        check("current-version legacy block migrates without resetting sound", seeded["storedBlocks"] == 1 and seeded["storedStrikes"] == 1 and migrated["storedBlocks"] == 0 and migrated["storedStrikes"] == 0 and migrated["gain"] == -6)
        start()
        initial = wait("startup-captured", full)
        time.sleep(2)
        check("startup capture proves both mute phases and measured native EQ", heard_both(initial) and abs(level("startup-cut") - flat + 6) <= .75)

        stop()
        start()
        idle = wait("idle-engine", lambda d: d["capture"] and not d["admitted"] and d["recorder"] is None and not d["routes"])
        check("idle engine releases its main recorder before late checks", not idle["routes"])
        loud()
        late = wait("late-captured", full)
        time.sleep(2)
        check("late source shares the recorder safely and plays measured native EQ", heard_both(late) and abs(level("late-cut") - flat + 6) <= .75)

        late_sid = route(late)["sid"]
        tone(cap, "--ez", "pause", "true")
        paused = wait("paused-released", lambda d: route(d).get("playing") is False and route(d).get("owner") == "ENGINE_A" and not d["admitted"] and d["recorder"] is None)
        check("paused source releases capture so another app can be checked", route(paused)["sid"] == late_sid and route(paused)["reason"] == "WAITING_FOR_PLAYBACK")
        tone(cap, "--ez", "pause", "false")
        resumed = wait("paused-resumed", full)
        time.sleep(2)
        check("same session resumes with fresh mute proof and measured native EQ", route(resumed)["sid"] == late_sid and heard_both(resumed) and abs(level("resumed-cut") - flat + 6) <= .75)

        stop()
        eq("forget_verdicts")
        adb("logcat", "-c")
        tone(cap, "--ef", "freq", "1000", "--ef", "amp", "0", "--ez", "broadcast", "true", "--ez", "component", "true")
        wait("quiet-connected", lambda d: route(d).get("owner") == "ENGINE_A" and route(d).get("playing") is True)
        start()
        wait("quiet-first", lambda d: route(d).get("reason") == "SILENT_RECENTLY")
        eq("retry_capture", "--es", "pkg", cap)
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            log = adb("logcat", "-d", "-s", "EqSpike:I")
            observations = re.findall(rf"capture check: {re.escape(cap)} .*phase=unmuted result=silent samples; policy unproven frames=([1-9]\d*)", log)
            quiet = status("quiet-second")
            if len(observations) >= 2 and route(quiet).get("owner") == "ENGINE_A":
                break
            time.sleep(1)
        else:
            raise AssertionError("Two genuine silence checks did not finish")
        check("repeated real silent PCM never creates a permanent block or mute", quiet["storedBlocks"] == 0 and quiet["storedStrikes"] == 0 and not quiet["admitted"])
        screen("retry-capture", "Retry capture")
        old_sid = route(quiet)["sid"]
        tone(cap, "--ef", "live_amp", "0.25")
        eq("retry_capture", "--es", "pkg", cap)
        recovered = wait("quiet-recovered", full)
        time.sleep(2)
        check("same app and session recover from silence without update or global reset", route(recovered)["sid"] == old_sid and heard_both(recovered) and recovered["storedBlocks"] == 0 and abs(level("recovered-cut") - flat + 6) <= .75)

        stop()
        # Android also folds the UID policy into the reported player flags. Hide
        # those two discovery reports so the independent UID-policy table must
        # explain the refusal for this explicitly announced source.
        eq("test_blind_reports", "--ez", "players", "true", "--ez", "server", "true")
        time.sleep(2)
        loud(cap, "--ei", "uid_capture_policy", "3")
        wait("uid-policy-connected", lambda d: route(d).get("owner") == "ENGINE_A")
        (out / "uid-policy-server.txt").write_text(adb("shell", "dumpsys", "media.audio_policy"))
        start()
        uid_block = wait("uid-policy-blocked", lambda d: route(d).get("reason") == "UID_CAPTURE_DISABLED")
        r = route(uid_block)
        check("UID policy explains an opt-out when player flags are unavailable", r["manifestAllowed"] is True and r["streamOptOut"] is None and "UID capture policy disabled (flag_mask=0x1400)" in r["report"] and not uid_block["admitted"] and "phase=muted" not in r["report"])
        check("UID-policy-blocked music remains audible through measured system EQ", abs(level("uid-policy-cut") - flat + 6) <= .75)
        screen("uid-policy", "audio server reports")
        eq("test_blind_reports", "--ez", "players", "false", "--ez", "server", "false")
        loud()  # Same installed version, new stream, default ALLOW_CAPTURE_BY_ALL.
        policy_recovered = wait("uid-policy-recovered", full)
        time.sleep(2)
        check("new stream can recover automatically after the UID policy permits capture", heard_both(policy_recovered) and abs(level("uid-policy-recovered-cut") - flat + 6) <= .75)

        stop()
        loud(blocked)
        wait("manifest-connected", lambda d: route(d, blocked).get("owner") == "ENGINE_A")
        start()
        manifest = wait("manifest-blocked", lambda d: route(d, blocked).get("reason") == "APP_CAPTURE_DISABLED")
        r = route(manifest, blocked)
        check("installed manifest opt-out is proven without muting or guessing from silence", r["manifestAllowed"] is False and r["manifestExplicit"] is False and r["owner"] == "ENGINE_A" and not manifest["admitted"] and "No capture samples measured" in r["report"])
        check("manifest-blocked music stays audible through measured system EQ", abs(level("manifest-cut") - flat + 6) <= .75)
        screen("manifest-policy", "settings disable playback capture")
        stop()
    except Exception as error:
        with results.open("a") as file:
            file.write(f"FAIL capture recovery suite: {error}\n")
        raise
    finally:
        (out / "logcat.txt").write_text(adb("logcat", "-d"))


if __name__ == "__main__":
    main()
