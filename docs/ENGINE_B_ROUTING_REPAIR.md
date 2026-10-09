# Engine B routing repair and tuning signatures — after 0.5.8 / code 15

Source basis: branch `ccr-9eafc6c2-mru90o` at `e4d2920` (the code the owner's 0.5.8 APK was built from, including the
version-keyed capture verdicts). Nothing here changes the version number; a release build is a separate owner step.

## Owner request (9 October 2026)

1. Spotify must be able to play through the audiophile engine (Engine B).
2. "Learn this sound" must save its mixing/mastering knowledge as presets: nine saved tuning signatures.

## What the 0.5.8 investigation showed (and what it did not)

The owner's 0.5.8 log showed Spotify detected and attached to system effects (Engine A) while the audiophile engine had
no assigned player. Read in the code, that is a routing outcome, not a failed capture: with no admitted UID the capture
loop generates silence instead of reading the recorder, so the repeated `capture level: peak=0.0000 ... muted=[]` lines
say "nothing assigned", not "Spotify delivered silence". The supplied log did not include the initial routing decisions,
so **the exact reason Spotify stayed on Engine A in that incident is still unproven.** What was confirmed, and fixed:

| Defect (code) | Effect on Spotify | Change |
|---|---|---|
| A player first seen after capture started was never checked (`capture check deferred ... using Engine A`); the check only ran during the startup window. | "Start Svan, then start music" left Spotify on Engine A until the engine was restarted. | Late capture check, see below. |
| One stretch of silence in a startup check was saved as `BLOCKED` for that app version. | A quiet intro, buffering or a route transition could permanently demote an app. | Silence is a strike. `BLOCKED` is saved only after `SilentStrikes.LIMIT` (2) strikes, and only strikes against a source the audio service reports as playing count. |
| A player that failed open (silent capture) was never looked at again after its back-off expired. | Temporary demotion became permanent for the capture session. | `retryParkedPlayers()` re-routes it once the back-off ends (rate limited to one tick per session per 15 s). |
| `CaptureCompat.all()` returned raw storage keys; the Hi-Fi list showed `com.spotify.music@146810608@1791220371629` as an app. | Bogus cards, stale labels. | `CaptureRecords.current()` returns one verdict per package for the installed version only. Malformed, older-version and shadowed legacy keys are removed at start-up. |
| The check filtered by UID only; the main recorder also filters by usage. | A check could hear audio the engine would exclude. | One builder, `CaptureCompat.playbackConfig`, for both. |
| Header said "Waiting for a music connection" and "No music connected" when music was detected on Engine A. | Looked like a detection failure. | `HiFiStatus` derives the text from routes; each app on system effects shows its `RouteReason`. |

## How a late check works (`SessionRouter`)

1. Unknown, playing app, engine running: it is parked on Engine A (audible, equalised) and `requestLateProbe` runs.
2. **Only while no source is being captured** (`captureUids` empty), so the check cannot disturb audio Engine B already
   carries. Otherwise it waits; the scan tick retries.
3. Phase 1 (`probeWorker`, not the routing worker): listen to that UID **without muting it**. A player that forbids capture
   is therefore never silenced.
4. Phase 2, only if audio was heard: mute the source and confirm capture still carries it (this is the measured
   "mute vs. capture tap" check from the investigation). Only then `toEngineB`.
5. Every async result is checked against the projection, session id, uid, owner and connection generation. A stale result
   applies nothing, and a stale phase 2 un-mutes the source.
6. Failures are bounded: silence retries every 30 s up to 6 checks; a refused second recorder backs off 1 min, then 5 min,
   then stops for that capture session (`RouteReason.INCONCLUSIVE`).

The startup path is the same two phases, sequential: unmuted first, mute, confirm. Previously it muted first and a blocked
app was silent for the whole 2.5 s check.

## Tuning signatures ("Learn this sound" presets)

* Nine fixed slots (`TuningSignatures.SLOTS`). Each stores the learned `TasteTarget` (7 packed numbers: track count, tilt,
  bass depth, brightness, width, dynamics): features only, never audio. A slot keeps its position when another is deleted.
* Hi-Fi/Svaramanas panel, under "Your sound": a 3x3 grid. Tapping a slot only selects it. **Use**, **Rename**,
  **Replace with current sound**, **Delete** (second tap confirms) and **Save current sound here** are explicit actions.
  The slot "in use" is whichever one holds exactly the current learned sound, so learning another track after using a
  signature correctly shows it as no longer a saved one.
* Stored in the `svaramanas` preferences (`signatures`) and included in settings export/restore. A backup made before this
  change has no `signatures` key and leaves the saved slots untouched; a damaged or 10-entry list rejects the whole backup.

## Verified here

* `:app:testDebugUnitTest` passes locally: 267 tests, 0 failures, including `CaptureRoutingTest` (16), `TuningSignaturesTest`
  (12) and two new `SettingsBackupTest` cases.
* Lint and debug assemble: see the CI run for the pushed commit.

## Not verified (do not claim)

* **No physical phone and no Spotify run were available.** Whether the installed Spotify build allows playback capture on
  the owner's TECNO/LG is unknown. If it forbids capture, no routing change can put it on Engine B; the app will now say so
  (`BLOCKED_THIS_VERSION` / `STREAM_NOT_CAPTURABLE`) instead of implying a detection failure. Ordinary consent, DUMP and
  Shizuku do not override another app's capture policy.
* Whether Android allows the second playback `AudioRecord` used by a late check beside Engine B's idle recorder is
  device-dependent. If refused, the log shows `capture check failed for <pkg>` and the card shows `RECORDER_BUSY`; the old
  behaviour (restart capture with the player already running) still works.
* The emulator fixtures cover the capturable and blocked cases only. Spotify, Amazon Music and YouTube Music still need the
  per-app, before/after-mute matrix in the 0.5.8 investigation.

## Investigation items not implemented

* Finding 6 (skipped audio-server scans shown as `server=idle`): untouched. The throttle still builds a result without a
  fresh server snapshot; keeping the last snapshot with an explicit age needs care around absence handling.
* Finding 7 (per-UID attribution of silence in the mixed recorder) and the remote-playback / other-profile states
  (Spotify Connect, work profile): not implemented.
* The full state machine and `MUTE_TAP_CONFLICT` as a stored reason: phase 2 measures it, but a conflict is currently
  counted as a silent strike, not stored under its own name.
* Detailed opt-in per-transition telemetry. The capture level log now says `source=none(...)` and reports
  `capturedFrames` / `generatedSilenceFrames`, so generated silence is no longer read as captured music.
