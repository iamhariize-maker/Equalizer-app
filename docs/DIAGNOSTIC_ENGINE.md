# Full diagnostic engine

Hi-Fi → Music detection → **Full diagnostic**. One tap measures why a player is not found, not processed, or not
captured on *this* phone, and produces a report that can be sent to the developer.

## Why it exists

The same symptom ("Spotify is not processed") has many unrelated causes, and they differ by phone and Android skin
(HiOS, HyperOS, One UI, ColorOS and others limit background apps and audio paths in different ways). Reading code
cannot tell them apart; only measurements on the phone can. The engine records the evidence for each possible cause so
a verdict never appears without the lines it rests on.

## What it measures

| Stage | What | Where it comes from |
| --- | --- | --- |
| Environment | Android build, OEM skin, chip, developer options, permissions, record-audio app-op, battery optimisation, standby bucket, background restriction, Svan's service state | public APIs and the readable `build.prop` files (no hidden APIs) |
| Target app | version, install source, signing-certificate fingerprint, stopped state, manifest capture declaration | `PackageManager` |
| Detection | every session announcement Svan received *and what became of it* (accepted, or dropped with the reason), which other apps announced, a self-test proving broadcasts reach Svan, routes, Android's public active-player count | `SignalLedger`, `SessionReceiver`, `AudioManager` |
| Effects | whether Svan's effect is attached and verified, other effects on the same session, vendor effects registered on the phone | `GlobalEqEngine`, `dumpsys media.audio_flinger`, `AudioEffect.queryEffects()` |
| Audio-server view | the policy's own entry for the player's stream: **effective** attribute flags, UID-wide capture policy, and whether the stream is attached to a capture mix | `dumpsys media.audio_policy` (needs Enhanced detection) |
| Capture lab | a ladder of capture attempts, a few seconds each, against the running player | `AudioPlaybackCapture`, needs the audiophile engine started |

### The capture ladder

Each attempt asks Android for different audio. The *pattern* of which attempts hear sound is what points at a cause.

1. this app, MEDIA usage, float 48 kHz: what Svan 0.5.10 listens with
2. this app, no usage rule: what 0.5.5 and 0.5.6 listened with
3. this app, MEDIA + GAME + UNKNOWN
4. this app, MEDIA, 16-bit
5. this app, MEDIA, 44.1 kHz
6. every app's MEDIA audio except Svan
7. every app, any usage, except Svan
8. *(opt-in)* this app, Svan's own effect detached
9. *(opt-in)* this app, muted first (the order older builds used)

Tests 1–7 leave the player audible and unchanged. Tests 8 and 9 briefly detach the effect or mute the player and then
always restore system effects (`SessionRouter.labDisrupted`).

## Reading the result

Findings are ordered worst first and each lists its evidence and a hedged next step. Codes you may be asked about:

| Code | Meaning |
| --- | --- |
| `TARGET_NEVER_ANNOUNCED` | other apps announce, the player never did. Close it completely and reopen it, or use Enhanced detection |
| `NO_ANNOUNCEMENTS_AT_ALL` | receiving works but no player opened a session since Svan started |
| `RECEIVER_DEAF` | Svan's own test broadcast never arrived: background delivery is blocked on this phone |
| `ANNOUNCEMENT_DROPPED` | it arrived and Svan dropped it; the reason is listed |
| `OEM_BACKGROUND_LIMITS` | the skin limits background apps and Svan is not exempt (typical, not proven, cause) |
| `STREAM_OPTS_OUT` / `UID_POLICY_BLOCKS_CAPTURE` / `MANIFEST_DISABLES_CAPTURE` | the app forbids capture; only System effects can process it |
| `USAGE_FILTER_MISSES_STREAM` | capture works only with a wider usage rule |
| `UID_FILTER_MISSES_STREAM` | audio reaches the mix but not under this app's UID |
| `FORMAT_SENSITIVE` | only another sample format or rate works |
| `CAPTURE_ONLY_AFTER_MUTE` / `CAPTURE_ONLY_WITHOUT_EFFECT` | ordering or effect interplay on this phone |
| `NOT_ATTACHED_TO_CAPTURE_MIX` | the audio server did not copy the stream into the capture mix and its published flags do not explain it: a vendor audio policy or output path |
| `ATTACHED_BUT_SILENT` | the tap exists but only silence arrives: look at the recorder side |
| `RECORDER_SILENCED` | Android reported that it silenced Svan's recorder |
| `LAB_PLAYER_NOT_PLAYING` | silence proves nothing; start playback and run again |

## What it does not claim

It never says a vendor or an app "blocks" capture unless the audio server's own report says so. Findings that
cannot be proven are worded as likely causes. Emulator checks (`android/scripts/diagnostic_lab.py`) establish the
engine's behaviour with the fake player on Android 13/14; they do not qualify any commercial player or phone.

## Privacy

The report holds package names, versions, the signing-certificate fingerprint prefix, device model and build
properties, the audio server's lines for the chosen player, and a short signal timeline. No audio, no track titles,
no account data. It is written to Svan's private folder and shared only through Android's share sheet or the clipboard
when the user chooses. Nothing is uploaded.

## For developers

* `android/app/src/main/java/app/svan/diag/`: `SignalLedger` (bounded event history), `DumpExtract` (pure parsers),
  `EnvProbe`, `CaptureLab`, `DiagRules` (pure, unit-tested), `DiagReport` (text and JSON), `DiagnosticEngine`.
* Tests: `app/src/test/java/app/svan/diag/` (64 tests: parsers use the layout from the android14-release
  `ClientDescriptor::dump`; every rule has a scenario).
* Scripted run (debug build): `am start -n app.svan/.Command --es cmd diagnostic --es pkg <package> [--ez lab false] [--ez disruptive true]`
  writes `files/diag-report.json` and `files/diag-report.txt`.
* The router pauses its own background capture checks while the lab runs (`SessionRouter.labActive`).
