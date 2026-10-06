# Audiophile engine refinement — 0.5.4

Owner request: give Svaresa more engine authority when that improves audio quality, and keep
improving the audiophile path. This increment addresses control transitions and protection;
it does not force additional bass, vocal focus, stereo widening or a higher CPU quality mode.

## Built

- Native live EQ edits crossfade the previous and next filter cascades for 10 ms. The first
  configuration before playback remains immediate. Further requests during a transition coalesce
  into the latest request instead of continually restarting the fade. Frequency/type/Q identities
  preserve surviving histories; zero/disabled bands retain slots and cost no steady sample work.
- Preamp/headroom changes use a linked 10 ms linear gain ramp. Both channels see exactly the same
  gain. This adds transition time, not audio lookahead or output buffering.
- Coefficients, histories and 512-sample transition buffers are preallocated. Publication still
  uses a nonblocking try-lock; the audio thread does not wait or allocate. Both cascades run during
  an edit, increasing temporary DSP cost; phone cost still needs measurement.
- Active Svaresa keeps predictive headroom and overload protection enabled on both engines.
  Saved manual choices are untouched and return when Auto master is disabled or Your EQ is chosen.
  Protection stays linked while comparing or switching EQ off, avoiding protection changes between
  comparison halves. The gain-staging controls explain the effective state.
- Capture protection switches live through JNI. Protection/system-resolution edits no longer
  rebuild the capture DSP; quality, dither and output word length still require a rebuild.
- EQ adaptation no longer resets gain-protection history. Its existing 250 ms recovery continues
  through edits instead of being reset on every automatic plan or fader change.

## Measured regression cases

The new tests first exposed three failures in the old implementation: removing a shelf, activating
a previously zero-gain slot, and changing preamp caused abrupt steps.

On a 0.1-amplitude DC signal with a +6 dB low shelf, removing the shelf previously changed output
by 0.099526231 in one step. The new first step is zero; the largest adjacent transition step is
0.000207346. A +6 dB preamp edit gives a maximum step of 0.000207350 instead of 0.099526231.
Those approximately 480-times-smaller steps describe these controlled tests, not a general sound
quality score or a guarantee that every extreme filter edit is inaudible.

Additional tests cover block-size-independent transitions, eventual adoption of the latest queued
curve, unchanged settled response, preserved filter history at zero gain, stereo-linked gain and
live protection/history retention. Combined EQ/headroom transitions are checked at 1x/4x/8x
for linked stereo and bounded sample peaks. The full native suite is 86 tests. Three new JVM regressions
bring the suite to 94, covering authority, bypass/manual restoration and format-only rebuilding.
The EQ workspace emulator script grows from six to nine checks, retaining measured output and
adding requested-versus-effective protection/restore checks. Personal adjustment is tested with
a cut, since predictive headroom can intentionally cancel absolute gain at a boosted band's peak.
The existing eight touch, 39 audio/routing and ten release detection checks remain.

## Limits and next work

This does not upgrade Android's system-effect coefficient transition behavior or precision.
Engine A still cannot analyse music; it uses output/volume/night context. Engine B analyses only
capturable playback. Protection remains sample-peak based, not a true-peak limiter, and headroom
can make a processed signal quieter than a loudness-matching estimate. A source clipped upstream
cannot be restored by output gain protection.

Next quality work should earn its place through measurements and listening: reconstructed-peak
protection with bounded lookahead, reduction-only dynamic EQ, calibrated headphone/volume profiles,
a listen-only tap where capture is permitted, output-path latency measurements, and blind matched
comparisons. Increasing oversampling beyond what the current path needs or inventing codec detail
does not establish improved fidelity. Android's mixer, source capture policy, codec and hardware
remain limits. No IM4-specific correction, commercial-player compatibility or listening preference
is claimed from synthetic checks. No new permission or notification access is introduced.

CI validation and final screenshots must pass before delivery; the downloadable APK's verification
record contains the exact successful workflow, artifact and checksum.
