# Svan 0.5.13 — capture stability, bass texture, spacious without air, sustained shrill

> **Review note (2026-10-10).** An independent review with measurements corrects four points below. The corrected plan is
> [BUILD_BRIEF_0.5.14.md](BUILD_BRIEF_0.5.14.md). (1) The capture changes in §1 sit on code that does not run without DUMP,
> and a source handed to Engine A is never promoted back there (brief F1, F2). (2) The shrill guard's absolute thresholds are
> inert on music-like spectra, and on a harsh cluster the analyzer rates at +29 dB (F4). (3) The `space` "known limit" understates
> the problem: the side also has a −6.5 dB hole at 200 Hz (F5). (4) CPU and engine-rebuild costs were measured and are not a
> concern (F3, F7).

Status: code and core/JVM tests are in this branch. **No listening has been done.** Nothing here is a sound-quality
claim until the blind protocol in §6 has run on the owner's phones. Verified here: the core suite (196 checks, 0 failed;
ASan and UBSan clean), the Android debug build and the JVM unit tests (Gradle). Not verified here: the emulator
end-to-end checks (they run in CI after push), any real Gaana session, any phone or DAC.

## 1. What changed

### Capture stability (Gaana and other streaming players)

Gaana can be captured without DUMP but drops out. No device logs were available, so this is a diagnosis from the code,
not a reproduction. Three mechanisms could each disconnect an app that is already captured:

1. **Silence treated as a blocked capture.** Four seconds of exact zeros from a muted source, while another player is
   active, failed the source open and recorded a strike. A buffering stream produces exactly that. Strikes gave 3 minutes
   on system effects, then 15, then the rest of the session (`FailOpenBackoff`).
2. **A sibling session made the whole app unprocessable.** A format change or a second track creates a new media
   session. Until the 1.5 s admission delay passed, `CapturePolicy.eligibleUids` excluded the app's UID, and the muted
   route was handed to Engine A (`UID_GROUP_UNSAFE`).
3. **Proven apps stayed on system effects.** `retryParkedPlayers` promoted parked players only when no capture was
   running. An app whose capture was already proven stayed on Engine A for as long as any other app was captured.

Changes:
- Silence after the epoch has heard the source is a **stall**: the fail-open waits 12 s instead of 4 s, skips the
  sample-rate retry (a capture restart), and hands over for 20 s with no strike. At most two stall hand-overs per app
  per capture session. A third silence takes the existing strike path, so a truly blocked source cannot cycle forever.
- A new session of an app whose audio already goes to Engine B is admitted at once. Admission still refuses system,
  utility, notification and sonification sounds.
- A proven app returns to Engine B even while another source is captured. Unproven apps still wait for an idle engine
  before their check.
- `com.gaana` joins the immediate-admission list. This is admission only. It is not a capture-permission claim: capture
  is still decided by the app's manifest and stream flags.

What would confirm or refute the diagnosis: a Hi-Fi **Share diagnostic report** taken while Gaana drops. The engine
trace lines `fail-open:`, `stall:` and `capture level:` show which path fired and when.

### Bass texture (odd harmonics for the bass notes)

A bass note on a phone or small speaker is heard largely through its harmonics. `BassTexture` adds only those:

- The mid channel's 150 Hz-and-below band goes through an odd-order `tanh` shaper, `tanh(k·x)/k`. Drive `k` rises with the
  note's level from 0.5 (quiet, almost linear) to 2.5 (at −12 dBFS).
- The shaper's own fundamental gain at that level is subtracted (a precomputed describing function), so the fundamental
  is not compressed. Only the generated harmonics are added, high-passed at 30 Hz. The same wet goes to L and R, so the
  side channel is untouched.
- Depth 0 is bit-exact. Depth scales the harmonics linearly.
- In the app it follows the house body weight (`groundingBody`), so the bass gains texture where the voicing asks for
  weight. Blind-test renders use the same mapping.

### Spacious without air (the `space` knob)

The side path adds `space` as a gain on everything above 180 Hz, including the top. Raising it lifted the air, which is
the "digital, too airy" complaint. Now a high shelf at 6 kHz takes the widening back above that frequency, and the static
response model uses the same shelf. Narrowing is unchanged.

### Sustained-shrill guard (presence and sizzle, transient-friendly)

`ShrillGuard` watches two fourth-order bands with zero phase at their centres: presence at 4 kHz and sizzle at 8 kHz.
A band is shrill when its slow (40 ms) power sits above a threshold relative to the whole mix (presence −9 dB, sizzle
−12 dB). Reduction is 0.5 dB per dB of excess, capped at 2 dB per band. A sustain gate is closed while the fast (2 ms)
power runs well above the slow one. That is an attack, so a hi-hat click or a pick keeps its definition, and the gate
opens once the energy holds, so the ringing that makes a sound shrill is what gets reduced. Both channels get the same
gain. The app drives it from the house transient-restraint weight (`groundingRestraint`), so no new control is added.

