# Implementation prompt: Svan audio quality follow-up

Copy everything below into the Sol implementation session. This is a source-development task, not
permission to overwrite v0.5.6-beta.1, merge PR #6, or bypass release verification.

---

Implement the owner's four audio goals using `docs/audio-quality/DESIGN.md` as the engineering
specification and `tools/audio_quality_reference/` as tested, limited reference code. Read `AGENTS.md`,
`docs/CODEX_VISION.md`, and `docs/HANDOFF.md` first. Stay on
`claude/codex-audio-crackling-amplifier-gkj007`. Preserve existing user changes and licensing.

Goals:
1. Make the existing Backing vocals/Binaural controls discoverable in Orchestral amplifier and
   improve spatial definition without drowning the lead, guitar, bass or drums.
2. Add Bass Resolve: protect bass-note onset, body, harmonics and sustain; allow explicit Svaresa
   Auto master ownership while preserving manual settings.
3. Give Svaresa/Svaramanas complete, tested feature coverage, valid measurements, coordinated budgets
   and clear reasons. A prose “mastering knowledge” document alone does not implement this.
4. Improve high-resolution capture/processing with actual format provenance, rate propagation,
   safe negotiation, correct latency/clock accounting and commercial-player fallback.

The owner reports Amazon HD and Spotify lossless work well. Preserve that evidence; establish the
actual route/rates separately. BHIM/GPay worked after Shizuku removal with Developer options still
ON. Do not add notification-listener, accessibility or SMS capabilities, bypass capture policy,
introduce cloud audio upload, or replace the production signing identity.

## Start with the measured bug, not new knobs

Both knobs/parameters already exist. `SoundScreen.kt` places them in the second row of
`InstrumentTunerCard`. Move them to the first row, test visibility, and reuse the fields
`backingVocals`/`spatialDetail`. Show effective per-player engine capability.

The current side-only LR4 crossover in `core/src/stereo.cpp` rotates phase relative to mid. A
left-only 180 Hz tone effectively moves right even when Backing vocals = 0.000001.
Run `stereo_phase_probe.cpp`; preserve it as evidence and convert the correct desired behavior
into regression tests before replacing the path. Mono-sum-only tests miss this defect.

## Ordered implementation tickets

### AQ-01 — phase and stereo transfer regression

Files: `core/tests/test_main.cpp`, `core/src/stereo.cpp`, `core/include/eqcore/stereo.h`.
Add hard-pan sweeps/180 Hz, zero-limit continuity, unequal-channel impulse, mono/pure-side and
moving coherent-source fixtures. Establish actual current failure. Do not “fix” it by weakening
expectations or checking only total side energy. All side controls must share a phase-consistent
topology; leaving old Space/Instruments crossover enabled would retain the bug.

### AQ-02 — streaming spatial processor and controls

Port the dry-plus-delta WOLA architecture and guarded residual math from the design. The Python
implementation is an offline oracle, not realtime code. Implement preallocated C++ FFT/rings,
fixed delay, normalized overlap-add, startup/drain, rate/block independence and smooth state changes.
Use existing compatible core FFT infrastructure where suitable; do not import GPL code.

Add the production pieces missing from the prototype: aligned statistics, transient/motion guard,
noise floor, smooth eligibility, frequency/time anti-chatter, output-domain checks and a shared
headroom/peak budget. Preserve existing mid controls. Port Space/Instruments semantics explicitly.
Test combined settings against lead ducking and bass-image changes.

Files also include `core/src/engine.cpp`, the matching engine header, `android/app/src/main/cpp/`
JNI bridge, `NativeEngine.kt`, `model/EqModel.kt`, `ui/SoundScreen.kt`, and
`listening/BlindRenderer.kt`. Verify actual filenames with `rg`; do not create duplicate JNI paths.
Advertise all added latency through the engine and listening pipeline. Preserve manual values,
provide explicit Off/Manual/Auto, and migrate JSON with finite/range validation.

### AQ-03 — Bass Resolve

Files: `core/include/eqcore/bass.h`, `core/src/bass.cpp`, engine/JNI/NativeEngine, `EqModel.kt`,
`SoundScreen.kt`, `svaramanas/SvaresaBrain.kt`, and blind renderer/config serialization.
Implement stereo-linked, cycle-aware envelope preservation first. Use `guardedCharacterDb()` as
the tested policy kernel, with actual detector/smoothing integration and measured bass fixtures.
Default newly introduced ownership to Off for old presets. An explicit Auto setting lets Svaresa
control it while Auto master is on; do not conflate zero with automatic ownership.

