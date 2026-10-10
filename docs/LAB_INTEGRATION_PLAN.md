# Lab and Svaresa integration review, rebased to 0.5.13 (11 October 2026)

Scope: the loudness behaviour of the Lab and Svaresa "Auto master" layer, as the owner reports it: the features help,
but applying them drops the volume at once, and it returns a few seconds later.

Status: analysis only. No DSP or trim behaviour changed in this pass. The implementation ticket is **L-1** in
`docs/handoff/HANDOFF_BRIEF_2026-10-11.md`, and it targets the owner's build branch `ccr-f8964344-8f7mf5` (0.5.13, `fbe0680`).
Every code reference below is from that commit. The 0.5.5 references in the earlier draft are superseded.

## 1. Symptom and mechanism

The dip-then-return pattern comes from the loudness trim (`preampDb`), not from the peak limiters.

- **Applied at once.** A Svaresa band fader, an EQ mode change or a reset calls `Svaramanas.refreshEq()`
  (`Svaramanas.kt:377`), which runs `requestRecompute(immediate = true)` and so `recompute` (`:385`). On this path the
  bands jump to their target at once (`drifted = target`, `:438`). The callers are `SvanRepository.kt:141,160,167,171`.
- **The trim is not slewed.** `next = drifted.copy(preampDb = (-delta).coerceIn(-18.0, 1.5))` (`:451`) replaces the
  slewed preamp that `slew()` (`:465`, preamp step at `:477`) computed a few lines earlier. The class comment at
  `Svaramanas.kt:174` promises "Adaptive updates slew at most 0.5 dB per band every 3 s: the sound drifts, it never
  jumps". The trim does not keep that promise.
- **The trim changes source once, at 3 s.** `delta` is the predicted K-weighted loudness change of the applied bands.
  Without analysed audio it uses a pink reference (`core/src/svaramanas.cpp:170-172`). The analysis becomes valid
  after 3 s of audio (`core/src/analyzer.cpp:268`), and from then the measured spectrum is used. That switch is one step.
- **System effects keep the estimate.** The analysis is read only while Engine B captures (`Svaramanas.kt:401`). In
  system-effects mode the trim is always the pink estimate, so it never switches to a measurement.

## 2. What was measured

### 2.1 The trim (`docs/handoff/probes/svaresa_trim.cpp`)

Predicted trim for a quiet-listening layer, with a synthetic spectral tilt standing in for music (not a recording).
Output is the preamp Svaramanas would apply, clamped to [-18, +1.5] dB. **The result is identical on the 0.5.5 and
0.5.13 cores**, so the formula has not changed; the update rule (§1) is the fault.

| Quiet-listening lift | Pink estimate, applied at once | Measured at 3 s, −4.5 dB/oct | Change at 3 s |
|---|---|---|---|
| bass 2 dB, treble 1 dB | −0.46 dB | −1.46 dB | −1.00 dB |
| bass 4 dB, treble 2 dB | −0.99 dB | −3.02 dB | −2.03 dB |
| bass 6 dB, treble 3 dB | −1.59 dB | −4.65 dB | −3.06 dB |

The direction depends on the material. Each synthetic tilt tested (−3, −4.5, −6 dB/oct) moves the trim further down,
because those spectra carry more bass than pink. If the trim is the cause of the rise the owner heard, the owner's
tracks carry less energy in the boosted bands than pink. That is a hypothesis to confirm with logs (§4, step 4).

Not measured: the phone, the output level after protection, Bluetooth, and the Android mixer. These are predicted
offsets of the trim only.

### 2.2 CPU cost of each switch (`docs/handoff/probes/lab_cost.cpp`, 0.5.13 core, host)

Twelve peaking bands, stereo, 48 kHz, 10 s per setting, two runs, percent of one host core. Host numbers only compare
configurations with each other.

| Switch | Efficient (1x) | Audiophile (4x) | Read-out |
|---|---|---|---|
| Engine only | 4.0% | 7.0% | 1.6 to 2 times the 0.5.5 figure; the core changed |
| Gain protection | within noise | within noise | keep on |
| Auto headroom | within noise | about +0.8 (one run) | keep on |
| Selective dynamic EQ | +1.5 | +1.8 | largest add-on; no listening evidence in the repo |
| Engine B analysis | +0.1 to +0.4 | +0.5 | needed for a measured trim; capture only |
| Svaresa full set | +1.4 to +1.8 | +1.7 | dynamic EQ is most of it |

