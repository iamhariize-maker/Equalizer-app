# Android spike: questions to answer on real phones

Install the debug APK on 2–3 devices: one Pixel on Android 15/16, one Samsung,
and one other OEM such as Xiaomi or OnePlus. Then work through these. Record the
results in this file.

## Q1. How many DynamicsProcessing bands does each device accept? (Engine A)

The docs don't state a limit. Tap **"1. Probe DynamicsProcessing band limits"**.
It tries 10…1024 bands on a live session, reads every gain back, and times setup.

| Device | Android | Max bands OK | Read-back mismatches | Notes |
|---|---|---|---|---|
| TECNO LH7n | 14 (API 34) | **1024** (all tested) | 0 | Setup (per-band calls): 128 → ~225–255 ms, 256 → ~430–590 ms, 1024 → ~2.6–3.3 s |

**Takeaways so far**
- Band count isn't the limit on this device; per-band binder cost is. Engine A
  now defaults to 128 bands and only re-sends bands whose gain changed.
- Stored ≠ audible. **Q1b** measures what you actually hear.

## Q1b. Does the device *audibly* honour the bands?

Tap **"1b. Measure audible band resolution"** with the volume low. It plays
tones through alternating +6/−6 dB bands and measures the level with a
Visualizer. Adjacent bands should differ by **12 dB**: ~12 means fully
resolved, ~0 means smeared. It also times a bulk `setPreEqAllChannelsTo` call
against per-band calls.

| Device | Bands | 60 Hz | 1 kHz | 10 kHz | Bulk vs per-band (1024) |
|---|---|---|---|---|---|
| TECNO LH7n | 31…1024 | ≈0 | ≈0 | ≈0 | no faster (~0.9–1.4 s either way; 128 bands ~100 ms) |

**Inconclusive.** Every count reads about 0 dB, even 31 bands, so the Visualizer
sees the session's audio *before* its effects. That's useful for Q3, though:
it's consistent with playback capture also tapping pre-effect audio. Next
method: measure through Engine B's capture of a second, effect-free route, or
use a loopback cable / USB audio interface.

## Q2. Which apps announce their sessions? (Engine A)

Play music in each app, then tap **Refresh log** and look for `OPEN session=… pkg=…`.

| App | Broadcasts session? | With DUMP granted? |
|---|---|---|
| Spotify | | |
| YouTube Music | | |
| Tidal / Qobuz / Apple Music | | |
| YouTube / Chrome | | |

Enhanced detection with the DUMP permission isn't implemented yet. It comes next,
if Q2 shows it's needed.

## Q3. Engine B double audio: implemented, needs device testing

**Technique** (confirmed by reading RootlessJamesDSP's source; ours is an
independent implementation, no GPL code copied). Playback capture receives a
player's audio *before* its session effects. So putting a top-priority
`DynamicsProcessing` with input gain −200 dB on the source session mutes what you
hear, while the capture still gets the clean signal.

| Piece | File |
|---|---|
| Mute the source session, and re-assert if another app takes over the effect | `SourceMuter.kt` |
| Find sessions: OPEN/CLOSE broadcasts, plus the `audio` service dump with DUMP granted | `SessionReceiver.kt`, `PlaybackSessions.kt` |
| Exactly one engine per session (A and the mute share one DynamicsProcessing engine) | `SessionRouter.kt` |
| **Our addition:** per-app check of whether capture actually works. Mute, listen to that UID only for ≤2.5 s; any real audio → Engine B; only zeros → unmute and use Engine A. The verdict is cached per app. | `CaptureCompat.kt` |

Without that check, an app that opts out of capture (e.g. Spotify) would be muted
and never re-rendered, leaving total silence.

**Setup (one-time, over ADB or Wireless debugging + LADB/Shizuku):**
```sh
adb shell pm grant dev.equalizer.app android.permission.DUMP        # find every session
adb shell appops set dev.equalizer.app PROJECT_MEDIA allow          # optional: skip the capture prompt
```

**Test:** grant DUMP, tap 4 (start capture), then play in YouTube Music or a local
player. Expect to hear one processed copy with no echo or phasing. Tap 6 to see
the routes, then try Spotify (expect ~2.5 s of silence once, then it plays via
Engine A).

| App | Verdict | Single copy heard? | Notes |
|---|---|---|---|
| | | | |

**Known risks to watch:**
- Two simultaneous captures (mixed + per-UID check) may be refused on some
  builds. The check then fails "inconclusive" and the app stays on Engine A.
- An app paused during its check would be wrongly marked BLOCKED. The check
  only runs while the dump says `started`. Button 7 clears the verdicts.
- Another effect app (Wavelet, etc.) on the same session takes over the mute.
  The router drops the session and logs it.

## Q4. Engine B real-world cost

For each quality mode, check that the audio doesn't drop out, and measure the
added latency, CPU load and battery drain over 30 minutes:

| Device | Efficient | High Quality | Audiophile | Extreme |
|---|---|---|---|---|
| | | | | |

Also run `eqcore_bench` on the device (`adb push` the binary, built with the NDK)
to get raw DSP cost.

## Q5. Android 15+ specifics

- Does capture survive screen-off, and switching apps for 30+ minutes?
- Is "screen share protection" the only developer-options change needed?
- Is the MediaProjection consent prompt shown every session? (UX impact)
