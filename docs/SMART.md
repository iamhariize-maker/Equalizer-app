Latest native quality and listening work: [0.5.5 quality lab](QUALITY_LAB_0.5.5.md).

# Svaramanas — Svan's sound intelligence

Latest increment: [0.5.4 engine quality](ENGINE_QUALITY_0.5.4.md). Svaresa now takes effective
headroom/overload-protection authority without overwriting manual choices. Native EQ and gain
transitions are smoothed and limiter history persists through adaptation. This is not authority
to invent source detail, instrument labels or forced stereo effects.

*Svara (sound) + manas (mind).* Svaramanas is the on-device "smart" layer of Svan. It analyzes source
audio only when Android lets Engine B capture it; on Engine A it uses static route, preference and
profile information. It drives Svan's measured DSP inside explicit limits. This document is the design
and build plan. Every claim about sound quality must be measured (rule 2 in `AGENTS.md`) before it
appears in the app or Play listing. See [`RESEARCH_SVARAMANAS.md`](RESEARCH_SVARAMANAS.md) for the
evidence review and ranked recommendations.

## Status (0.5.2-detection-preview)

Built and tested:
- `core/…/analyzer.h` **SourceAnalyzer**: gated K-weighted loudness (EBU 3341 sine reads −23.0 LUFS),
  peak, PLR, clipping rate, stereo correlation / mono detection, source bandwidth estimate (a synthetic 16 kHz source found at
  15.5–16.8 kHz), third-octave balance and mud/boom/harsh/air deviations from the mix's own tilt
  (a −3 dB/oct dark mix is not flagged). Pauses don't wash out the picture. Runs inside `Engine` on the
  *input* (Engine B), allocation-free; two 4096-point FFTs per 85 ms (mid and side).
- `core/…/svaramanas.h` **policy + guardrails**: feel × categories (3 always, a 4th only without a range
  clash, never 5), 6 dB emphasis budget, ±3 dB per band, overlap softening, analyser-driven trims (≤2.5 dB),
  bandwidth-estimate/mono/crushed-master respect rules, fixed band skeleton, reason codes.
  Tested on all 6876 feel × category × strength combinations; **full-guide loudness within 0.25 dB on the checked synthetic mixes** (target ±0.5 dB),
  including stereo tuners and bass character; older tests exercised only EQ and preamp.
  See [0.5.2 sound validation](SOUND_VALIDATION_0.5.2.md) for cases and limits.
- App: `svaramanas/` (controller, dialog activity, overlay bubble service, Quick Settings tile),
  in-app bubble (tap = open, hold = hear the original), notification action. Static plan on system
  effects (Engine A); adaptive on the audiophile engine (re-planned every 3 s, slewed ≤0.5 dB per band).
- Emulator e2e: T21 the plan reaches system effects at the predicted level; T22 the analyser hears the
  captured source on a real device image.

Not built yet: player coaching; masking-aware dynamic EQ and resonance suppression; calibrated quiet
listening; hearing test; blind A/B tool; small ML controllers. Track memory is deferred without
notification access. Volume compensation and conservative exact headphone lookup are already built.
Known limits: detection thresholds are first guesses, not yet tuned by ear; with auto headroom on,
Engine A can end up quieter than loudness-matched (safety wins); the overlay bubble does not yet hide
itself over full-screen video. The owner has reported delay/echo-like playback on YouTube Music over
Bluetooth on the TECNO LH7n; this real-device issue is unresolved and takes priority over new
sound-changing DSP or ML. Emulator tones do not settle it.

New real-phone detection evidence (5 October 2026): Apple Music was detected earlier with Fosi Audio
IM4 but is now missing over wired output; Neutron has not appeared; no player session appeared using
Realme Buds Air 8 with LHDC either on or off. These are user-reported and have not been reproduced from
device logs. Player-session discovery and headphone/output routing are separate questions. The current
increment adds explicit package visibility for common streaming/local players, tolerant parsing of
playback-report field variants, and a local scan summary that distinguishes no track, no usable session
ID, and a parsed media session. It also adds one-tick listening callouts, a controlled Full impact
starting preset and saveable tab state. Neither the new detection path nor this bass profile has been
verified on the TECNO yet; listing an app package is not proof that its session is visible or captured.

## 0.5.2 sound refinement