Phone CPU and battery are not measured. The owner's TECNO LH7n and LG V60 readings are the next data.

## 3. Other contributors checked

- **Reconstructed-peak protection and automatic gain protection.** Sample-peak limiting with 1 ms attack and 250 ms
  release (`docs/QUALITY_LAB_0.5.5.md`). It reacts to peaks and does not hold the average level down. Low likelihood.
- **Auto headroom.** Changes only when the EQ changes. Not a return mechanism.
- **Selective dynamic EQ.** Disabling it fades to neutral over 10 ms (`docs/QUALITY_LAB_0.5.5.md`). Not slow.
- **Context layer (quiet listening, night).** Bands move at most 2 dB per 3 s (`Svaramanas.kt:337`). That can make a
  tonal change look gradual, but it does not cause the level dip.
- **Whole-phone fallback (`MixFallback`).** It applies the user's curve to the output mix with a 0.3 s fade. It does not
  carry the Svaresa trim, so it is not the dip. It does affect messaging audio (see the brief, P-2).
- **Features not switched on in the app** (per `docs/audio-quality/STATUS.md`, dated 7 October): the spatial residual,
  selective bass unmasking and rate negotiation (AQ-02, AQ-03 B, AQ-05). They are not part of this dip. Check that file's
  current state before relying on it.

## 4. Plan (for discussion; implementation in brief ticket L-1)

### Step 1: continuous trim (small; do first)

1. Route the trim through the same limits as the bands on every path, including `immediate`. Proposed rate: 1 dB per
   second in either direction, so a 3 dB change takes 3 s and never steps.
2. Replace the single switch at 3 s with a confidence ramp. Use the estimate until the analysis is valid, then blend
   toward the measured trim over about 10 s of analysis. No step.
3. In system effects, label the trim as an estimate in the UI, because nothing is analysed there.
4. Log `delta`, `valid`, `seconds` and `preamp` on every update, so a listening session can be matched to the numbers.

Acceptance, as tests to add: a layer change moves the applied level by at most 0.2 dB per 100 ms; the level reaches its
final value within 6 s with no overshoot beyond 0.2 dB; the blind-listening level match still holds. These checks show
the trim is continuous. They do not show that it sounds better.

### Step 2: one protection and headroom authority

Predictive headroom, automatic gain protection and the reconstructed-peak detector each reduce level for overs, with
separate release rules. Merge their decisions into one value with one release law, so the listener sees one number and
recovery does not depend on which layer acted. §2.2 shows protection and headroom cost nothing measurable, so this is
about behaviour, not CPU.

### Step 3: spend CPU where it is heard

- Keep protection and headroom on by default.
- Keep analysis on only while capturing (already the case).
- Make selective dynamic EQ opt-in until blind listening shows a preference. It is the most expensive switch, and its
  thresholds are documented as awaiting listening evidence (`docs/QUALITY_LAB_0.5.5.md`).
- Measure on the TECNO LH7n and LG V60 before and after each step, with the Hi-Fi CPU readout.

### Step 4: owner listening check

Level-matched A/B on the same track, using Blind listening: (a) the current trim, (b) the continuous trim, (c) dynamic EQ
on and off. Record the size of the dip, the time to settle and the preference. The owner decides. The app does not
infer a preference.

## 5. Questions for the owner

1. Does the dip happen with Engine B (capture) or with System effects?
2. Which tracks, and at what volume? The direction of the return depends on the material.
3. After applying, should the level return to its previous value (loudness-neutral), or settle at the new trimmed level?

## 6. Reproducing the numbers

- Core build and tests: see the brief, §4. The 0.5.13 release test binary segfaults in this container at
  `grounding_off_is_bit_exact_bypass`; reproduce that before relying on a full run.
- Probes (reference harnesses, not built by CMake):
  `g++ -std=c++17 -O2 -I core/include core/src/*.cpp docs/handoff/probes/svaresa_trim.cpp -o svaresa_trim && ./svaresa_trim`
  and the same with `lab_cost.cpp`.
