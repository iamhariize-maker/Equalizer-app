# Spatial continuity follow-up — 7 October 2026

The owner reported that Detailed can obscure foreground detail on some music, that its
fallback interrupts playback, and that the capture engine stops during light phone use.
The source is the beta-2 app branch, `claude/codex-audio-crackling-amplifier-gkj007`.

The owner conditionally authorized native work: “If you can definitely improve the quality
without causing side effects, you may touch C++ DSP.” The changes below are checked for
timing, center preservation, bounds and continuity. This does not establish a preference
for the processing on commercial recordings or guarantee behavior on every phone.

## What changes

- Auto is an explicit additional spatial choice. Existing Fast and Detailed preferences
  remain selected. Auto uses local audio cues, not artist names, metadata, genre guesses,
  uploaded audio or a trained model. It only blends the two spatial paths at the listener's
  existing Backing/Binaural amounts; it never turns those dials up automatically.
- Detailed-capable capture retains the same N-frame spatial delay while switching to
  Fast, Detailed or Auto. The shared vocal, Space and Instruments path stays in place;
  only the two spatial deltas blend. Manual/load transitions take 50 ms. Steady live Fast
  matches the existing Fast processor after delay alignment.
- A capture session started in Fast retains its existing lower latency and needs a restart
  to allocate Detailed/Auto. A session started in Detailed/Auto can change all three modes
  live and keeps its extra delay until capture stops. Rate/quality changes still need restart.
- An underrun fades spatial work to Fast without rebuilding capture, output, EQ, limiter or
  oversampling. Ten seconds without new underruns lets the selected mode resume. Buffered
  playback can cover a late DSP block; one deadline miss no longer triggers a restart.
- Settled Fast and zero spatial amounts avoid spatial FFT work, while their delay rings keep
  advancing. Detailed warms its statistics for 200 ms plus a 100 ms ramp after resumption.
- Buffer recovery is checked about every 250 ms of audio, separately from two-second
  diagnostics. Only new underruns count. Six seconds of continued new underruns at maximum
  buffer capacity still fails open; an isolated/recovered stall does not accumulate forever.
- Output reserve starts at 80 ms rather than 40 ms, with capacity to grow to 240 ms rather
  than 120 ms. This favors continuity and can add playback delay. Actual buffer/queue readings
  remain visible; these constants are engineering bounds, not measured phone latency.
- High-rate transport may still reopen at safe 48 kHz after sustained failure. Blocked/silent
  capture still returns source audio to system effects. No Android/DRM capture policy is bypassed.

## Auto's experimental cue bounds

The existing FFT measures residual and mid/side energy in the Binaural body/air region.
Residual share begins eligibility at 1.5% and reaches full eligibility at 5.5%; a side/mid
power ratio from 0.25 to 0.5 reduces eligibility. A >3 dB rise in mid power per hop or a
mid crest above roughly 14 dB holds it back, with 400 ms activity decay. The target falls
with an 80 ms time constant and rises with 1.5 seconds, then passes through the bounded
sample-level blend. No center/no sustained eligible residual means no Detailed request.
Stable endpoints snap within 2% of Detailed or 0.5% of Fast so an unchanged Auto decision
does not keep both paths running indefinitely. Auto still needs analysis to detect section changes.
These are first heuristics tested on synthetic counterexamples, not validated music classes.
The UI calls Auto experimental and reports the actual Detailed mix percentage.
The existing compiled rule registry remains unchanged; Auto is an explicit spatial-mode
controller, not new evidence admission in the Svaramanas planner.

## Evidence and limits

Verification results are recorded in `CODEX_STATUS.md`. New tests cover stable decorrelated
beds versus repeated foreground attacks, rapid mode/load changes at 44.1/48/96 kHz, aligned
Fast equivalence, unchanged mid, switch continuity, time-based recovery, counter resets,
concurrent setters and no audio-thread allocation. Android's release JNI lab also exercises
all three modes and the load limiter at each of those rates.

Phone listening and background-load tests remain necessary. In particular, the named Wilco,
Amit Trivedi, A. R. Rahman and Zubeen tracks have not been tested here. Source-file/DAC rates
remain unknown even when a player labels content lossless or HD. A preview is not an
owner-signed public update; signing and public publication are separate steps.

The owner confirmed the affected setup: TECNO with Fosi IM4 and Realme Air 8, using Spotify
lossless, Amazon Music HD and YouTube Music. Validate all six player/output combinations,
including a track change, screen-off playback and ordinary phone/app use. For each, compare
manual Detailed and Auto on the same familiar passages, and record actual underrun deltas,
Detailed mix, buffer/queue values and whether foreground detail is clearer. This is a phone
test plan, not a completed compatibility result.
