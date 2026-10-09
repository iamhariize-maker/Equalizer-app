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

## Evidence and limits

Final local debug/release builds and lint passed with 282 JVM tests, zero failures/skips.
Native DSP passed all 179 tests; Python tooling passed 17 checks and mastering tooling
passed 12. The source/production APK is frozen at 3dabee6 (CI 37930206322).
Android 14 measured startup/late capture and pause/resume at -6 dB, with no duplicate copy.
The next quiet-case attempt was cancelled before capture: the harness reopened the singleTask
activity while Android was resolving projection consent. The harness now permits consent to
finish and waits for current playback. Reports are deleted and awaited before each read, so an asynchronous onNewIntent cannot return a stale receipt. Idle checks await session-removal grace. The existing watchdog fixture now delivers actual zero PCM on the same admitted AudioTrack instead of recreating a source that the fixed CLOSE handler already excludes. Its measured fallback and fail-open-log assertions are retained. All audio assertions stay intact; a frozen-APK rerun
retains every full suite gate on Android 13/14. The focused Android 14 run now measures recovery after two real silent checks on the same session. The UID fixture separately blinds player reports, because Android also exposes its effective restriction in player flags and otherwise the existing stream guard correctly wins first. Android 13 emulator host diagnostics identify an unsupported AMD guest WRMSR; the isolated verifier enables KVM ignore_msrs for that virtual CPU. All 14 focused recovery assertions now pass on API 34. The production fixture now selects global Auto through the public UI (fresh installs intentionally start in System effects only). Full verifiers retain the same required assertion sets in separate API 33/34 workflows; API 33 also preserves guest kernel logs on an Ubuntu 22.04/legacy SwiftShader host. Final full/production emulator results remain pending. The added integration suite measures downstream host PCM, not merely route
labels. It exercises version-keyed legacy migration, start-before-music, pause/resume, real zero samples,
recovery on the same audio session, UID-wide policy, and an installed manifest opt-out.
All existing test gates remain. Four additional production assertions use public UI actions
with automation disabled and measure actual replayed audio plus manifest-blocked fallback.

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
