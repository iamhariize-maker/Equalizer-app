# Build brief 0.5.14 — return path for parked players, shrill guard re-base, space, bass texture range

Read `AGENTS.md` first. Work on the branch the session names. Base: `d44173e` (0.5.13 candidate). Evidence below comes
from an independent review with measurements; the probes that produced it are in `tools/probes/0.5.13/` (see §5).
Nothing here has been heard on a phone. Do not claim sound quality; ship each change as a blind-test pair.

## Start here (build in tiers; each tier ends at a green core/android run and its own commit)

- **Tier 1, stability and protection:** WP2, WP1, WP4, WP3, and the *protect* tests of WP8 and WP10. These fix what is
  broken and stop the new stages from hurting the voice and the highs. No new taste features.
- **Tier 2, bass:** WP5, then WP9. Each new sound feature is default-off behind its own control until a level-matched
  blind pair prefers it.
- **Tier 3, the rest:** the candidate additions in WP8 and WP10, WP6, and WP7 only if the traces call for it.
Every perceptual statement in WP8–WP10 comes from reasoning and general psychoacoustics, not from listening or from the
owner's tracks; treat each as a hypothesis with a measured acceptance test, not as a known result.

## 1. Verdict on `d44173e`

| Part | Verdict | Why |
|---|---|---|
| `BassTexture` + wiring | **Keep**, retune range (WP5) | Bass line probe: peak −0.04 dB, fundamental ≤0.11 dB, no DC, 1.1 ms/s CPU. But −32 dBc at −12 dBFS is probably too subtle |
| `ShrillGuard` detector | **Re-base** (WP3) | Inert on realistic material (see F4) |
| `space` 6 kHz shelf | **Replace** (WP4) | Air is tamed, but the side still has a −6.5 dB hole at 200 Hz (F5) |
| Capture changes | **Replace mechanism** (WP1) | Dead on the no-DUMP path the owner runs (F1, F2) |
| `com.gaana` admission, JNI, tests | Keep | Harmless, compiled, tested |

CI on `d44173e` (run 38072765359): core (release, ASan/UBSan, TSan), android (lint, JVM, production APK), capture-lab 33/34,
compat 29/30/33 pass. `efficiency-ui (33)` and `production-ui (33)` fail as harness aborts (adb exit 1, 5/10 PASS, no
FAIL lines) and fail the same way on the base `d8f0201`, where six API 33/compat jobs are red. Compare job by job before
blaming a change. Read `emulator-e2e` and `basic-detection` results for this run before starting.

## 2. Findings (all measured or read from code)

| # | Finding | Evidence |
|---|---|---|
| F1 | Without DUMP, `SessionRouter.sync` never runs: `SystemEqService.sync` calls it only when `st.dumpPermission`, else `repairKnownSessions()`. So `MusicSourceGate`, sibling admission and the dump-fed `evidence` UID exclusion are dead there | `SystemEqService.kt` ~L150–160 |
| F2 | Without DUMP a route's `playing` is always `null` (set only by `sync`). `retryParkedPlayers` needs `playing == true`, so a source handed to Engine A (fail-open or stall) **never returns** until a new OPEN arrives. This is the leading explanation for "Gaana gets disconnected and stays" | `SessionRouter.kt` `retryParkedPlayers`, `sessionOpened(…, playing = null)` |
| F3 | During capture a CLOSE removes a muted route at once (`sessionClosed`, `projection != null`). An app that opens a new session per track tears the recorder down and reopens it every track. Engine rebuild is **not** the cost: construct 0.10–0.35 ms, `reset()` 0.003 ms | `p1_cost` probe. Whether Gaana churns sessions is unverified |
| F4 | `ShrillGuard` is inert on music. Presence threshold −9 dB sits on the pink-noise edge (pink: −9.0). Music-like spectra read −20 dB, so 11 dB under it. On a sustained harsh cluster the repo's analyzer reads `harshDb` **+29.2 dB** (smoothing starts at 1.5) while the guard changes nothing (0.00 dB at 3.6/4.2/4.8 kHz) | `p2`, `p4`, `p5` probes |
| F5 | `space` +1 side gain, old / committed: 120 Hz −0.6/−0.6, **200 Hz −6.7/−6.5**, 300 Hz +1.1/+1.4, 600 Hz +5.1/+5.3, 2 kHz +5.9/+6.1, 8 kHz +6.0/+1.5, 10 kHz +6.0/+0.6 dB. The 200 Hz hole (LR4 crossover phase against the dry path) plus a lifted top reads as "light and airy" | `p6` probe; `stereoResponsePower` |
| F6 | Harmonic level vs shaper drive k at −12 dBFS (3rd harmonic): k 2.5 → −31.3, 3.5 → −26.9, 4.5 → −24.1, 6.5 → −21.3 dBc. At the house mapping (depth 0.7) the committed texture sits near −35 dBc | `drive.py` |
| F7 | New stages cost +5% of the Audiophile engine on the host (BassTexture 1.1 ms/s, ShrillGuard 3.9 ms/s). Not a concern | `p1_cost` probe |
| F8 | The analyzer's `harshDb` is the 2.5–5 kHz residual against the mix's own least-squares tilt. Pink noise reads −0.2, steeper music-like −0.1, a bright flat top reads `harsh` −4.0 and `air` +12.8. It separates shrill from normal cleanly | `p5` probe; `analyzer.cpp` `residual()` |

