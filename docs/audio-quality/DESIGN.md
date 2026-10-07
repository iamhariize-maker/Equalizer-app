# Svan: spatial definition, Bass Resolve, mastering control and high-resolution capture

Engineering handoff, 7 October 2026. Audited source: `7b627770a78b7d3dc267754e0c2995b608751525`.
Implementation target: the next development version, provisionally 0.5.7 / versionCode 14.
Do not replace the already published 0.5.6-beta.1 artifact or merge PR #6.

This is a design and executable reference package, **not an integrated Android feature release**.
Start with [the implementation prompt](../CODEX_AUDIO_QUALITY_IMPLEMENTATION_PROMPT.md).
Original numerical code is in [tools/audio_quality_reference](../../tools/audio_quality_reference/README.md).
The constants below are conservative starting hypotheses unless explicitly described as measured.
They require the listed listening and device gates before public sound-quality claims.

## 1. What the investigation establishes

### The owner's preview and the missing knobs

The newly attached `Svan-preview (1).apk` has SHA-256
`c1ade065d5a3846383dd35c62ec26ea6d7c26b9fd3c9406d98f77bb4d24e4d14`.
It is byte-identical to the earlier supplied preview: `app.svan`, versionCode 13,
versionName `0.5.6-continuity-preview`, minimum API 29, target API 36.
Its DEX contains `Backing vocals`, `Binaural`, `backingVocals`, and `spatialDetail`.
This establishes that those labels/fields exist, not which screen was visible on the owner's phone.

In the audited source, `SoundScreen.kt` always includes `InstrumentTunerCard` in the Sound list.
Inside **Orchestral amplifier**, Space and Instruments occupy the first row; Backing vocals and
Binaural occupy the second row. They are already connected through model/JNI/native DSP.
Do not add duplicate parameters. Reproduce visibility with actual screenshots, including a scrolled
screen and large fonts, then move these two requested controls to the first row. Add a stable
navigation target and a clear per-player state: applied, manual, automatic, off, or unavailable on
the current engine. The system-effects route cannot run this native stereo processor.

The user reports successful playback with Amazon Music HD and Spotify lossless. Treat that as
positive listening/compatibility evidence for that phone and setup. It does not identify the active
engine, capture rate, source bit depth, or physical output format. Earlier BHIM/GPay success was
reported after removing Shizuku **with Developer options still enabled**; preserve that fact.

### P0: a measured stereo phase defect must precede stronger controls

`core/src/stereo.cpp` splits S into fourth-order Linkwitz–Riley low/high branches around 180 Hz,
then recombines them, while M does not receive the equivalent phase response. Their sum has flat
magnitude but an all-pass phase rotation. At the crossover it approaches inversion. With
`L=M+S, R=M-S`, inversion of S swaps the stereo channels at that frequency.

[The reproducible probe](../../tools/audio_quality_reference/stereo_phase_probe.cpp) links the real
production `stereo.cpp`, feeds a left-only tone, and measures the settled output at 48 kHz:

| Setting | Tone | Left relative to original left | Right relative to original left |
|---|---:|---:|---:|
| Backing vocals = 0.000001 | 180 Hz | -160.08 dB | 0.00 dB |
| Backing vocals = 0.01 | 180 Hz | -80.08 dB | +0.00015 dB |
| Binaural = 0.000001 | 180 Hz | -141.43 dB | 0.00 dB |

The mono sum remains correct to about `1.4e-17` in these fixtures. Consequently, existing mono-sum
and side-RMS tests can pass while a bass component changes sides. This is a synthetic demonstration
of the audited native code, not a measurement of the owner's live phone. Full output:
[current-stereo-phase.csv](current-stereo-phase.csv).

Also address the mismatched orders of the mid/side filters used by the motion detector: a detector
must not interpret its own unequal phase delays as movement in the recording.

### Other concrete constraints in the current implementation

