# Capture recovery, Svan 0.5.10 / code 17

The owner's Android 14 log identifies Spotify (`com.spotify.music`, session 40777) as started,
seen by both AudioService and AudioFlinger, with its system effect verified as PROCESSING.
The failure is admission to playback capture, not player discovery. The main-loop report
`source=none`, `capturedFrames=0`, `generatedSilenceFrames=96000` describes output Svan
created while no source was admitted. It does not establish Spotify's capture policy.

The screenshot establishes that 0.5.9 saved a silence-based BLOCKED verdict for Spotify's
installed version. This is a confirmed application defect: two silent checks can reflect
buffering, an OEM recorder conflict, no delivered frames, or a mute/capture-tap conflict.
They cannot establish an installed application's permanent playback-capture policy.
The original before/after-mute measurements were not supplied, so their physical cause
remains unknown. This change repairs the identified failure paths and makes that decision
inspectable on the phone.

## Changes

* Migrate all legacy silence-based blocks and strikes, preserve positive history, and never
  persist a negative verdict inferred from samples. Historical positives are advisory;
  new capture sessions must prove audio before and after muting.
* Use one playback-recorder lease for initial checks, later checks, diagnostics, and live
  capture. Release the idle main recorder instead of competing with a later check.
* Use nonblocking reads with actual deadlines. An active recorder delivering no frames
  fails open by elapsed time; a source change can close it even without a final block.
* Keep system effects audible during the first check. Restore them if capture fails after
  muting. Ignore the first 200 ms after opening the muted check so buffered pre-mute
  samples cannot immediately confirm the tap. Suppress repeated disruptive mute checks for that stream until an explicit retry,
  a new source, resume, or device change.
* Release capture reservations for explicitly paused sources so the next music app can
  be checked, and recheck a resumed stream before muting it. If a higher client rate
  delivers silence after a positive 48 kHz probe, retry the proven safe format once.
* Cancel stale check completions across capture/session generations. Retry safely within
  bounded schedules; provide per-app Retry capture and Copy capture report actions.
* Inspect the installed, UID-matched base manifest using public package/resource APIs.
  An explicit allowAudioPlaybackCapture=false is direct policy evidence; permission to
  capture is not inferred from a successful manifest read alone.
* Read Android's fixed media.audio_policy report through the existing DUMP/Shizuku route.
  A UID-wide policy can be applied inside AudioPolicyManager without appearing in the
  player's AudioService attributes. Missing/unreadable tables remain unknown. No grant,
  arbitrary command, account data, or capture-policy override is introduced.
* Retain all four artistic themes and the existing DSP, detection, recording, and controls.
  Work is isolated on codex/svan-capture-recovery; the other collaboration branch is untouched.

The owner also asked to remove optional WAV recording if it causes this failure. ClipRecorder
and ProofRecorder create no AudioRecord or MediaProjection: they copy existing dry/wet
buffers only after an explicit recording action, and the inactive path returns immediately.
The speaker sync cue is a capture-excluded SONIFICATION AudioTrack. No evidence connects
these features to the reported admission failure. The engine input and compatibility/
diagnostic recorders now share one lease; optional WAV export is retained.

## Evidence and limits

Final local debug/release builds and lint pass with 282 JVM tests, zero failures/skips.
Native DSP passes all 179 tests; Python tooling passes 17 checks and mastering tooling
passes 12. Application source is frozen at 3dabee6b3bc73bf6391c03b98bb8b0b5f62b2080
(production build CI 37930206322). Later changes affect only validation scripts, workflows,
and documentation, not the tested APK payload.

[Full Android 14 validation](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37942817135)
at harness 45123a0a5693307fe6322e966090b0631fd0a350 succeeds. Required result files contain:

| Gate | Passed checks |
| --- | ---: |
| Capture recovery | 14 |
| Production, automation disabled | 8 |
| Audio routing | 41 |
| Recording | 7 |
| Basic detection | 14 |
| EQ workspace / precision controls | 9 / 8 |
| Source filtering | 3 |
| Quality / continuity | 4 / 4 |
| Enhanced detection / detection onboarding | 13 / 6 |
| Onboarding / fallback setup | 8 / 3 |
| Screens and scrolling | 16 |
| Release smoke | Passed |

Recovery uses real AudioRecord frames and downstream host PCM. System effects, startup/late
capture, pause/resume, same-session recovery after two actual silent checks, and UID-policy
recovery measure -5.9965 to -5.9966 dB against a flat baseline for the intended -6 dB cut.
Production flat playback, native replay, and manifest-blocked fallback each measure
-47.5725 dBFS, confirming one copy. Public UI actions start production capture and prove
post-mute signal; production rejects scripted command extras. The elapsed digital-silence
watchdog restores the source after four seconds in the same-session zero-PCM fixture.
No existing assertion was removed or relaxed. These measurements are emulator output,
not physical DAC or acoustic qualification.

The independent UID-policy fixture blinds player reports, verifies the direct 0x1400
policy mask, and confirms no muted probe or native admission. Restoring that policy on a
new stream of the same app version recovers native capture. Legacy migration does not
reset settings, and actual silence never creates a persistent negative verdict.

All four themes retain the source build's 39 debug and 35 production appearance checks
and 68 captured screenshots. Representative theme layouts and final production capture/
restriction screens were inspected.

The owner-signed APK reuses certificate SHA-256
9cb9daca3b49fbdd17683d45dfb069fa9c6d05e3733934795f92546fef696b0f.
All 86 ZIP payload entries match the tested production APK byte for byte. Package/version,
permissions, signature, 16 KB ZIP alignment, and all four native ELF alignments pass.
Private key material was handled outside Git/CI and removed from temporary storage.
APK SHA-256: 5dce12adff4cec1a76a1d7231e386a001dda38b50cf5b59643f75c19ecde1778.

Android 13 is unqualified for this update. Hardware-accelerated hosted attempts lost the
emulator before completing the matrix; changing images, GPU modes, and KVM MSR handling
did not resolve that failure. The software-CPU attempt
[37947507926](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37947507926)
records SystemUI, media-provider, and phone-service ANRs before Svan launches. Svan then
misses its foreground-service deadline and the first report wait fails. SystemEqService
already calls startForeground before repository, routing, or detection initialization;
the logs show service-start completion delayed by roughly 32 seconds. This run establishes
neither a passing Android 13 matrix nor the cause of the owner's Android 14 capture failure.
The failed logs are retained; no deadline or audio assertion was waived.

Emulator fixtures do not qualify Spotify, Amazon Music, YouTube Music, TECNO or LG hardware.
If a player's installed manifest, UID-wide policy, or stream policy excludes MediaProjection,
ordinary playback capture cannot provide its PCM to Svan's native engine. System effects
remain the available route. A mute-tap incompatibility is a different condition and is
reported separately. The new per-app capture report is needed to settle which condition
applies to the owner's installed Spotify build.

## Platform references

* [Android playback capture requirements](https://developer.android.com/media/platform/av-capture):
  the most restrictive manifest, app, and player policies apply; apps must share a user profile.
* [Android 14 AudioPolicyManager](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android14-release/services/audiopolicy/managerdefault/AudioPolicyManager.cpp):
  mAllowedCapturePolicies is combined into resultAttr.flags; AllowedCapturePolicies is dumped
  as UID/flag_mask entries. The parser is original, not copied platform implementation.
* Android 13 AudioPolicyManager exposes the same UID/flag_mask table; both versions were
  checked against official platform source.
* Android 14 MediaProjectionManagerService.canProjectAudio permits screen-capture projections
  independent of the selected display/app sharing cookie. The app-sharing prompt was not
  assumed to be the cause, and its consent behavior was not changed without evidence.