Note on `otherActivePlayers`: the muted source's own track is still an active player, so the silence fail-open fires on a
**stalled but active** track (buffering, ad gap, DRM zeros), not on a pause (a paused track leaves the active list).

## 3. Work packages (priority order)

**WP1 (P0) Return path without DUMP.** Files: `SessionRouter.kt` (`onCaptureSilent`, `retryParkedPlayers`), new pure
`ParkPolicy.kt`, `ParkPolicyTest.kt`, `SessionContinuity.kt`.
- Treat `playing == null` as "maybe playing" everywhere; only an explicit `false` blocks promotion.
- `ParkPolicy`: per-package state machine with a fake-clock interface. Confirmed packages are **never blocked
  permanently**: hand-over after 6 s of zeros on an active track, minimum dwell 5 s, then return by a listen-only probe
  every 2–3 s (reuse the late-probe machinery, unmuted) or by timer backoff 20 s → 60 s → 180 s → 600 s cap. Reset the
  counters after 10 s of captured audio. Unconfirmed packages keep today's 3 min / 15 min / session strikes.
- Distinguish blocked from stalled with `AudioRecord.activeRecordingConfiguration.isClientSilenced` (already traced):
  silenced → blocked (strike); not silenced → stall (no strike).
- Promote only parked routes with reason in {`SILENT_RECENTLY`, `NO_CAPTURE_DATA`, `WAITING_FOR_PLAYBACK`}; allow it while
  another source is captured only when the package is confirmed. Fold or remove the `stall:` keys and `STALL_*` constants.
- Tests: JVM (fake clock): stall → park → promote with `playing == null`; repeated stalls grow the backoff to the cap and
  never go permanent for a confirmed package; explicit `playing == false` is not promoted; unconfirmed strikes unchanged.
  Emulator: add a stall mode to the fixture player (15 s of zeros mid-track, track still active) and assert the source is
  back on Engine B within 30 s with no new OPEN, in a DUMP-free job.

**WP2 (P0) Make the next Gaana report conclusive.** Add `EqController.log` lines with prefixes the classifier knows
(`diag/EngineTrace.kt` ~L178; add `park:`, `promote:`, `uid-drop:`): package, reason, ms since the previous event, recorder
open duration, and `isClientSilenced`. Test the classifier. Ship before or with WP1.

**WP3 (P1) Re-base `ShrillGuard` on the analyzer.**
- Keep: two 4th-order band-passes (4 kHz Q 1, 8 kHz Q 1.4), the sustain gate (2 ms vs 40 ms power; closed during attacks),
  2 dB cap per band, 10 ms gain smoothing, bypass bit-exactness, the −100 dBFS floor. Delete the internal absolute
  band-to-mix detector.
- Input: analyzer residuals. Add lock-free atomics in `SourceAnalyzer` published where `published_ = f` is set (the audio
  thread must not call `snapshot()`, which locks) plus a new `residual(6000, 10000)` kept off the packed JNI array. The
  engine passes them per block when `analysisOn_`; with analysis off the guard idles.
