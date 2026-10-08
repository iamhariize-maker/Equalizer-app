# Claude handoff: audio-quality follow-up

Date: 7 October 2026 (Asia/Calcutta)
Repository: `/workspace/Equalizer-app`
Branch: `claude/codex-audio-crackling-amplifier-gkj007`
Audited HEAD: `7b627770a78b7d3dc267754e0c2995b608751525`

## Current state

The user asked for four related improvements: restore/discover the Orchestral amplifier's Backing
vocals and Binaural controls with better spatial definition; add Bass Resolve with Svaresa Auto
master control; expand Svaresa/Svaramanas into a measured, coordinated mastering policy; and improve
Amazon Ultra HD / Spotify lossless capture and processing. The user reports both services already
work on their phone. This turn produced an engineering design and tested reference package only.
No Android production source, APK, signing key, release, PR merge, or manifest permission was changed.

Read these files in this order:

1. `AGENTS.md` (repository rules and privacy/licensing constraints)
2. `docs/audio-quality/DESIGN.md` (full specification, measurements, algorithms and gates)
3. `docs/CODEX_AUDIO_QUALITY_IMPLEMENTATION_PROMPT.md` (ordered tickets for Sol)
4. `tools/audio_quality_reference/README.md` and the files beside it (executable limited references)
5. `docs/HANDOFF.md`, `docs/CODEX_VISION.md`, then relevant current source

The user specifically asked for enough intellectual/code detail that Claude/Sol can implement the
bulk without spending another context window rediscovering the investigation.

## Important measured finding

There is a P0 defect in the current side processing. `core/src/stereo.cpp` filters S (side) through
180 Hz LR4 low/high branches and recombines it, while M (mid) is not given the same phase response.
The magnitude is approximately flat but phase is not. A left-only 180 Hz tone therefore swaps sides
when any tiny Backing vocals or Binaural amount is enabled. Existing mono-sum tests pass because the
mono sum remains mathematically correct.

Reproducer: `tools/audio_quality_reference/stereo_phase_probe.cpp`, which links the real current
`stereo.cpp`. Results are in `docs/audio-quality/current-stereo-phase.csv`:

- Backing vocals `0.000001`: left `-160.08 dB`, right `0.00 dB` at 180 Hz.
- Backing vocals `0.01`: left `-80.08 dB`, right `+0.00015 dB`.
- Binaural `0.000001`: left `-141.43 dB`, right `0.00 dB`.
- Mono-sum error is approximately `1.4e-17` in these fixtures.

Do not pile new widening code on this path. First replace all side-control crossover processing with
a phase-consistent identity dry branch plus bounded side delta, then keep the regression fixtures.
The motion detector also uses mismatched mid/side filter orders and needs phase-consistent analysis.

## Source facts already verified

The attached preview APK is byte-identical to the prior preview:
`c1ade065d5a3846383dd35c62ec26ea6d7c26b9fd3c9406d98f77bb4d24e4d14`.
It is `app.svan`, versionCode 13, `0.5.6-continuity-preview`, min API 29, target API 36.
DEX contains the requested labels/fields, but that proves existence, not viewport visibility.
Current `SoundScreen.kt` puts Backing vocals and Binaural in the second row of
`InstrumentTunerCard`; move them to the first row after screenshot reproduction, without duplicate
state. Show effective capability (native, system approximation, off or unavailable) per player.

Current implementation constraints to preserve or fix are described fully in DESIGN.md. The most
relevant are: BassShaper can apply roughly +/-12 dB dynamic character independently per channel;
`updateGain()` does not budget every downstream boost; DynamicEq owns low lanes that could conflict
with Bass Resolve; EqModel has no Resolve field; native capture/track/engine/ClipRecorder assume
48 kHz; primed frames are omitted from `writtenFrames`; playback head position is not unwrapped;
and the single audio loop does not prove clock identity. Dither is already off by default, so do not
claim a default-dither bug.

## Design decisions to preserve

- Binaural means enhancement of spatial information already present in the recording. Do not invent
  binaural beats, synthetic HRTF surround or reverb without a separate user decision.
- M/S covariance residual is decorrelated ambience/double-track detail, not semantic stem separation.
  Centered coherent backing vocals may remain unchanged; explain that honestly in the UI.
- New spatial controls use fixed-delay phase-consistent WOLA dry-plus-delta processing. Proposed
  initial masks: Backing 250–450 Hz rise/body/3.5–6 kHz taper, max 4 dB; Binaural 180–350 Hz/body,
  taper above 4–12 kHz, max 3 dB; shared residual request cap 4 dB. These are tunable hypotheses.