The owner now reports working music detection and promising listening with the Fosi IM4 on 0.5.1.
That is listener feedback, not a full route/app matrix. Streaming apps remain first priority.

Guide profiles now pair foreground presence with low-mid masking cuts for voice, guitar, strings,
piano, brass/winds and bass. Strength scales intimacy and stereo focus as well as EQ; zero is truly
neutral. Svaresa analyses total M/S energy, so widely mixed instruments and ambience no longer vanish
from its tonal analysis. Measured excess correction is more assertive, with unchanged dead bands and
4 dB limits. It still does not invent instrument labels, add ambience to mono, or reshape clean masters
without evidence. Complete guide/context matching uses the live frequency-dependent M/S spectrum
when available; system effects explicitly label the reference-based match as an estimate.

## Svaresa 0.5 — what changed

Svaresa used to be inert on system effects (the default) because it had no audio to analyse there. It now has three
layers that need no capture, and a stronger measured layer when Hi-Fi can listen:
1. **Quiet listening** (`SvaresaBrain`): ISO 226 contour difference between the current assumed loudness
   (30–80 phon mapped from the volume setting) and a 65-phon reference → bass shelf ≤ 6 dB, treble shelf ≤ 3 dB
   (bass ≤ 1.5 dB on the phone speaker), matched with the combined guide/context curve. The mapping ignores headphone sensitivity: a rough guide.
2. **Night comfort**: auto by clock (full 22:30–05:30, one-hour ramps), on or off: −2.5 dB sub-bass shelf,
   −1 dB at 3.8 kHz and a gentle 1.5:1 multiband level-evening (system effects; the capture engine does tone only).
3. **Headphone recognition**: connected device name → AutoEq entry (exact/unambiguous only) → Harman correction;
   never overrides a correction the listener chose; removed when unplugged.
4. **Measured policy** (Engine B only): boom/mud/harsh limits 4 dB (guided: 2.5), tilt shelves ≤ 2.5 dB toward −2.5 dB/oct
   (±1.5 dead band, first guess), harshness-driven smoothing suggestion on both engines.
Each of 1–3 has a switch. Nothing here is claimed to sound better until listened to; the numbers shown in the UI are
the plan's real values.

## Sound modes — Svaramanas and Svaresa

The names are paired with everyday labels so listeners can choose by purpose:

- **Sound guide · Svaramanas** (*svara* + *manas*, sound + mind): the listener chooses a feel and
  priorities; the guide shapes those choices within the existing gain, headroom and loudness limits.
- **Auto master · Svaresa** (*Svareśa*, master of sound): a conservative automatic mode. It ignores
  guided taste lifts and only uses measured corrections such as boom, muddiness, harshness and a
  full-range dullness check. It keeps the correction cap, lossy-ceiling and limited-master guards,
  loudness matching, and slow adaptive slew. It does not guess instruments or widen stereo.

Both modes use a local rule-based controller, not a newly trained AI model. Svaresa can analyze only
playback that Android exposes to Engine B; when capture is unavailable it holds the user's selected EQ
and tuning but has no source measurements to correct. The automatic mode currently avoids changing the
bass transient shaper, vocal tuner, or stereo width because there is no validated source model to decide
when those subjective controls would help. A trained controller requires opt-in, level-matched listener
choices, held-out tracks/routes, and a measured advantage over these rules. No audio samples or preferences
are uploaded by this work.

The Svaramanas identity is a gold sound-and-mind seal; Svaresa uses a related gold sonic-brain/wave mark.
The mode cards and accessible names lead with “Sound guide” and “Auto master”; the Sanskrit names are
identity, not prerequisites for understanding what each control does.

## 1. Persona and behaviour priority

Svaramanas is a **tonal police** and, underneath, a **passionate audiophile**: it enforces what protects
the sound, it chases clarity and perceived resolution, and it respects beautiful sound however unusual.

Decision priority, highest first (a lower rule never overrides a higher one):

1. **Do no harm.** Never clip, never exceed headroom, never win by being louder. Every change is
   loudness-matched against the unprocessed signal (target within ±0.5 dB). Bounded gains, instant revert.
2. **The user's stated priority.** What the user asked for in the dialog (feel + up to 3-4 instrument
   categories) outranks Svaramanas's own taste.