- Start values (match `SM-HARSH-1`): presence reduces above `harshDb` 1.5 dB, sizzle above 2.0 dB, 0.5 dB per dB, cap 2 dB.
- Tests: the probe-4 cluster drops 1.5–2.0 dB per partial at depth 1; pink and −6 dB/oct noise change < 0.05 dB; a 3 ms burst
  peak changes < 0.3 dB while excess is high; depth 0 bit-exact. Calibrate against the owner's reference excerpts with
  `tools/mastering/` (percentiles, not pink-noise reasoning).

**WP4 (P1) `space` as a plain side EQ.** Replace the LR4 dry-plus-delta for `space` with a bell plus high shelf on S. Keep mono
sum bit-identical, keep `stereoResponsePower` in sync, update the tests that pin the old structure and say why.
Targets at +1: side gain ≥ −0.5 dB at ≤ 250 Hz, +3.5…+6 dB over 700 Hz–3 kHz, ≤ +0.5 dB at ≥ 9 kHz; hard-pan leak ≤ −20 dB
below 150 Hz and ≤ −9 dB elsewhere. Probe 6 trial (bell 1.5 kHz +6 dB Q 0.45 plus shelf 6.5 kHz −5 dB): −0.2 @200, +0.8 @300,
+3.1 @600, +5.2 @1k, +5.5 @2k, +2.3 @4k, −2.8 @8k, −3.9 @10k. It overshoots at the top: use a milder shelf (−2…−3 dB) and
drop the 250 Hz low shelf (it worsened the 60 Hz leak to −23 dB). Narrowing mirrors the bell; no top shelf.

**WP5 (P1) Bass texture range.** Raise `kDriveMax` 2.5 → 4.5 (≈ −24 dBc at −12 dBFS, full depth). Add `evenMix` 0..1 (default 0)
as a blind-test control for the second harmonic (full-wave rectified sub band, DC removed by the 30 Hz high-pass). Add a
route factor in Kotlin (speaker 1.0, wired and Bluetooth 0.7 as starting guesses, blind-tested). Acceptance: bass-line probe
peak growth ≤ 0.3 dB, fundamental deviation ≤ 0.2 dB, no DC; `evenMix` 1 gives a 2nd harmonic −26…−20 dBc.

**WP6 (P2) Top-band budget.** After WP3, cap the combined 3–6 kHz reduction (Grounding restraint ≤ 4 dB, vocal de-harsh,
DynamicEq ≤ 3 dB, guard 2+2) at about 4.5 dB by scaling the guard's cap with the remaining budget, using the existing meters
(`groundingRestraintDb()`, `lastDeharshDb()`, `reductionsDb()`). Test on the probe-4 scenario at maximum settings.

**WP7 (P2, only if WP2 traces show a UID drop at every track change).** Hold the recorder open for ≤ 2 s when a single-UID
capture drops to empty; gate the output to silence until a muted route returns; fade in. Only valid for single-UID capture
(a held UID that is not muted would be captured raw and doubled).

**WP8 (P1) Vocal realism: guard the voice, then test additions.** Owner target: the voice feels real, with its emotional
texture audible, and `space` never makes it digital, light or airy. Nothing below has been heard; the reference set is §7.
- *Protect first (acceptance tests, no new DSP).* On the vocal excerpts, at default settings with `space` at +1 and
  Svaresa on: ShrillGuard reduction ≤ 0.3 dB (a close-miked voice has a natural 3–5 kHz presence peak; the guard and the
  `SM-HARSH-1` policy must not shave it), BassTexture does not change vocal-band level by > 0.2 dB, the centre (mid) channel
  is unchanged by `space` (WP4 mono-sum identity), and the 150–350 Hz side gain stays ≥ −0.5 dB (a thinned room body reads
  "light"). Add a vocal-dominance guard: when the mid channel dominates 300 Hz–4 kHz and the analyzer shows no harsh
  residual beyond the WP3 threshold, scale the guard and the `harshDb` policy down (reuse the stereo tuner's vocal
  evidence; do not add a new detector unless the tuner has none).
- *Measure before touching.* Per excerpt, with `tools/mastering/` plus a new vocal-band report (mid/side energy ratio in
  300 Hz–4 kHz, sibilance 5–9 kHz against 1–3 kHz, 10–16 kHz breath band against the mix, crest factor of the 200 Hz–4 kHz
  band, side/mid correlation). Put the numbers in `docs/` as the vocal baseline. Run Svan's chain at defaults and report
  every band that moves more than 0.5 dB or the crest factor by more than 0.3 dB.