- Side-energy limit is `min(0.5 * Pmm, Pss * 10^(4/10))`, enforced with the exact stable quadratic
  root in `quality_policy.h`; reapply the budget after smoothing and check actual output peaks.
- Bass Resolve protects existing envelope first (stereo-linked, no channel wandering), then permits
  conservative unmasking only with validated harmonic evidence. Proposed lanes 70/110/180/280 Hz,
  1.5 dB/lane and 2 dB combined limits, 50 ms attack/400 ms release. Unknown harmonicity means skip.
  Existing low DynamicEq lanes must relinquish ownership while Resolve is active.
- Ownership is explicit Off/Manual/Auto. Preserve saved manual values; migrate old state with Resolve
  Off. Svaresa may control it only when Auto master is on and evidence is fresh, same-epoch and valid.
- Svaresa operates on fast native protection (~250 ms policy) and Svaramanas on slower coordinated
  intent (~3 s). Every rule needs units, validity, confidence, epoch/age, bound, owner, reason,
  rollback and counterexample test. Never infer SPL, codec identity or original source rate from weak
  proxies.
- Capture reports requested/client/device formats separately. Original source and physical DAC rate
  remain unknown unless measured. Keep safe 48 kHz fallback; evidence-based 96 kHz; 192 kHz experimental.
  Propagate actual fs through DSP, analyzer, oversampling, queue, clips, WAV metadata and BlindRenderer.
  Fixed-ratio SRC is not asynchronous clock correction; do not bypass Android capture policy.

## Reference package and test evidence

New, currently untracked files are intentionally reviewable and standalone:

- `tools/audio_quality_reference/quality_policy.h`: original C++17 ownership, covariance, quadratic
  side budget, Bass Resolve guards, reduction budget and rate-family kernels.
- `tools/audio_quality_reference/test_quality_policy.cpp`: **5,038 assertions pass**, including
  5,000 randomized realizable covariance cases.
- `tools/audio_quality_reference/reference_dsp.py`: offline NumPy WOLA dry-plus-delta prototype;
  not realtime-safe and intentionally lacks semantic classifiers/transient production guards.
- `tools/audio_quality_reference/test_reference_dsp.py`: **4 unittest methods pass** at multiple rates.
- `tools/audio_quality_reference/stereo_phase_probe.cpp` and `docs/audio-quality/current-stereo-phase.csv`.
- `tools/audio_quality_reference/README.md` with exact build commands.

Observed synthetic broadband residual fixture: +1.62195 dB side RMS, mono-sum max error 1.11e-16,
48 kHz. This proves limited arithmetic/fixture behavior, not improved music quality, phone CPU,
Spotify/Amazon native HD capture, or a finished bass detector.

## Implementation order for Claude/Sol

1. AQ-01: preserve the phase-defect regression and add hard-pan/impulse/moving-source tests.
2. AQ-02: implement preallocated streaming phase-consistent WOLA with fixed advertised latency;
   add residual eligibility, transient guard, anti-chatter, shared headroom and UI state migration.
3. AQ-03: implement Bass Resolve envelope protection; only then add validated selective unmasking.
4. AQ-04: make the feature matrix and rule registry executable; unify ownership and combined budgets.
5. AQ-05: correct format/queue/clock provenance at 48 kHz, then negotiate variable rates safely.
6. AQ-06: device/listening qualification, screenshots, CI and release gates. A new APK must receive
   a new version; do not re-sign the old tested artifact as a different build.

Do not wire the Python prototype into an audio callback. Do not claim any of these features are in
the app until C++ streaming tests, Android/JNI/state migration, real-device timing, and listening
evidence exist. Preserve PR #6 unmerged and the published v0.5.6-beta.1 release.

## Re-entry commands

```sh
cd /workspace/Equalizer-app
git status --short --branch
g++ -std=c++17 -O2 -Wall -Wextra -Wpedantic \
  tools/audio_quality_reference/test_quality_policy.cpp -o /tmp/svan-quality-policy
/tmp/svan-quality-policy
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tools/audio_quality_reference -p 'test_*.py'
g++ -std=c++17 -O2 -Icore/include tools/audio_quality_reference/stereo_phase_probe.cpp \
  core/src/stereo.cpp core/src/biquad.cpp -o /tmp/svan-stereo-probe
/tmp/svan-stereo-probe
```

Before pushing implementation work, run the core and Android checks mandated by `AGENTS.md` and
inspect CI emulator screenshots. The current design/reference additions are not yet committed;
commit them together only after reviewing the untracked-file list and using the repository's required
identity/trailers. No secret, signing key or Drive file belongs in this handoff.
