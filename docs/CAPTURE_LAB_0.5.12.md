# Svan 0.5.12 — Lab in native capture

Apply the Lab before starting capture or while capture is running. There is no longer an instruction
to stop capture to apply the experiment. The actual captured path uses an original native realization
of the selected 2048/4096/8192-frame frequency-domain controls, with the guarded reference bass blend.
No Android EQ is added to Svan's replay AudioTrack and the source muter/routing policy is unchanged.
A player that refuses capture remains on system effects; enabling capture is not proof that every
player is captured. The user's listening report motivated this feature, but does not establish the
route, cause, or sound-quality superiority of the previous result.

## Processing and fitting

System effects retain the AOSP-based preferred-block prediction, because the vendor's rate and
implementation remain unmeasured. Capture knows its client rate and FFT size. It uses double precision,
symmetric sqrt-Hann analysis/synthesis, N/2 overlap, N-frame static-EQ delay and the model's fixed-unity
Nyquist bin. The exact selected block is reflected in native latency and the Lab status. At 48 kHz,
8192 frames add 170.67 ms before the existing oversampler, spatial and peak-protection delays. This is
DSP latency, not an end-to-end measurement.

One off-thread fit prepares an Engine A plan plus native capture plans at 44.1 and 48 kHz. Capture fits
its own effective static bands, preamp and headroom at the selected oversampling rate. It excludes
Engine A's static vocal stand-ins, because capture has actual M/S processors. Both plans retain the
64 unique-bin layout, gain bounds, bass-error and modeled-modulation guard. Hybrid may be selected
for one path but not the other. Capture implements the reference 60/230 Hz coefficient data directly;
it does not require a vendor Equalizer descriptor. System-effect hybrid still requires matching
Android controls. Selecting blend is a request: the numerical guard may choose WOLA/DP only.

Capture chain when Lab is selected:

1. Frozen Lab operating attenuation (includes the fitted target's preamp/headroom contribution).
2. Existing selected oversampler up/down response, with the ordinary parametric cascade bypassed.
3. Reference bass biquads when the guarded blend selects them, then the WOLA controls.
4. Existing bass character/Resolve/unmask, stereo/spatial tuner, grounding and dynamic EQ.
5. Existing native linked reconstructed-peak protection, then the selected dither/output conversion.

There is a single static-EQ response and operating attenuation. Normal EQ bands/preamp are retained
as settings for Restore but are not applied again during Lab. Final native protection observes the
result of the Lab and downstream dynamic processors. It obeys the existing effective protection
settings. The model, sampled response maximum and operating margin are not an arbitrary-waveform
sample/true-peak guarantee, and the system DP limiter does not gain that guarantee either.

The native implementation allocates only during epoch construction. Process is allocation-free and
uses fixed buffers/precomputed FFT tables. Exactly identical stereo input can share one FFT/IFFT pair
per hop, while retaining independent channel filter and overlap histories. Near-mono is not rounded.
The transition back to independent stereo is checked bit-for-bit against two separate mono engines.
Normal capture constructs no Lab FFT buffers and performs no Lab FFTs.

## Selection lifecycle

Lab is a frozen comparison. While fitting or selected, periodic automatic *curve* planning is held;
input analysis, meters and native dynamic processors continue. This prevents capture startup or the
next three-second smart update from silently clearing the fit. Saved smart-controller preferences
are not changed. Restore or an explicit sound edit resumes the normal automatic curve.

A live selection change fades the current block and reopens capture's audio epoch with the same
MediaProjection and quality. The output briefly rebuffers. Each epoch builds immutable coefficients
before processing; no FFT resize or coefficient allocation occurs in process. Source mutes remain
under the router's existing ownership/proof checks; a failed reopen exits through normal fail-open
cleanup. Lab reopen is separate from the existing one-shot safe-rate/underrun recovery allowance.
Sound/settings edits, system-engine stop or process restart restore normal Svan. A route-only
system-only/capture mode switch or mix-fallback preference does not invalidate the frozen curve.
Plans are not saved
as an automatic startup default.

Capture above 48 kHz retains normal native processing: no compatible reference fit is supplied for
88.2/96/176.4/192 kHz. The UI identifies that case. Lab does not silently change the chosen rate or
quality. Native system-effect block sizes and latency remain vendor assumptions.

The recording tab records the actual wet output, and metadata includes the Lab block, blend,
attenuation and static controls. A Lab change closes the old recording at the epoch boundary. The
separate eight-second *raw reference* clip recorder requires restoring normal Svan because its
existing offline comparison renderer does not reproduce Capture Lab. Imported WAV comparisons
continue using their native comparison renderer; they are not a capture-Lab comparison.

## Verified numerically; phone listening still required

- Native WOLA is checked against all six unchanged bundled matrices at seven frequencies each,
  including 20/31.5/60/100 Hz and a reference bass blend. Rendered steady carrier response agrees
  with the prediction within 0.003 dB in those fixtures. This establishes model realization, not
  target accuracy, sound preference, or a match to a physical vendor device.
- Unity insertion response, fixed delay, chunk independence, stereo isolation/history, reset,
  configuration rejection and no process allocations are checked.
- An Audiophile capture fixture confirms normal EQ/preamp are not applied twice and observes the
  final protected output with a separate reconstructed-peak measurement.
- JVM tests verify coefficient-table equivalence and frozen capture control arrays.
- `android/scripts/capture_lab.py` checks pre-apply/start, live apply/restore without new consent,
  unchanged quality, one-EQ/one-margin downstream host PCM, exact added delay, sound-edit restore
  and automatic-curve hold on real API 33/34 Android emulators. Emulator output is not a phone/DAC
  measurement. Actual results and failures are retained in the validation package.

On TECNO HiOS and LG V60, compare the same source/volume/output route, with operating margin and
levels matched. Record output, measured latency, peaks, underruns and DSP load. Keep capture admission
and fallback status with each take. A blinded, level-matched preference comparison is needed for a
sound-quality claim. Battery qualification must hold block/quality/settings constant.

## Reference sources and provenance

These model/control facts were verified in the prior research/source handoff, not inferred from
search snippets. This implementation uses Svan's original model generator and coefficient assets;
no GPL DSP is copied and no vendor-source or TECNO/LG block-size claim is made.

- [AOSP DynamicsProcessing frequency core](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android14-release/media/libeffects/dynamicsproc/dsp/DPFrequency.cpp)
- [AOSP DynamicsProcessing base](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android14-release/media/libeffects/dynamicsproc/dsp/DPBase.cpp)
- [AOSP Equalizer biquads](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android14-release/media/libeffects/lvm/lib/Eq/src/)
- [Svan model generator](../tools/lab/generate_models.py), [planner](../android/app/src/main/java/app/svan/lab/core/Planner.java),
  [original native realization](../core/src/lab_eq.cpp), [native regressions](../core/tests/test_main.cpp).