| Area | Audited behavior | Consequence |
|---|---|---|
| Backing/spatial | Static side boosts plus dynamic side boosts can stack | A louder side signal alone is not improved separation |
| Bass Feel | Independent channel envelopes can reach approximately +/-12 dB | Strong settings can alter note shape and stereo balance |
| Headroom | `Engine::updateGain()` primarily budgets static EQ peaks | Downstream bass/stereo boosts need a combined budget |
| Dynamic EQ | Four lanes around 120/330/3000/6500 Hz infer prominence | Strong intentional notes can resemble resonances |
| Automatic controls | Manual/automatic values partly combined by max/add conventions | Intent and effective ownership need explicit representation |
| Capture | Record, engine and playback fixed at 48 kHz, stereo PCM float | Original high-resolution source rate is not preserved by assumption |
| Dither | Already off by default; native DSP uses doubles | Do not invent a default-dither defect or claim double precision restores lost data |
| Playback queue | Primed frames excluded from `writtenFrames`; head position not unwrapped | Queue/long-run diagnostics need correction before latency claims |
| Listening clips | `ClipRecorder` assumes 48 kHz | Rate negotiation must update recording/export/rendering together |

## 2. Orchestral amplifier: definition without overpowering the musical core

### Meaning of the controls

Assumption for this handoff: **Binaural enhances spatial information already present in the
recording**. It does not generate binaural-beat tones, add a synthetic surround room, or promise
HRTF localization. No reply to the optional preference question was available during design.

Backing vocals should improve audibility of eligible diffuse vocal-range detail. Binaural should
give eligible spatial detail body and articulation, including lower midrange, rather than only
lifting upper treble. Neither stereo statistics nor frequency ranges identify an actual singer.
Use help text explaining that centered/coherently panned backing vocals may remain unchanged.
Do not label a covariance residual as isolated backing-vocal stems.

The desired quality is constrained enhancement: retain direct sound, preserve bass/attack cues,
avoid a thin high-frequency halo, and stop adding energy when the mix is already wide. Full knob
travel is a maximum request, not a promise of maximum gain on every recording.

### Signal path: phase-consistent dry plus a controlled side delta

Use one coherent design for **all** side controls, including Space/Instruments. Leaving the old
side crossover in front of new Backing/Binaural code would retain the measured defect.

```text
input -> preamp / static EQ -> BassShaper + Resolve envelope guard
      -> StereoTuner [existing mid controls; new phase-consistent side stage]
      -> coordinated selective DynamicEq -> true-peak limiter -> float output
```

For the spatial stage:

```text
M = (L + R) / 2
S = (L - R) / 2
Mout = delayed M
Sout = delayed S + bounded residual delta
Lout = Mout + Sout; Rout = Mout - Sout
```

The dry branch is a delayed identity, not a filtered low/high reconstruction. Zero delta must give
the original channels after alignment. This preserves the mid signal at the stage boundary;
later global gain or limiting can still change the lead level and must be checked separately.

Proposed implementation: weighted overlap-add (WOLA), real FFT, periodic square-root Hann analysis
and synthesis, hop `N/4`, with `N = nextPowerOfTwo(fs * 1024 / 48000)`. Accumulate the **delta**,
normalize by overlap window-square sums, and add it to the delayed dry side. Explicitly handle
DC/Nyquist and startup/drain. Use a fixed streaming delay of N frames as a conservative contract:
1024/48000 = 21.33 ms; 1024/44100 = 23.22 ms. Confirm impulse timing in the actual port.

This is additional latency, not free lookahead. Add it to `Engine::latencyFrames`, capture queue
diagnostics and blind-listening alignment. Keep delay constant for an engine lifetime, even when
a control turns off; change topology/delay only through a defined stop/restart transition.
If this latency is unacceptable for a route, expose the lower-latency capability honestly rather
than claiming the full algorithm is active. FFT plans, buffers and tables are created off the audio
thread; no allocation, mutex wait or JNI call inside the processing loop.

### Conservative primary/residual estimation

Per frequency bin, accumulate aligned second moments, initially with a 150 ms time constant:

```text
Pmm = E[|M|²]; Pss = E[|S|²]; Csm = E[S conj(M)]
coherence = |Csm|² / (Pmm Pss)
beta = Csm / (Pmm + lambda)
A = S - beta M
```