- *Candidate additions, each behind its own default-off control and a blind pair (ranked by risk):* (1) a breath/air
  keep-out so de-harsh and the guard never act above 9 kHz when the vocal dominates; (2) a vocal-band micro-dynamics
  check: no limiter or dynamic-EQ action on the 200 Hz–4 kHz band below its own crest-factor floor; (3) low-level
  even-order warmth on the vocal band (second harmonic of 200–800 Hz at about −45 dBc, parallel, DC-free), the same
  family as WP5's `evenMix`; (4) a short, level-gated early-reflection tail on the side channel instead of any
  decorrelator, so widening adds room rather than phase smear. Items 3 and 4 are speculative; ship only if the blind pair
  prefers them.

**WP9 (P1) Bass detail you can place in space.** Owner target: every layer of the bass audible (pick and string attack,
sustain, bass waves and glides, the rubbery, ported-box bounce), with texture that feels three-dimensional rather than
just louder. The bass is built in time layers; give each its own measured handle, all level-gated and bounded:
- *Attack (pick, string noise, kick-bass separation).* A bass-onset-gated lift of the 0.6–2.5 kHz "pick" band: detect the
  onset on the sub band (fast against slow envelope, as in `ShrillGuard` but inverted), raise the pick band by up to
  +2 dB for 5–15 ms. Between onsets the band changes < 0.1 dB. This is mid-range, not treble, so it does not touch the
  WP10 rule.
- *Body and sustain (40–250 Hz).* Existing `BassTexture` (range per WP5). Candidate: a bounded decay-extender on the bass
  band (slower release only, tail lift ≤ +3 dB, never above the note's own peak) so a held note keeps its shape.
- *Rubbery, ported-box bounce.* Candidate, speculative: a level-gated, low-Q resonant tail around 45–60 Hz at about
  −20 dB below the note, with `evenMix` supplying the tube-like even harmonic. Muddying is the risk: ship only if the blind
  pair prefers it, and make the centre frequency follow the detected note (or stay off) instead of ringing on one pitch.
- *Dimension.* Below about 150 Hz the bass stays centred and mono-safe. The apparent size and depth of a bass note comes
  from its harmonics above about 200 Hz, so spread **only the texture's wet harmonics** into the side channel (a small,
  fixed all-pass phase difference between left and right on 250–700 Hz, ≤ 0.3 ms equivalent). Because the spread lives in
  the side channel, the mono sum and the fundamental stay bit-identical. This relies on the general finding that low
  inter-channel coherence below roughly 700 Hz widens a source; verify it by blind pair, not by this sentence.