3. **Clarity and perceived resolution.** Reduce masking, harshness and muddiness; lift buried detail.
4. **Respect the music.** Do not "correct" intentional character: lo-fi, distorted guitars, heavy bass
   genres, narrow vintage mixes, new and unfamiliar timbres. When intent is unclear, do less and say so.
5. **Be explicit.** Always able to show what it heard, what it changed and why; one tap to bypass.

Voice in the UI: brief, confident, warm, never jargon-first. Explain only what the sensors measured and
what the policy actually changed. Do not claim to have identified a vocal, guitar, or masking
relationship until a validated detector supports that explanation. A truthful current example is
"I reduced a measured low-mid excess slightly; tap to compare or undo."

## 2. Architecture: sense → decide → act → verify

ML (when used) is a **controller of the DSP, never the signal path**. Models output parameters at a slow
hop (tens of ms); audio is still processed by `eqcore`. This keeps latency unchanged, makes behaviour
bounded and testable, and avoids artefacts.

```
 Sense ──────────► Decide ─────────► Act ───────────► Verify
 SourceAnalyzer    Svaramanas        eqcore chain      Loudness match,
 Route/Device      policy (rules,    (parametric EQ,   headroom, A/B,
 App profile       later small ML)   bass, stereo,     bypass, bounded
 Environment       Guardrails        dynamic EQ …)     changes
 Listener/taste
```

### 2.1 Sense

| Input | How | Engine B | Engine A |
|---|---|---|---|
| Spectral content: estimated bandwidth and long-term tonal balance | `SourceAnalyzer` in `core/` on the captured stream | yes | no (no access to audio) |
| Loudness, dynamic range, crest factor, clipping / inter-sample peaks | `SourceAnalyzer` | yes | no |
| Stereo width / mono-in-stereo | `SourceAnalyzer` (mid/side energy) | yes | no |
| App identity and known behaviour | package name → `AppProfile` table | yes | yes |
| Output route: connected device type/name and volume; codec only if reported | `AudioManager` / device callbacks | yes | yes |
| Ambient noise (optional, opt-in) | mic level in bands, on-device, never stored | yes | yes |
| Track identity / replays (see §5) | MediaSession metadata + playback position | yes | yes |
| Listener: hearing profile, A/B choices, instrument priorities | local store | yes | yes |

Capture-blocked or undetected apps (which may change by app version, route and Android build) only get
the non-audio inputs, so Svaramanas there is **static-smart** (route, selected profile and taste). A
cutoff estimate describes observed bandwidth; it does not identify MP3/AAC/Opus or prove the stream is
lossy. Engine B checks already-playing apps before it opens its main capture recorder. An unknown app
discovered after Engine B is running stays audible on Engine A until capture is restarted with that app
playing; this avoids opening a second recorder on Android audio stacks that reject parallel playback
capture sessions.

### 2.2 Decide

Phase 1 is a deterministic policy: features in, bounded parameter targets out. Phase 2 may add a small
on-device controller (LiteRT, ≲1M parameters) **only where held-out and blind tests show it beats the
deterministic policy**. A model can report confidence for a task such as vocal-presence; it does not
separate a mix or prove masking. YAMNet is an event-classification baseline, not a production clarity
model. No cloud, no uploads, no sample-level model output.

### 2.3 Act — what the existing chain already offers
Parametric EQ (128 bands, oversampled), bass tuner, vocal tuner, orchestral amplifier, mid/side
`StereoTuner`, de-harsh band, AutoEq tunings, headroom + Automatic Gain Protection. New DSP needed:

- **Optional dynamic EQ research**: a Bark/ERB estimate can guide a test, but a mixed stereo spectrum
  does not identify which instrument masks another. Do not blindly boost low-margin bands. Once routing
  is stable, test a small reduction-only persistent-peak suppressor with a separate fast feature path,
  explicit confidence, bounded attack/release and fast bypass. Keep it off by default until listening
  evidence supports it.
- **Intelligibility lift** for vocals/detail, gated by the vocal-presence estimate.
- **Noise-aware lift**: raise the bands that keep detail audible over ambient noise (works on Engine A).
- **Lossy-source handling**: treat cutoffs as bandwidth estimates, not codec fingerprints. Optional
  bandwidth extension can only generate plausible content; it cannot restore missing original
  information. Do not enable it by default or claim restoration without reference-based blind evidence.