Thresholds and caps are design values chosen from filter responses, not from music. A 1 kHz tone sits about −24 dB in the
presence band (never reduced). A 4 kHz tone sits about −15 dB in the sizzle band, and an 8 kHz tone about −10 dB in the
presence band. Both are under their thresholds, so no band reduces another band's content.

## 2. Measured in the core suite

| Check | Result |
|---|---|
| Bass texture, −12 dBFS 50 Hz, depth 1 | 3rd harmonic −32.6 dBc (predicted k²A²/12 with the 5th-order correction); 2nd harmonic below −120 dBc; fundamental within 0.05 dB |
| Bass texture, −50 dBFS | 3rd harmonic below −90 dBc (effectively linear) |
| Bass texture, depth 0 | bit-exact bypass |
| Bass texture, depth 0.5 | 3rd harmonic 6 dB lower than depth 1 |
| Bass texture, 1 kHz and 2 kHz tones beside the bass | unchanged within 0.05 dB; side (L−R) unchanged to 1e-12 |
| Space +1, side gain at 300 Hz / 2 kHz / 8 kHz / 10 kHz | +1.38 / +6.05 / +1.47 / +0.61 dB (static model agrees within 0.15 dB at each) |
| Space, before this change | nominal +6 dB across the band above 180 Hz; from about 2 kHz up the realised gain was the same +6 dB (not re-measured on the old code); at 300 Hz the same phase limit applied |
| Shrill guard, sustained 4 kHz tone, depth 1 | −2.0 dB (cap); the reported presence reduction −2.0 dB, sizzle 0.0 dB |
| Shrill guard, sustained 8 kHz tone, depth 1 | −2.0 dB (cap) |
| Shrill guard, clean 1 kHz tone | exactly 0 dB |
| Shrill guard, sustained 4 kHz at depth 0.5 | −1.0 dB (linear in depth) |
| Shrill guard, 3 ms 4 kHz burst (attack) | peak change +0.00 dB |
| Shrill guard, after 2.5 s digital silence | reduction exactly 0 before the next sound |
| Shrill guard, hard-panned / centred input | empty channel stays exactly 0; centred channels identical |
| Allocation on the audio path (bass texture, shrill guard) | none |

Known limit, measured: the 300 Hz body widens only about +1.4 dB. The side path is dry-plus-delta, and the high-band delta
meets the 180 Hz crossover phase there (about 107°). The old code had the same limit, so this is not a regression. §4
item 3 describes the fix.

## 3. Evidence behind the choices

- **Even-order distortion lowers pleasantness** (see `RESEARCH_GROUNDED_SOUND.md` §3). So the texture is odd-order only,
  and even-order stays a blind-test knob.