- *Acceptance (synthetic bass line + 808 glide from `p3_bass`, plus the owner's excerpts):* peak growth ≤ 0.3 dB;
  fundamental ≤ 0.2 dB; no DC; mono-sum change of the dimension path < 0.05 dB; pick-band lift only inside onset windows;
  decay-extender never raises a note above its own peak; level matched to ±0.1 dB for every blind pair. Stress case:
  heavily limited, boomy bass (the Metro Boomin reference) must come out no louder in the low band than it went in.
- *Optional, P3.* A "bass anatomy" display in Lab/Hi-Fi that draws attack, body, tail and harmonic level live from engine
  meters, drawn in code in the house palette. I read "visualised" mainly as heard; say if the owner means the screen.

**WP10 (P1 protect, P2 candidates) Highs: balance and analogue ease, nothing special.** Owner target: keep detail and
clarity, but make the highs pleasant and balanced, because uneven highs are what make listeners skip tracks or turn the
volume down. Winds (sax, trumpet) and moody strings (violin) should come across through emotion, not loudness.
- *Protect (Tier 1, tests only).* On the excerpts, at defaults: no band above 4 kHz changes by more than 0.3 dB unless the
  analyzer's residual (WP3) says it is in excess; a **detail-retention** check on 4–12 kHz (onset-strength correlation
  between input and output ≥ 0.98, envelope modulation depth ≥ 90 % of the input's) so softening never flattens the
  texture; shrill excerpts must lose perceived sharpness (Zwicker sharpness from the analyzer) while passing that check.
- *Balance candidates (default-off, blind pair).* (1) A slow **fatigue trim**: integrate 2–6 kHz energy over 30–60 s with an
  equal-loudness weighting and ease a high shelf by up to 1.5 dB when a track is persistently above its own tilt target;
  never faster than seconds, so it cannot pump. The phone's music-stream volume index is available and may scale it.
  (2) **Level-dependent top softening**: the 6–12 kHz band's gain falls a little as its own level rises above a knee
  (soft, ≤ 1.5 dB per 10 dB, 20–50 ms), so loud cymbal crashes soften while quiet air stays. This is the analogue-tape
  behaviour in a controllable form and differs from the guard (which keys on sustained excess, not level).
- *Emotion without loudness (winds, strings).* Candidate, speculative: where the Harmonic/Percussive split shows tonal
  dominance (the analyzer already has HPSS), expand slow envelope detail in 1–4 kHz (swells and decays at 0.5–2 Hz,
  vibrato envelope at 4–8 Hz) by ≤ 1.5 dB peak-to-peak, **loudness-neutral** (integrated level matched), and lean
  300–500 Hz warmth by ≤ 1 dB. Measure with a modulation-depth metric; the claim is "more expressive", never "louder".
- *Do not:* add air, brilliance or any treble boost; any new treble action must be reductive or loudness-neutral.

## 4. Rules that still apply

Allocation-free audio thread; a measured test for every DSP change; gold design tokens untouched; no GPL code; keep the ten
detection, 39 routing, nine workspace and eight control checks intact; never open a pull request; commit trailers carry the
session link and a generic co-author line with no model name. No version bump unless the owner asks. Document verified and
unverified items in `docs/HANDOFF.md`.

## 5. Probes and commands

`tools/probes/0.5.13/` holds `p1_cost.cpp` (construction, reset, CPU), `p2_dsp.cpp` (guard on tilted noise), `p3_bass.cpp`
(bass line and 808 glide), `p4_stack.cpp` (guard plus existing reducers), `p5_analyzer.cpp` (analyzer residuals),
`p6_space.cpp` (side gain and leak tables), `drive.py` (harmonic level vs drive). Build: configure the core with
`-DCMAKE_BUILD_TYPE=Release`, then `g++ -O2 -std=c++17 -Icore/include <probe>.cpp build/libeqcore.a -lpthread`. They are not
part of the CMake build. Turn the scenarios you rely on into tests in `core/tests/test_main.cpp`.

## 6. Questions for the owner

1. A Hi-Fi diagnostic report while Gaana drops, taken after WP2 ships.
2. Does Gaana create a new session for every track? (the WP2 trace answers this).
3. 10–20 s excerpts: a bass-heavy master with audible pick or string attack, a boomy/limited master, a bright guitar master, a hi-hat-heavy master, the four vocal masters in §7, and one or two each of a sax or trumpet passage and a violin passage. Needed for WP3, WP5 and WP8–WP10. Name the tracks and I add them to §7.
4. By "visualised" for the bass, do you mean heard in more detail, or also a live display (WP9 optional)?
4. Texture strength by route (speaker vs headphones) once WP5 is on the phone.

## 7. Vocal reference set (owner, 10 Oct 2026)

Use these four as the vocal masters, in the owner's words: the voice should feel real, with its emotional textures almost
tangible.
1. Melody Gardot, "Morning Sun"
2. Amit Trivedi, "Shauq"
3. A. R. Rahman, "Tere Paas Main" (female version)
4. Hale, "Blue Sky"

I have not heard or analysed any of them; no claim about their spectra or mixes is made here. They are the **negative
controls** for WP3, WP4 and WP8: the shrill guard, `space`, the harshness policy and BassTexture should leave them
audibly and measurably alone unless a blind pair says otherwise. They also bound the texture work: a change that helps
bass or hi-hats but costs these four is rejected.

How to use them with no PC: the owner uploads 15–20 s excerpts of each (one vocal-forward passage; one dense passage
where the voice sits in the full mix), features only, nothing stored. The implementer runs `analyze_corpus.py` and the
WP8 vocal-band report, and builds the acceptance numbers in WP3/WP4/WP8 from them. Without uploads, the owner can play
each in Svaresa with Hi-Fi on and tap **Learn this sound** after 20 s.

Order to build: follow the tiers in "Start here". Inside Tier 1: WP2 → WP1 → WP4 → WP3 → the WP8/WP10 protect tests. The
candidate additions in WP8–WP10 come last and only after the vocal and highs baselines exist.