- **Loudness compensation** tied to system volume (equal-loudness contours).

### 2.4 Verify
Guardrails live in one `Guardrails` module that every Decide output passes through: gain/Q limits per
band, total emphasis budget, headroom check, loudness-match trim, slew-rate limiting, panic bypass.
BS.1770/EBU loudness is a level guardrail, not a clarity score. PEAQ needs a reference and is scoped to
perceptual impairment comparisons; STOI/ESTOI and SII are speech measures, not full-music quality
scores. The blind A/B tool is the evidence gate for listener preference.

## 3. Instrument priorities (the dialog)

Floating bubble → dialog "What kind of sound do you want?":

1. **Feel** (single): warm · bright · balanced · punchy · spacious · intimate.
2. **Instruments you prioritise** (1 to 4): vocals · strings & orchestra · piano & keys · guitars ·
   drums & percussion · bass · brass & winds · synth & electronic · space & ambience.
3. Loudness-matched A/B preview, then confirm. Saved per headphone and optionally per app.

Why the cap: every emphasis costs headroom and masks neighbours; vocals and guitars both occupy
~1–4 kHz, so too many priorities cancel out. Rules:

- Fixed **emphasis budget** spread over the chosen categories (default cap 3; a 4th is accepted only if
  its bands do not conflict with the first three, otherwise the UI explains which one clashes).
- Overlaps resolved by **dynamic priority** (e.g. guitar is lifted gently in the shared band only while
  the vocal is not buried).
- CI test: total gain bounded, no clipping, loudness within ±0.5 dB for every category combination.

## 4. The bubble

- Draggable gold स्वर mark with a subtle neural glyph (own artwork, not an emoji); tap → dialog;
  long-press → instant bypass; hides over full-screen video.
- Needs "Display over other apps" + the existing foreground service. **Fallbacks that must keep the app
  fully usable**: Quick Settings tile and a notification shortcut. Re-check the current Play
  `SYSTEM_ALERT_WINDOW` policy and prepare the justification before listing.

## 5. Track memory: favourites and most-replayed parts

Goal: after explicit opt-in, infer which tracks or moments a listener returns to and offer an editable
per-track profile. A replay cue is not proof that the listener likes a track or wants it processed
differently.

- **Owner restriction (5 October 2026)**: no notification-listener access for this feature.
  Cross-app track metadata and seek/replay history are unavailable through the current
  approved sources. Defer automatic replay inference rather than restoring that permission.
- **Possible signals**: manually labelled profiles or an explicitly opted-in, on-device
  fingerprint using the already authorized Engine B playback capture. Neither is built yet.
- **Permission**: use existing playback-capture consent only; no additional notification,
  SMS or accessibility permission. Explicit opt-in, off by default, one-tap wipe.
- **Privacy rules**: all on-device; no upload; no raw titles stored unless the user enables
  "show my favourites"; documented in `docs/PRIVACY.md` before shipping.
- **Behaviour limits**: it may only *shape bounded Svaramanas parameters for that track/segment*, never
  create new loudness or break guardrails. Per-track settings are suggestions the user can see and edit.
- **Honesty**: a "favourite" is inferred; the UI must say "I noticed you replay this" and allow
  correction. No claim of improved sound until A/B evidence exists.

## 6. Source and app profiles

`AppProfile` (shipped as a versioned JSON in the APK, updated through Play releases, built from our own
test results, no telemetry): per observed app version, Android build, route and test date, store capture
result/engine/fallback and confidence. Normalisation, own EQ, exclusive output and codec are
`user-confirmed` or `unknown` unless directly measured; do not infer them from a package name. Used for
the **player coach**:

- Player has its own EQ/effects → warn about double processing, suggest turning it off.
- Exclusive USB / bit-perfect mode (Neutron, HiBy, Onkyo) → Svan cannot touch it; say so and how to fix.
- Normalisation on → explain interaction with Svaramanas's loudness match.

Declared visibility list: YouTube Music, YouTube, Spotify, Amazon Music, Apple Music, Tidal, Deezer,
Qobuz, SoundCloud, Pandora, Neutron, HiBy, FiiO Music, ONKYO HF Player, Poweramp, USB Audio Player Pro,
VLC, foobar2000, AIMP, Musicolet, Pulsar and Plex. This list allows UID/name resolution when the system
reports a package; capture/session support remains unknown until probed on a particular phone and output
mode.