- **Virtual bass is an established technique, and its failure modes are known.**
  - Harmonics must be phase-matched to the original to sound natural: [Phase-matched Harmonic Generation and Variable Slope Exponential Weighting for Virtual Bass System](https://consensus.app/papers/details/69e752fc791b504fa2e7d9518a7526a8/?utm_source=claude_desktop) (Moon et al., 2016). The texture derives its harmonics from the same bass signal, so they are phase-locked by construction.
  - Harmonics should come from the same direction as the high frequencies: [Subjective evaluation of the combining effect between the virtual bass and head related transfer functions](https://consensus.app/papers/details/f701d72440d956cea645fec33325be68/?utm_source=claude_desktop) (Chen et al., 2021). The texture is centred, so it follows this.
  - Separating transient from steady components before generating harmonics improves both attack and sustain: [A psychoacoustic bass enhancement system with improved transient and steady-state performance](https://consensus.app/papers/details/3f6b8c7b8bf35074961e1741a6974d5c/?utm_source=claude_desktop) (Mu et al., 2012). The shrill guard's sustain gate applies the same principle to the top bands.
  - Virtual bass can add perceptual distortion, so it needs listening tests, not only metrics: [Self-Supervised Mean Opinion Score Prediction of Phase-Vocoder-Based Virtual Bass System](https://consensus.app/papers/details/43eb93d337585acfb15363e576754737/?utm_source=claude_desktop) (Gou et al., 2024).
- **Median-filtering separation of harmonic and percussive energy** is the standard tool for a tonal-only de-shrill:
  [Harmonic/Percussive Separation using Median Filtering](https://dafx.de/paper-archive/details/DsmIVcydPX66AuaqEmKyTQ) (FitzGerald, DAFx 2010; [PDF](https://www.audiolabs-erlangen.com/resources/aps-w23/papers/2010_FitzGerald_HarmonicPercussiveSep_DAFx.pdf)).
- **Decorrelation as the route to width** (Kendall's work on spatial imagery) is the basis of §4 item 4. The venue and year
  I recall for that paper are from memory and were not confirmed in this session, so no citation is given here.

## 4. Theories, ranked from practical to wild

Each item states the mechanism, its evidence, its status, and the test that would settle it.

1. **Phase-coherent harmonic texture** (implemented). Harmonics come from the same bass signal, so they are phase-locked,
   and the shaper's own fundamental gain is removed. Test: fundamental within 0.05 dB; odd order only (§2).
2. **Route-aware texture** (designed, not built). Phone speakers need more texture than wired headphones. Scale the depth
   by the measured low-frequency capability of the output route, using Svaresa's route classification. Test: blind A/B at
   matched loudness on speaker, wired and Bluetooth.
3. **Phase-matched side crossover** (designed, not built). Fixes the 300 Hz body limit (§2). The dry path would get an
   all-pass with the same phase as the 180 Hz crossover's high-pass, so widening is not phase-limited at the body. Test:
   side gain at 300 Hz +6 ±0.4 dB, mono sum unchanged, the hard-pan leak tests still pass.
4. **Decorrelated presence tail** (wild, not built). Spaciousness without raising the top: a low-level decorrelated copy
   in about 1.5–5 kHz, made with an all-pass decorrelator. Test: interaural correlation at 2 kHz drops, and the level above
   6 kHz does not change.
5. **Tonal-only de-shrill** (wild, not built). Median-filter STFT separation (FitzGerald 2010) would reduce only the
   tonal energy in 3–5 kHz, so the hi-hat click keeps its definition while the guitar's shrill ring is cut. Cost: an STFT
   on the audio thread, which needs a latency budget. Test: attack peak unchanged, sustained tone reduced by the same cap
   as now.
6. **Sharpness closed loop** (wild, not built). Compute the output's sharpness every 100 ms with a Zwicker-style model
   (Fastl & Zwicker, *Psychoacoustics: Facts and Models*, not re-checked in this session), and drive the guard so
   sharpness stays under a target at the current loudness. Test: a bright synthetic mix reaches the target within the
   guard's cap.
7. **Analog colour as linear filtering** (wild, not built). A head bump at 60–110 Hz, a gentle 12 kHz roll-off and a small
   group-delay warp, instead of distortion. The distortion evidence argues against adding harmonics to vocals, but it does
   not rule out linear colour. Test: blind A/B only.
8. **Tune by the owner's ears** (process). Each change above is one pair in the blind protocol (§6), not a bundle.

## 5. Limits and risks

- **No listening has been done.** Every sound change is unverified by ears, including the defaults.
- **The house voicing now includes both changes by default:** texture follows body weight and the shrill guard follows
  transient restraint. Expect the bass to carry more weight and the presence band to be slightly tamer. Both are
  bounded, and the mapping is one line each in `CaptureService.applyEq` and `BlindRenderer` if the owner prefers less.
- **CPU.** `ShrillGuard` runs a few logarithms and powers per sample, and `BassTexture` runs `tanh` and an interpolated
  table per sample. Measure with `eqcore_bench` on the phones before raising oversampling.
- **CI checks.** The emulator end-to-end checks include a bass-versus-mid balance check (measured +5.6 dB in an earlier
  CI run). The new bass texture and shrill guard can move it. Read the CI log for that check after push.
- **Stall hand-overs** are bounded (two per app per capture session) but can still switch an app to system effects for
  20 s at a time.
- **Skirts.** The guard is a parallel band subtraction. It is exact at each band's centre; its skirts carry small phase
  effects (below about 1 dB one octave away). The caps apply at the centres.

## 6. What the owner needs to provide, and the blind protocol

- A Hi-Fi diagnostic report taken while Gaana drops (the player visible, screen on).
- Ten to twenty seconds of each: a bass-heavy master, a guitar-heavy master with bright presence, a hi-hat-heavy master
  and a vocal master. The owner has no PC, so analysis runs here on uploads, with `tools/mastering/ab_compare.py`.
- Blind pairs, one per change, at matched loudness: texture on or off; shrill guard on or off; space at +1 before and
  after. Use the existing blind matched-listening tool. Judge on voice presence, rhythm weight, fatigue and harshness,
  as in `SONIC_IDENTITY.md`. Claim a preference only when the protocol has run.

## References

- [Phase-matched Harmonic Generation and Variable Slope Exponential Weighting for Virtual Bass System](https://consensus.app/papers/details/69e752fc791b504fa2e7d9518a7526a8/?utm_source=claude_desktop) — Moon et al., 2016
- [Subjective evaluation of the combining effect between the virtual bass and head related transfer functions](https://consensus.app/papers/details/f701d72440d956cea645fec33325be68/?utm_source=claude_desktop) — Chen et al., 2021
- [A psychoacoustic bass enhancement system with improved transient and steady-state performance](https://consensus.app/papers/details/3f6b8c7b8bf35074961e1741a6974d5c/?utm_source=claude_desktop) — Mu et al., 2012 (ICASSP)
- [Self-Supervised Mean Opinion Score Prediction of Phase-Vocoder-Based Virtual Bass System](https://consensus.app/papers/details/43eb93d337585acfb15363e576754737/?utm_source=claude_desktop) — Gou et al., 2024 (EUSIPCO)
- [Harmonic/Percussive Separation using Median Filtering](https://dafx.de/paper-archive/details/DsmIVcydPX66AuaqEmKyTQ) — FitzGerald, DAFx 2010
- Internal: `docs/RESEARCH_GROUNDED_SOUND.md` §3 (distortion evidence), `docs/SONIC_IDENTITY.md` (house voicing and listening protocol).
