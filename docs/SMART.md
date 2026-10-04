# Svaramanas — Svan's sound intelligence

*Svara (sound) + manas (mind).* Svaramanas is the on-device "smart" layer of Svan: it listens to what is
actually playing, in any player, and drives Svan's existing measured DSP to get the best sound the
source allows. This document is the design and build plan. **Nothing here is implemented yet**; every
claim about sound quality must be measured (rule 2 in `AGENTS.md`) before it appears in the app or the
Play listing.

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

Voice in the UI: brief, confident, warm, never jargon-first. It explains in plain words ("the vocal was
sitting behind the guitars, so I lifted it 2 dB around 3 kHz when it gets buried").

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
| Spectral content: HF cutoff (lossy ceiling), tonal balance, masking margins | `SourceAnalyzer` in `core/` on the captured stream | yes | no (no access to audio) |
| Loudness, dynamic range, crest factor, clipping / inter-sample peaks | `SourceAnalyzer` | yes | no |
| Stereo width / mono-in-stereo | `SourceAnalyzer` (mid/side energy) | yes | no |
| App identity and known behaviour | package name → `AppProfile` table | yes | yes |
| Output route: headphone model, speaker, BT codec, USB DAC, volume | `AudioManager` / device callbacks | yes | yes |
| Ambient noise (optional, opt-in) | mic level in bands, on-device, never stored | yes | yes |
| Track identity / replays (see §5) | MediaSession metadata + playback position | yes | yes |
| Listener: hearing profile, A/B choices, instrument priorities | local store | yes | yes |

Capture-blocked apps (e.g. Spotify; Apple and Amazon Music to be probed on the V60) only get the
non-audio inputs, so Svaramanas there is **static-smart** (route, hearing, loudness, noise, taste).

### 2.2 Decide

Phase 1 is a deterministic policy: features in, bounded parameter targets out. Phase 2 adds small
on-device models (LiteRT, ≲1M parameters), **only where blind tests show they beat the rules**.
Candidate models: vocal-presence estimator, genre/intent classifier (to implement priority 4),
bandwidth-extension controller. No cloud, no uploads.

### 2.3 Act — what the existing chain already offers
Parametric EQ (128 bands, oversampled), bass tuner, vocal tuner, orchestral amplifier, mid/side
`StereoTuner`, de-harsh band, AutoEq tunings, headroom + Automatic Gain Protection. New DSP needed:

- **Masking-aware dynamic EQ**: Bark-band masking estimate; lift bands with low audibility margin, trim
  maskers, all time-varying and bounded. Plus automatic **resonance suppression** (narrow, adaptive).
- **Intelligibility lift** for vocals/detail, gated by the vocal-presence estimate.
- **Noise-aware lift**: raise the bands that keep detail audible over ambient noise (works on Engine A).
- **Lossy-artefact softening**: start deterministic (de-ring, gentle harmonic restoration). Honest limit:
  nothing restores information a lossy file discarded; copy must never claim it.
- **Loudness compensation** tied to system volume (equal-loudness contours).

### 2.4 Verify
Guardrails live in one `Guardrails` module that every Decide output passes through: gain/Q limits per
band, total emphasis budget, headroom check, loudness-match trim, slew-rate limiting, panic bypass.

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

Goal: quietly learn which tracks, and which moments within them, a listener returns to, and give those
a little more care (e.g. a per-track profile, a gentle clarity lift on a replayed chorus).

- **Signals**: track identity from MediaSession metadata (title/artist/duration) → stored only as a
  salted local hash; replay count, completion, repeated seek-backs to the same position range →
  "most replayed segments" via playback-position history. Engine B may add an on-device audio
  fingerprint to identify tracks when metadata is missing.
- **Permission**: reading other apps' media sessions needs notification-listener access. That is
  sensitive: **explicit opt-in, plain-language explanation, off by default, one-tap wipe**.
- **Privacy rules**: all on-device; no upload; no raw titles stored unless the user enables
  "show my favourites"; documented in `docs/PRIVACY.md` before shipping.
- **Behaviour limits**: it may only *shape bounded Svaramanas parameters for that track/segment*, never
  create new loudness or break guardrails. Per-track settings are suggestions the user can see and edit.
- **Honesty**: a "favourite" is inferred; the UI must say "I noticed you replay this" and allow
  correction. No claim of improved sound until A/B evidence exists.

## 6. Source and app profiles

`AppProfile` (shipped as a versioned JSON in the APK, updated through Play releases, built from our own
test results, no telemetry): per package, capture allowed/blocked, normalisation/ReplayGain default,
own-EQ presence, exclusive/bit-perfect USB mode, typical codec. Used for the **player coach**:

- Player has its own EQ/effects → warn about double processing, suggest turning it off.
- Exclusive USB / bit-perfect mode (Neutron, HiBy, Onkyo) → Svan cannot touch it; say so and how to fix.
- Normalisation on → explain interaction with Svaramanas's loudness match.

Initial list: YT Music, Spotify, Apple Music, Amazon Music, Neutron, HiBy Music, ONKYO HF Player,
Poweramp, plus generic local players.

## 7. Test plan

**CI (emulator/unit, deterministic)**
- `SourceAnalyzer`: synthetic signals with known flaws (lowpass at 16/19 kHz, clipping, mono-in-stereo,
  boomy 200 Hz bump, bright 4 kHz) → detected within tolerance.
- Policy: for every feel × category combination, outputs within bounds; loudness-matched ±0.5 dB;
  zero clipping; headroom preserved; bypass restores the original level.
- Dynamic EQ / resonance suppression: measured reduction on synthetic masker/target pairs.
- Engine A static-smart paths measured with the existing e2e harness.

**Real hardware — the LG V60 ThinQ (arm64, wired DAC) and the user's main phone**
- **Test Pilot** mode in the app: one tap probes each installed player and exports a report (capture
  allowed, which engine took it, double-audio check, measured level change, route, errors).
  Reads routing facts only — never account data.
- Matrix per player (YT Music, Neutron, Apple Music, Spotify free tier, Amazon Music free tier, …):
  capture allowed/blocked · single processed copy · wired and Bluetooth · 30 min with screen off ·
  battery drain · player-side effects/exclusive mode.
- Streaming apps with DRM/account checks are not tested in CI emulators.

**Human listening** — the only evidence of "sounds better": in-app blind A/B (loudness-matched,
randomised) with results kept locally; a small panel study before any quality claim is made public.

## 8. Build order

1. `SourceAnalyzer` in `core/` (+ tests): cutoff, loudness, DR, clipping, width, balance.
2. `Guardrails` + rule-based Svaramanas policy (+ the full combination test).
3. Test Pilot report and `AppProfile` table; run on the V60.
4. Bubble, dialog, Quick Settings tile, notification fallback (Compose UI, gold design system).
5. Masking-aware dynamic EQ, resonance suppression, noise-aware lift, loudness compensation.
6. Hearing test and headphone auto-ID → personal profile.
7. Track memory (opt-in) and per-track/segment profiles.
8. Blind A/B tool; then, if justified, small ML controllers.

## 9. Constraints and open questions

- No root, no Shizuku dependency for the default path (Shizuku stays an optional extra).
- Play: MediaProjection + foreground-service disclosure, overlay and notification-listener
  justification, privacy policy covering mic/media-session use.
- Unknown until probed on the V60: which of Apple Music / Amazon Music / HiBy / Onkyo allow capture.
- App licence still undecided (ask the user).
- Name/brand: always "Svaramanas" (never "SvanMind"); gold design system, no new hues.
