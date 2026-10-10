# Research choices carried into 0.5.11

The integrated experiment combines the first two methods below through Svan's
existing Engine A owner. Method 1 is the default Lab trial; method 2 is optional
and must improve the reference prediction to be selected. Engine B remains the
existing alternative when capture is permitted. None establishes a listening
preference, vendor response or commercial-player compatibility.

| Candidate and decision | Evidence and confidence | Measurement that would settle it |
|---|---|---|
| **Unique-bin DynamicsProcessing controls, WOLA-aware fitting and modeled input margin.** Implemented as the DP-only Lab fit. | **Verified source:** the [AOSP frequency core](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android14-release/media/libeffects/dynamicsproc/dsp/DPFrequency.cpp) rounds band edges to bins. **Verified local tests:** the integrated planner preserves the original fit, has 64 distinct stops, and retains a narrow 31.5 Hz target. High confidence in control-layout mathematics; vendor equivalence is unverified. | On each phone and route, record the quiet stimulus and single tones at fractional/bin-centered bass frequencies. Compare gain, phase and modulation to the exported prediction, infer block/rate from impulse timing, and measure high-level sample/reconstructed peaks separately. |
| **Reference Equalizer biquads for bass plus a residual DP fit.** Implemented as an opt-in blend, guarded by descriptor/control checks and predicted error/modulation. | **Verified source:** the [AOSP Equalizer](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android14-release/media/libeffects/lvm/lib/Eq/src/) uses time-domain filters; insertion order and the five reference centers were already verified in the source research. Numerical model confidence is moderate; a descriptor match alone does not prove the vendor transfer function. | Measure the 60/230 Hz filters individually and combined, then compare DP-only versus hybrid at matched output level. Check effect-control loss and reconnection, transient peaks, latency and CPU. Reject the blend if measured error/modulation worsens or controls are not reliable. |
| **Playback capture with native time-domain/oversampled processing.** Retained as Engine B rather than made the default for streaming players. | **Verified platform constraint:** [AudioPlaybackCapture](https://developer.android.com/media/platform/av-capture) depends on projection consent and source capture policy. Native core tests pass, but they do not establish phone routing. High confidence that IIR coefficients avoid FFT-bin edge quantization; app/route availability remains conditional. | Establish capture eligibility for the installed player, before/after-mute signal proof and fail-open behavior. Measure downstream response, actual latency, battery/CPU and reconstructed peaks against a separate meter on each route. |

Peak protection must be qualified separately. The DP-only/hybrid report explicitly
disclaims sample-peak and true-peak guarantees: static sinusoidal gain plus margin
does not bound every waveform, and the existing MBC/limiter are outside the static
fit. [ITU-R BS.1770](https://www.itu.int/rec/R-REC-BS.1770) and
[EBU Tech 3341](https://tech.ebu.ch/docs/tech/tech3341.pdf) provide the independent
loudness/true-peak measurement references; a 4× reconstructed-peak test at 48 kHz
is a measurement step, not something this Android limiter is claimed to do.

TECNO HiOS and LG V60 block sizes and vendor band limits remain unresolved.
TECNO's reported acceptance of 1024 controls does not prove 1024 useful frequency
regions. This update does not present public vendor implementation code as found
or infer an implementation from a search snippet. Source inspection already
completed in the handoff is not repeated here; the [existing evidence ledger](SYSTEM_EFFECTS_FRAMEWORK.md)
separately labels bibliographic/search-result leads that were not opened in full.

Basic detection continues to use genuine [session announcements](https://developer.android.com/reference/android/media/audiofx/AudioEffect#ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
and [public playback callbacks](https://developer.android.com/reference/android/media/AudioManager.AudioPlaybackCallback).
An anonymous playback event can prompt repair of a known session; it cannot supply
a hidden session ID or authorize capture. No new unprivileged processing route
has been established by this implementation.