`A` is a decorrelated residual. It may contain ambience, double-tracking, instruments, reverberation
or noise. It is not source separation. Skip enhancement for invalid/inconsistent covariance,
silence, coherence >= 0.98, absent mid energy, or side power already >= 0.5 mid power. Pure-side
material is passed unchanged. Warm up statistics before enabling enhancement and reset/fade on
track/route/format discontinuities. The prototype uses 200 ms warmup; tune against real recordings.

Use common aligned analysis for M and S. Smooth eligibility in time and frequency; avoid binary
per-bin switching and musical noise. A moving coherent source needs a conservative motion/onset
guard because a lagging covariance estimate can temporarily mistake it for diffuse detail.
Measure that case explicitly; the reference prototype does not yet implement that guard.

Initial masks and gain requests, to be listening-tested:

| Control | Frequency support | Maximum requested residual boost |
|---|---|---:|
| Backing vocals | Smooth rise 250–450 Hz; broad vocal body; taper 3.5–6 kHz | 4 dB |
| Binaural | Smooth rise 180–350 Hz; broad body/detail; taper above 4 kHz toward 12 kHz | 3 dB |

Combined request: `min(4 dB, backingRequest + binauralRequest) * confidence * (1-onsetGuard)`.
Smooth boundaries, preserve the bass anchor below 180 Hz, and avoid adding reverberation tails or
random decorrelation. Apply a shared budget to Space/Instruments too; their semantics must be
ported explicitly rather than silently deleted. Keep intentional user widening distinct from
automatic residual enhancement in the state/reason display.

### Exact side-energy constraint

For proposed complex delta D and scalar amount `a`, the band energy is:

```text
Pout = Pss + 2 a q + a² Pdd,  q = Re E[S conj(D)]
limit = min(0.5 Pmm, Pss * 10^(4/10))
```

If the original side is already at/above the limit, add nothing. Otherwise the nonnegative exit
root gives the maximum allowed a. For positive q, the stable form is:

```cpp
double slack = limit - pSide;
double disc = std::sqrt(q*q + pDelta*slack);
double a = q >= 0 ? slack/(disc+q) : (disc-q)/pDelta;
a = std::clamp(a, 0.0, 1.0);
```

See the guarded implementation `sideScale()` in `quality_policy.h`, including covariance validation
and early exits. Compute moments over the **same aligned ERB-like band and window**. Smooth desired
gains, then reapply the budget to the actual frame gains; smoothing must not spend tomorrow's budget
on today's transient. The offline prototype has an instantaneous band budget but still needs a
production anti-chatter strategy. Overlapping synthesis frames also require output-domain checks.

`Pss/Pmm` is an energy-width proxy, not universally equal to an L/R correlation coefficient. For
actual correlation use full L/R moments, including unequal channel powers. Band energy limits do
not guarantee a sample/true-peak ceiling. Add a shared peak/headroom governor using the actual
combined signal, and test limiter activity: added detail should not make the limiter pull down the
lead by more than a proposed 0.2 dB versus an otherwise identical, level-aligned baseline.

### UI and listening gates

- First row: Backing vocals and Binaural; second row: Space and Instruments. Preserve fine knob
  gestures, scrolling, accessibility names and the existing gold/charcoal design.
- Show requested versus effective amount and a short reason, e.g. “Holding: already wide” or
  “Available with Audiophile processing”. Do not claim native DSP is active on the system-effects route.
- Preserve manual values. Provide Off / Manual / Auto ownership without conflating zero with Auto.
- Test 320/360/411 dp, larger text, portrait/landscape, scrolling, TalkBack labels and restoration.
- Blind comparisons: match loudness within 0.1 dB; include vocals centered and panned, doubled voices,
  dry mono masters, live ambience, phase-inverted material, hard-panned bass and moving percussion.
  More side RMS is a diagnostic, never a perceptual pass criterion on its own.

## 3. Bass Resolve: preserve the note before attempting to remove masking

Bass notes contain onset, fundamental, harmonics, body and decay. A fast bass envelope can follow
individual cycles, distort sustained notes, and flatten articulation while making the bass louder.
Therefore Resolve is **not another bass boost, exciter, gate or transient exaggerator**.

### A. Protect the existing Bass Feel envelope