## 7. Test plan

**CI (emulator/unit, deterministic)**
- `SourceAnalyzer`: synthetic signals with known flaws (lowpass at 16/19 kHz, clipping, mono-in-stereo,
  boomy 200 Hz bump, bright 4 kHz) → detected within tolerance.
- Policy: for every feel × category combination, outputs within bounds; loudness-matched ±0.5 dB;
  zero clipping; headroom preserved; bypass restores the original level.
- Dynamic EQ / resonance suppression: measured reduction on synthetic masker/target pairs.
- Engine A static-smart paths measured with the existing e2e harness.

**Real hardware — the LG V60 ThinQ (arm64, wired DAC) and the user's main phone**
- **Test Pilot** mode in the app: one tap probes active or user-selected visible players and exports a report (capture
  allowed, which engine took it, double-audio check, measured level change, route, errors).
  Reads routing facts only — never account data.
- Matrix per player (YT Music, Neutron, Apple Music, Spotify free tier, Amazon Music free tier, …):
  capture allowed/blocked · single processed copy · wired and Bluetooth · 30 min with screen off ·
  battery drain · player-side effects/exclusive mode.
- Streaming apps with DRM/account checks are not tested in CI emulators.

**Human listening** — the only evidence of listener preference: in-app blind A/B (loudness-matched,
randomised) with results kept locally. Use a controlled test method that fits the question; a small
panel study is required before a public quality claim.

## 8. Build order

1. Complete review of the current CI run; inspect e2e logs/screenshots and deliver its preview if green.
2. On the TECNO, complete the DUMP discovery grant (the owner's 5 October report shows it missing),
   then verify Spotify, Amazon Music and YouTube Music discovery on wired and Bluetooth routes;
   Apple Music follows, advanced players such as Neutron later. Then
   isolate the YT Music/Bluetooth delay or double-copy report using one engine at a time. Keep broader
   sound-changing work behind this real-device check.
3. Build Test Pilot + evidence-backed `AppProfile`; run the player/route matrix on the TECNO and V60.
4. Level-match and compare Clean impact and Full impact against Flat on bass-rich and vocal-led passages;
   revise presets only from real listening results. Then build the randomized, level-matched blind A/B tool.
5. Prototype bounded dynamic EQ/resonance suppression as an optional feature; measure synthetic pairs,
   Engine loudness/peak/headroom, bypass, and human preference before default-on.
6. Add hearing/headphone personalization only where the route/profile is known. The current AutoEq index
   has no exact entry for Fosi Audio IM4 or Realme Buds Air 8; accept imported measurements and do not
   substitute a nearby product.
7. Consider volume/noise compensation and track memory after their calibration, permission and privacy
   design. Track memory remains explicit opt-in and off by default.
8. Add a LiteRT controller only if held-out listening beats the deterministic policy. Keep it off
   Engine A, which cannot observe the audio.

## 9. Constraints and open questions

The 6 October 2026 follow-up authorizes optional package/playback-state recognition
through Android notification access, superseding the 0.5.2 restriction for player naming
only. Track history/metadata remain out of scope. Real audio sessions come from shell
audio reports, broadcasts or existing/manual DUMP grants. Recognition never proves
processing or capture eligibility. Svaresa's Engine A analysis tap is still unbuilt.

- No root, no Shizuku dependency for the default path (Shizuku stays an optional extra).
- Play: MediaProjection + foreground-service disclosure, overlay
  justification, privacy policy covering playback-capture use.
- Unknown until probed on the V60: which of Apple Music / Amazon Music / HiBy / Onkyo allow capture.
- The TECNO's reported YouTube Music/Bluetooth delay or echo is not resolved by the emulator suite; the
  output queue is not end-to-end Bluetooth latency.
- App licence still undecided (ask the user).
- Name/brand: always "Svaramanas" (never "SvanMind"); gold design system, no new hues.

## Extended EQ control (0.5.3)

Svaresa now defaults to owning Extended EQ, with live parametric or actually fitted graphic bands,
bounded listener gain preferences, summed-response boost checks and matching of applied filters.
Manual curves and layouts are kept separately. See [EQ workspace](EQ_WORKSPACE_0.5.3.md) for
measured conversion cases, migration/override behavior, UI improvements and engine limits.