Only enable selective unmasking when a validated harmonicity/confidence detector exists. Unknown
harmonicity means skip. `bassReductionDb()` is a decision rule, not that detector. Respect the
1.5 dB/lane and 2 dB combined proposed limits, apply the budget after smoothing, and transfer lower
DynamicEq lane ownership rather than doubling the attenuation. Test intentional resonant synths,
quiet decays, notes arriving during existing cuts and stereo balance. Do not claim clean note
preservation from onset-from-silence tests alone.

### AQ-04 — feature-wide mastering policy

Files: native Svaramanas/planner/source-analysis code under `core/`, Kotlin files under
`svaramanas/`, model ownership/effective-state structures, and existing diagnostics.
Implement the design's feature coverage matrix as executable, versioned rules with tests.
Each rule declares input units/validity/age/epoch, ownership, bounded action, competing processors,
reason and rollback. Source analysis precedes adaptive DSP; outcome analysis is latency-aligned.
Use one combined static/dynamic headroom authority. Retain manual curves and explicit Off.

Do not treat volume as calibrated SPL or bandwidth as codec detection. Do not force a universal
spectral tilt. Do not make native PCM measurements appear available on opaque system effects.
Update loudness evaluation to include the real new processor behavior, rather than reusing an
incomplete prediction. Keep audio-frame protection separate from slower tonal/taste decisions.

### AQ-05 — capture precision and rate correctness

First implement observable format/queue corrections at the existing 48 kHz behavior, then introduce
rate negotiation. Files: `CaptureService.kt`, `EqController.kt`, `NativeEngine.kt`, JNI, source
analyzer, resampler setup, `listening/ClipRecorder.kt`, `listening/BlindRenderer.kt`, WAV metadata,
diagnostic summary and UI capability/rate displays.

Report requested/client/device-reported rates separately; original source and physical DAC rate
remain unknown without evidence. Include primed frames in output accounting; unwrap playback-head
position and reset per epoch; measure capture backlog and clock drift. Do not call fixed-ratio SRC
an asynchronous clock solution. Preserve float transport and the already-off default dither.

Negotiate supported candidate rates serially with bounded fallback through the existing route
state machine. Keep 48 kHz safe fallback; make 96 kHz evidence-based and 192 kHz experimental.
Derive DSP, envelopes, buffers, analyzer duration, oversampling, clip length and metadata from
actual fs. No rate change may leave the original source muted, corrupt an in-progress clip, or
carry old measurements into a new epoch. Test 44.1 and 48 kHz families and misleading/empty device
capability lists. Source app “Ultra HD” is not sufficient to assert native HD capture.

### AQ-06 — qualification and reviewable delivery

Extend meaningful core tests before the DSP edits. Run the reference checks, core tests and
required Android checks, then existing CI/emulator suites including all 13 detection checks.
Inspect screenshots for both requested knobs and Bass Resolve with large fonts. Preserve existing
routing, manifest/privacy, controls, release verification and fail-open tests.

Run sanitizers. Check audio-thread allocations/locks and measure target-phone timing before claiming
realtime performance. Add loudness/delay-matched blind fixtures using the same live implementation.
Do not label synthetic reference success as Amazon/Spotify/TECNO/LG validation.

Use separate reviewable commits for the tickets, with the repository's required identity/trailers.
Before every push run the checks from AGENTS.md. Keep PR #6 unmerged. Update HANDOFF with what is
implemented versus gated, metrics, remaining device checks and exact CI/source identity. If a new
APK is needed after integration, use a new version; follow the existing signing/release instructions
on the final tested commit. Never re-sign a rebuilt artifact as if it were the tested one.

## Reference commands

From repository root:

```sh
g++ -std=c++17 -O2 -Wall -Wextra -Wpedantic \
  tools/audio_quality_reference/test_quality_policy.cpp -o /tmp/svan-quality-policy
/tmp/svan-quality-policy
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tools/audio_quality_reference -p 'test_*.py'
g++ -std=c++17 -O2 -Icore/include \
  tools/audio_quality_reference/stereo_phase_probe.cpp \
  core/src/stereo.cpp core/src/biquad.cpp -o /tmp/svan-stereo-probe
/tmp/svan-stereo-probe
cmake -S core -B build/core
cmake --build build/core -j
./build/core/eqcore_tests
```

From `android/`, with the required JDK/SDK/NDK configured:

```sh
./gradlew assembleDebug :app:assembleRelease lintDebug testDebugUnitTest
```

Current reference baseline: 5,038 C++ assertions and 4 multi-fixture Python test methods pass;
broadband side lift +1.62195 dB, mono-sum error 1.11e-16. These establish limited arithmetic/fixture
behavior. The reference does **not** contain a finished streaming processor, semantic source
separator, validated bass detector, Android rate negotiator or proof of improved music quality.
Complete each missing piece and its counterexample tests; do not ship the prototype unchanged.
