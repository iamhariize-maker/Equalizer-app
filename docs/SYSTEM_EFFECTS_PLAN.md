# Making system effects a product, not a fallback

Status: plan (written 9 Oct 2026). Nothing here is built unless marked. Every quality claim below is a hypothesis
until a test in `android/scripts/` or a listening protocol in `SONIC_IDENTITY.md` has measured it.

## Why

Capture (Engine B) depends on each app allowing it and on Android continuing to allow it. A TECNO LH7n report
(Spotify 9.1.90.2270) showed the audio server holding `0x8600` effective flags (NO_MEDIA_PROJECTION set) for Spotify's
track while the Java player list said `0x0`. No public API gets that audio. Engine A (DynamicsProcessing on the app's
own session, the route Android sanctions for equalizers) kept working on the same track (`PROCESSING` verified).
If the one app most listeners use can close capture, the system-effects path must stand on its own.

## What Engine A already does (from `GlobalEqEngine`)

128-band pre-EQ, 4-band multiband compressor (bass feel, vocal smoothness, level evening), limiter, input gain,
verification against the audio server's effect list. CI measures EQ response end to end within about ±1 dB.

## What it cannot do (be honest about these)

DynamicsProcessing is per channel and gain-based. No mid/side or cross-channel processing, no oversampling, no dither,
no nonlinear bass shaping, no access to the audio. Effects also cannot attach to offloaded/compressed tracks, which the
verification already reports as bypassed. So the gap to Engine B is those extra processors, not the EQ curve.

## Workstreams, in the order I would do them

1. **Proof on every app (small).** Show, per app, "system effects verified: processing" next to the capture status
   (stage D8 of the diagnostic already knows it). Add a "Verify my EQ" self-test: Svan plays test tones through its
   own session with the same effect, reads the level back with a Visualizer (the method of `ResolutionProbe`), and
   reports measured vs requested dB at 20 frequencies. Differentiator: measured accuracy, not claims.
2. **Use the stages DynamicsProcessing gives us (medium).** Post-EQ is off today (`false, 0` in `buildConfig`).
   Experiment: tonal target in pre-EQ, fine correction after the compressor in post-EQ, so dynamics react to the
   corrected signal, not to boosted bands. Measure transfer curves with tone bursts in CI before shipping.
3. **Gain staging (medium).** Input gain = minus the largest boost, limiter as the last guard. Test on loud masters
   with the host capture the e2e suite already has. Only then claim "does not clip at your boosts".
4. **Real multiband dynamic EQ (medium).** Raise MBC bands from 4 to 8 or more to give a program-dependent EQ closer to
   Engine B's selective dynamic EQ. Unknowns to measure first: device limits on band count, binder cost (about 2 ms per
   band on the TECNO), audio-server CPU.
5. **An analysis tap for Engine A (small experiment, then a decision).** Svaresa is static on Engine A because it
   cannot hear the audio (`SMART.md`: "Engine A analysis tap is still unbuilt"). The sanctioned candidate is a
   `Visualizer` on the app's session (measurement mode peak/RMS for loudness, optionally coarse FFT), RECORD_AUDIO is
   already requested. Two open points: (a) whether data still flows for a stream that forbids capture is unknown, a
   one-trial lab experiment in the diagnostic answers it; (b) even if it flows, a capture opt-out states the app's
   wish that its audio not be captured. **This is an owner decision**, my recommendation is to run the experiment, then
   decide whether coarse loudness-only measurement (nothing stored, no audio kept) is acceptable for such apps.
6. **Hedge: Svan plays the audio itself (large).** An in-app player for local files (and share/open-with) owns its
   output, so the audiophile engine never needs capture. It is the only route that is immune to app opt-outs. Decide
   after 1 to 4 whether the product wants to be a player too.

## Position without over-claiming

"Two engines, one sound: the audiophile engine for apps that allow capture, verified system effects for everything
else, with measured proof per app." Publish the EQ accuracy and the list of what only the capture engine adds. Do not
claim parity until a listening protocol says so.

## Risks

Capture access is revocable by apps and by Android; treat the card in `CAPTURE_STATUS.md` as the honest interface to
that. Effects can be bypassed on offload and by OEM paths; keep the verification visible. Do not build anything that
captures a stream whose app has forbidden it.