Use a common, stereo-linked detector to prevent channel-dependent gain movement. Keep the existing
complementary low-plus-dry-delta topology; do not reintroduce the earlier non-complementary crossover.
Preserve saved Amount, Focus and Feel settings. Resolve limits their *dynamic envelope contribution*,
not the user's static bass shelf.

At Resolve r in [0,1], cap the existing requested dynamic change in dB:

```text
onset cap = (1-r)*12 + r*1.5
body/tail cap = (1-r)*12 + r*0.75
effectiveFeelDb = clamp(requestedFeelDb, -cap, +cap)
```

`guardedCharacterDb()` provides this kernel. The numeric caps are a proposal, not psychoacoustic
certification. Use cycle-aware envelopes and rate-independent smoothing, retain quiet decay rather
than threshold-gating it, and use aligned pre/post-BassShaper measurements to detect processing-added
shape damage. Normalize the comparison for the expected static EQ/shelf response, otherwise intended
bass gain will be misclassified as a defect. Freeze learning while parameters are changing.

### B. Selectively reduce sustained masking only with enough evidence

Separate a fast onset detector from a longer bass analysis window. A 30 Hz period is 33 ms; a 5–10 ms
window cannot reliably distinguish its sustain/harmonics. Use about 128–170 ms for low-frequency
analysis, optionally downsampled to approximately 2 kHz with a proper anti-alias filter. Protect
estimated fundamentals and harmonics across at least 40–600 Hz. Polyphonic pitch uncertainty must
reduce authority, not fabricate a confident note estimate.

Proposed gentle lanes: 70, 110, 180 and 280 Hz, Q about 1.4. Measure local prominence against
aligned neighboring bands, but never equate prominence alone with unwanted resonance. Require all:

- Valid, same-epoch evidence no older than 0.5 s and confidence >= 0.8.
- Band level above -60 dBFS, onset age at least 50 ms, excess sustained at least 150 ms.
- More than 6 dB excess, with a separately established low probability of intentional tonal content.
- No unmodeled route, rate, parameter transition or noisy/silent capture state.

Then request:

```text
cutDb = resolve * confidence * (1-harmonicProtection)
        * clamp(0.5*(excessDb-6), 0, 1.5)
```

Unknown harmonicity is **invalid evidence**, not zero harmonic protection. The reference supplies
the decision rule, not a completed harmonicity classifier. Until the detector is validated, ship
the envelope-preservation part and leave speculative unmasking inactive, with honest diagnostics.

Smooth attenuation with an initial 50 ms attack / 400 ms release. Constrain the sum of actual
smoothed serial lane reductions to 2 dB, and verify the complete transfer response at all rates.
Use attenuation-only stable filters with bounded coefficient transitions and warm state. The
existing DynamicEq 120/330 Hz lanes must relinquish ownership while these lanes are active;
otherwise two controllers will cut the same note. Keep the upper DynamicEq lanes independent but
inside the overall gain budget.

An onset that arrives while a cut is already active is not automatically preserved by “don't start
new cuts for 50 ms”. Relax existing attenuation using available, explicitly accounted lookahead,
or document and measure the residual onset alteration. Do not promise zero attack damage from a
purely causal slow-release controller. Test abrupt notes after ringing passages, not just an onset
from neutral state. Proposed clean-fixture onset/body error target: <=0.5 dB relative to the intended
static response; listening tests can demand stricter limits.

### C. Auto master ownership

Add `BassResolveControl(owner, manualAmount, automaticCeiling)` to persisted state. Default new
ownership to Off on migration; let the user enable Auto once. With Svaresa Auto master on and valid
native measurements, Svaresa can then control effective Resolve within that ceiling. When Auto master
is off, automatic ownership becomes inactive; do not silently overwrite the saved manual amount.
Explicit Off always wins. Manual mode remains available with normal protective limits.

Suggested display: “Bass Resolve · Auto · 42% · protecting sustain”. Never display a fabricated
instrument label such as “bass guitar detected” unless an actual validated detector supplies it.

### Required bass fixtures

