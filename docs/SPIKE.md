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
| | | | | | |

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

## Q3. Can Engine B silence the original stream? (the open problem)

Tap **"4. Engine B: start capture"** and play something. Today you'll hear
**both** the processed copy and the original. RootlessJamesDSP solves this, but
how isn't documented in its README. Candidate approaches to test:

1. Read RootlessJamesDSP's source to see what it actually does. It's GPL, so read it to learn the technique, don't copy code.
2. Attach a DynamicsProcessing to the source session with input gain −∞ dB
   (silence the original via Engine A). Needs to be checked: does capture tap the
   stream before or after session effects?
3. Shizuku-assisted hidden APIs: route the source app's playback only to the capture policy.

Record which works on which Android version. **This decides whether Engine B ships.**

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