Clean 30/40/60/100 Hz sines; decaying harmonic notes; legato overlapping notes; plucked bass and
kick coincident/noncoincident; slow synth sweeps; intentionally resonant synth; quiet sustained tails;
sub-bass under wide guitars; unequal L/R bass; silence; abrupt parameter changes; a deliberately
added low-mid resonant tail with a known clean reference. Measure frequency response, onset/body/tail
error, distortion/sidebands, stereo-image drift, false cuts and cleanup against known ground truth.
Clean tonal fixtures should remain neutral; “makes every recording sound different” is not a goal.

## 4. Svaresa and Svaramanas: executable engineering knowledge

The current controllers are deterministic signal-processing/planning code, not a language model
that can be taught all mastering knowledge with a paragraph. Expand them with explicit measurements,
feature contracts and tested rules. Do not use a universal spectral tilt as an objective definition
of a good master; do not infer codec identity from missing high-frequency content or physical SPL
from an Android volume percentage.

### Separate three timescales and responsibilities

1. Native audio-frame protection: finite samples, peak budgets, control smoothing, transient guards,
   stable filters, bounded gain, no allocation/wait. It can reduce a risky request immediately.
2. Svaresa policy, initially about every 250 ms: select bounded effective parameters from valid
   measurements and user ownership. It must not chase a single FFT frame or repeatedly toggle states.
3. Svaramanas slower intent/tonal planning, currently about every 3 s: bounded EQ/tonal proposals,
   reason reporting and preference integration. Keep the existing gradual movement, then revalidate
   the *combined* response, rather than adding independent “good” boosts.

These times are implementation starting points. Express all attack/release/aging in seconds, not
callback counts. The same song at a different sample rate or block size should get equivalent policy.

### Measurement and action contracts

```cpp
struct MeasurementIdentity {
    uint64_t captureEpoch, parameterEpoch, framePosition;
    double sampleRate, windowSeconds, ageSeconds;
};
// Each metric additionally carries units, validity, confidence and provenance.
// Example: dBFS peak is not LUFS, inferred bandwidth is not a codec label.
```

Analyze source content before adaptive DSP so the controller does not chase its own corrections.
Evaluate the outcome with aligned pre/post windows, accounting for EQ, WOLA and limiter latency.
Distinguish observations, user preferences and hard constraints. Treat route changes, stale windows,
silence and capture loss as explicit states. On invalid evidence, smoothly return automatic taste
adjustments to neutral; retain essential hard protection and saved manual settings.

Each rule needs: ID/version, prerequisites, evidence units/age, action bounds, competing owner,
reason string, rollback condition, counterexample fixture and test ID. Put the registry in code/data
with compile-time or unit-tested feature coverage; an unconnected prose “knowledge base” is insufficient.

### Feature coverage matrix for the rule registry

| Feature | Required knowledge/evidence | Authority and prohibited shortcut |
|---|---|---|
| Extended EQ | Combined complex/magnitude response; source spectrum; chosen target/preferences | Preserve manual curve, cap combined boosts/cuts; no universal ideal slope |
| Bass Amount/Focus | Static shelf response, available headroom, route capability | Coordinate shelf with EQ; don't call added level improved detail |
| Bass Feel/Resolve | Cycle time, onset/sustain, linked envelope, harmonic uncertainty | Guard envelope first; unknown pitch cannot authorize a notch |
| Vocal intimacy/warmth/smoothness | Mid response, masking evidence, sibilance uncertainty | No claim of isolated lead-vocal processing from M/S alone |
| Space/Instruments | ILD/IPD, direct coherence, low-band stability | Identity at zero, no phase-swapping crossover; respect intentional panning |
| Backing/Binaural | Residual eligibility, band/peak budgets, transient confidence | No semantic-stem or binaural-beat claim; never overwrite explicit Off |
| Dynamic EQ | Persistent excess versus intentional harmonic content | One owner per overlapping low-frequency process; measure false positives |
| Gain/headroom | Static EQ plus bass/stereo/dynamic contributions, actual peaks | Shared budget before limiter; no independent boost accumulation |
| True-peak limiter | Reconstructed peaks, gain reduction, latency | Preserve ceiling/anti-clip authority; finite interpolator is not an absolute analog guarantee |
| Loudness/night/calibration | Measured loudness where available, selected calibration, volume proxy | No invented SPL, headphone model, room response or calibrated phon value |
| Quality/oversampling | Actual base rate, measured CPU margin, resampling quality | Preserve internal-rate goals; don't multiply high input rate blindly by 8 |
| Capture/output | Client/device-reported formats, route and policy, timestamps | Unknown original rate stays unknown; no bypass of player capture policy |
| Dither/export | Actual quantization boundary, destination format | Dither once for intentional integer reduction, not to “improve” float processing |
| Bypass/fail-open | Routing ownership, source mute restoration, capture liveness | Restore original playback on failure; avoid double processing |
| Listening laboratory | Delay/loudness alignment, frozen settings, actual sample rate | Prevent louder/delayed alternatives from masquerading as better processing |

System-effects mode does not supply the same native PCM observability or feature capabilities.
Do not run unsupported measurement-based rules there. Existing volume-to-phon/lossy-ceiling
heuristics should be renamed/restricted to what they actually observe, with migrated UI strings.

The current native loudness prediction receives only some stereo parameters. Extend either the
prediction model or, preferably, use an aligned measured outcome evaluator for new dynamic effects.
Do not report a prediction as an actual loudness measurement. Freeze/rollback if an adjustment causes
excessive limiting, instability, persistent underruns or a worse known-reference error.

## 5. High-resolution playback: format honesty and a better transport

### What can be improved

The current stereo float capture/output at 48 kHz is not equivalent to an integer 16-bit bottleneck;
float transport and double DSP already provide useful arithmetic precision. It also cannot be called
original 96/192 kHz capture simply because the player displays “Ultra HD”. A source can be resampled
before Svan sees it. Upsampling that 48 kHz stream does not restore discarded information.

Android's [AudioRecordingConfiguration](https://developer.android.com/reference/android/media/AudioRecordingConfiguration)
distinguishes the application's client format from the device-side recording format. Report both
when available, but neither alone proves the original streaming-file format or DAC output.
[AudioFormat](https://developer.android.com/reference/android/media/AudioFormat) documents PCM float
and route-dependent sample-rate selection. [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack)
client rate and timestamps are useful diagnostics, not a complete physical-path certificate.

### A. Make format provenance an explicit object

```kotlin
data class CaptureFormatPlan(
    val epoch: Long,
    val requestedHz: Int,
    val recordClientHz: Int,
    val recordDeviceHz: Int?,
    val playbackClientHz: Int,
    val channelCount: Int,
    val encoding: Int,
    val routedDeviceId: Int?,
    val sourceOriginalHz: Int? = null,
    val physicalOutputHz: Int? = null,
    val fallbackReason: String? = null
)
```

Use typed validity/provenance alongside these fields in production. A value is null/unknown if no
reliable API reports it. Suggested user-visible line: “Capture 48 kHz float · DSP 64-bit · Output
client 48 kHz · Original source/DAC format unknown”. Do not expose all engineering fields in ordinary
setup; make details available in the existing local diagnostic summary.

### B. Negotiate conservatively, with a working fallback

Safe Auto should prefer a verified rate suitable for the current route and retain 48 kHz fallback.
Allow 96 kHz trials where capture/output initialization and measured sustained performance support
them. Keep 192 kHz experimental until phone evidence justifies it. Support the 44.1 kHz family too;
do not resample 44.1 -> 48 -> 96 unnecessarily.

Routed-device support is only a candidate hint. [AudioDeviceInfo](https://developer.android.com/reference/android/media/AudioDeviceInfo)
documents that an empty sample-rate list can mean arbitrary rates, not no support; advertised USB
192 kHz does not establish original 192 kHz playback capture. Open candidates serially through the
existing safe routing state machine, inspect the resulting formats, and construct the engine from
the actual client rate. Never leave the original player muted after a failed trial. Avoid repeated
rate-probing/restarts during a song. Rate/route changes create a new capture epoch and reset analysis.

[Android sampling guidance](https://developer.android.com/ndk/guides/audio/sampling-audio) supports
minimizing unnecessary conversion and choosing appropriate device rates. High rate alone is not a
quality score; compare distortion/noise, CPU, dropout rate and actual format provenance.

### C. Propagate the actual rate through every dependent component

- `CaptureService`: record/track builders, frames-per-buffer in milliseconds, epoch, native engine,
  statistics, timestamp accounting and fallback.
- `EqController`/`NativeEngine`/JNI: remove the assumption that the global 48000 constant describes
  every live stream; validate supported rates and channel count at the boundary.
- All filters, envelopes, FFT windows, delays, gain transitions and oversampling: derive from actual fs.
- `SourceAnalyzer`: fixed 4096 samples have poorer bass resolution at 192 kHz. Keep a validated
  anti-aliased analysis rate or scale the window by physical duration; do not silently change the
  controller's meaning at high fs.
- `ClipRecorder`, WAV metadata and `BlindRenderer`: actual rate and exact frame counts; cancel or
  segment a clip on an epoch/rate change, never label 96 kHz samples as 48 kHz.
- UI response graphs and diagnostics: use actual effective rate/capability and identify dynamic
  response bounds, rather than drawing a false static response for a time-varying processor.

Adaptive oversampling proposal (preserves quality mode's internal design-rate family):

| Base rate | HQ target 96k family | Audiophile target 192k family | Extreme target 384k family |
|---:|---:|---:|---:|
| 48 kHz | 2x | 4x | 8x |
| 96 kHz | 1x | 2x | 4x |
| 192 kHz | 1x | 1x | 2x |

Use corresponding 44.1/88.2/176.4 family targets. The reference `oversamplingFor()` covers these six
rates and four current quality indices. A target below the base rate means 1x, not downsampling.
Benchmark each mode; retain the existing limiter's separate oversampled peak detector semantics.

### D. Fix queue and clock observability before claiming stable low latency

Count primed output frames in cumulative written frames. Unwrap the unsigned 32-bit playback-head
counter, with reset handling on new track/epoch; at 48 kHz it wraps after about 24.9 hours. Track
capture reads/timestamps as well as output writes/timestamps. Report capture backlog, output queue,
DSP delay and uncertainty separately; a large AudioRecord buffer can conceal accumulated input delay.

A single read/process/write loop does not prove capture and playback share a clock. Measure drift
over long playback first. Existing fixed-ratio `Resampler` is not an asynchronous drift corrector.
If independent clocks require correction, design a bounded, low-slew asynchronous resampler with
timestamp-derived control and dedicated transparency tests. Do not routinely drop/duplicate blocks
of music to conceal queue drift. Investigate the JNI array pinning/alias path separately; do not
declare it defective or fixed without a reproducer.

### E. Respect player policy and distinguish processed audio from bit-perfect audio

[Audio playback capture policy](https://developer.android.com/media/platform/av-capture) depends on
the player/app and user MediaProjection consent. Probe the present route; do not hardcode “Spotify
always blocked” against the owner's working experience, and do not bypass a player's policy.
When capture is unavailable, preserve system-effects/fail-open behavior and show the actual limits.

Android 14's [preferred USB mixer attributes](https://source.android.com/docs/core/audio/preferred-mixer-attr)
can improve supported output paths. They do not establish that upstream playback capture retained
the original source. Applying EQ intentionally changes samples; don't market processed playback as
bit-perfect to the original file. Leave float-path dither off; dither only once when intentionally
quantizing to an integer export format. A selected “24-bit” processing/export setting is not proof
of physical DAC resolution.

## 6. Implementation sequence and acceptance gates

| Order | Deliverable | Required evidence before the next stage |
|---|---|---|
| 1 | Regression fixtures for current side-phase defect; UI reproduction | Probe fails current path; screenshots locate both existing controls |
| 2 | Phase-consistent dry/delta streaming infrastructure for all side controls | Identity, stereo transfer, rate/block independence, fixed measured latency |
| 3 | Backing/Binaural residual enhancement and shared headroom | Panned/transient/spine protection; no musical-noise regressions; blind comparison |
| 4 | Bass Resolve ownership + linked envelope protection | Onset/body/tail/distortion/image fixtures; neutral clean material |
| 5 | Optional conservative bass unmasking | Validated harmonic detector and false-positive fixtures; no duplicate low-band owner |
| 6 | Complete rule registry and unified gain authority | Every feature mapped/tested; stale/unsupported paths neutral and explainable |
| 7 | Capture provenance, queue fixes and variable-rate transport | Simulated rate/failure tests, valid clips, bounded fallback and real phone diagnostics |
| 8 | Device/listening qualification and candidate release | Required CI, performance measurements, owner listening, normal release-signing workflow |

Minimum automated matrix: 44.1/48/88.2/96/176.4/192 kHz; block sizes 1, 64, 127, 256, 1024;
zero/all-max controls; every individual control; automation during music/silence; finite extremes;
stale epochs; reset/restart; mono/pure-side/hard-pan; impulse and sweep; clipping protection;
source capture allowed/blocked/lost; Bluetooth/headphone/speaker/USB route changes where available.
Avoid using a frame-size-dependent test expectation to bless a frame-size-dependent controller.

Specific proposed gates:

- Zero spatial controls: delay-aligned identity within 1e-12 for double reference / an explicitly
  justified production float tolerance; no frequency-dependent channel swap as amount tends to zero.
- Coherent hard-panned fixtures with Backing/Binaural only: no enhancement; measure leakage below
  -100 dB in double reference, choose production tolerance from the float path. Independently test
  intentional Space/Instruments behavior against its stated transfer function.
- Mono sum through the side-only stage unchanged; original interchannel phase/level checked too.
- Clean bass at Resolve max: no unjustified notch/extra harmonics; proposed <=0.5 dB envelope error
  after expected static EQ normalization, and tighter limits where feasible.
- All boosts combined: no NaN/Inf, valid bounded peaks, no persistent extra lead ducking above the
  proposed 0.2 dB comparison threshold. Reject a “more powerful” setting that succeeds only by clipping.
- Measure audio callback P50/P95/P99 and worst observed duration on target phones, including route
  changes and UI load. Set a documented margin under each actual callback deadline. Host speed is
  not an Android performance result; sanitizer runs are correctness checks, not timing benchmarks.
- BlindRenderer uses exactly the live algorithm, settings, rate, latency and constraints. Compare
  loudness-matched music excerpts selected by the owner, with their listening preference recorded.
- CI retains core/sanitizers, Android lint/unit/build, API 33/34 emulator routing/control/production
  tests and all 13 current detection checks. Inspect emulator screenshots, not just test exit codes.

Owner listening on TECNO and the priority LG V60/API 33 remains necessary for real capture, DAC,
background, thermal and commercial-player behavior. Do not block creating reviewable code on
unavailable phones, but explicitly withhold corresponding public claims until measured.

## 7. What the reference package has already tested

- The real current stereo implementation reproduces the 180 Hz hard-pan phase defect above.
- C++ policy reference: **5,038 assertions passed**, including 5,000 randomized realizable covariance
  cases for the quadratic constraint, stale/invalid evidence, ownership, harmonic/onset guards,
  shared reduction budgets, rate-family selection and smoother block independence.
- Offline WOLA reference: **4 test methods passed**, with multiple rates/pan ratios: zero identity,
  coherent panned/mono preservation, pure-side preservation and residual lift.
- A synthetic broadband residual fixture measured **+1.62195 dB side RMS** with mono-sum maximum
  error **1.11e-16** at 48 kHz. This is a correctness example, not proof of better musical sound.

Not supplied as completed production work: streaming C++ WOLA, transient/anti-chatter strategy,
peak governor integration, a validated semantic vocal or bass harmonicity classifier, Kotlin/JNI
state migration, real-device high-rate negotiation, device CPU tests, or new listening results.
The reference Python returns aligned samples and omits the streaming delay from the returned array;
the Android implementation must not omit that delay from its contract.

Research context: [Geometrically-Motivated Primary-Ambient Decomposition With Center-Channel
Extraction](https://arxiv.org/abs/2206.02125) motivates separating primary and ambient information,
but the independent conservative covariance reference here is not a reproduction or validation of
that paper's algorithm. No third-party separation model, GPL implementation or cloud processing is
required by this design. Existing privacy and permission restrictions remain in force.
