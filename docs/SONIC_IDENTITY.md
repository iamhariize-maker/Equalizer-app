# Svan sonic identity: "Grounded"

Owner brief, 8 October 2026. Read with [RESEARCH_GROUNDED_SOUND.md](RESEARCH_GROUNDED_SOUND.md) (the evidence) and
[OWNER_REFERENCE_TASTE.md](OWNER_REFERENCE_TASTE.md) (the target taste).

## The brief
High-resolution playback exposes weak masters. The top end gets airy and spiky, the transients stand taller than the
music under them, and the result feels thin and nervous instead of present. Well-mastered tracks do not have this
problem. Svan should make every track feel **organic and grounded**: the voice sounds like a real person sitting in the
room, the guitar and bass have a spine, the whole spectrum feels a little analog and earthy. This is **not** a request
for dull or narrow sound: transients, atmosphere, wide studio stereo and binaural effects are loved, and only sound good
when the music has body under them.

**Owner decision (8 Oct 2026):** Svan should have a *clearly audible* house sound with an organic, analog tone, without
compromising quality. "No cheap" means: no gritty distortion, no dulling, no loudness tricks, no stereo narrowing, no
claim that is not measured.

## Owner's targets (8 October 2026, second answer)
* **Bass** mastered like A. R. Rahman and Massive Attack: deep, weighty and clean. Not mid-bass boom.
* **Transients and atmosphere** like Wilco: natural attacks, audible room and space.
* Listening is on bass-heavy Bluetooth earbuds (realme Buds Air 8), mostly away from a computer.

## How it is built (third pass, reviewed and re-planned)
The second pass put a fixed bell at 170 Hz. That is mid-bass, the wrong lever for "deep and clean" on earbuds that
are already heavy there, and it ignored the output route. The third pass replaces it with parts that each follow
the evidence (linear shaping first, research note sections 2 and 6) and the owner's targets:

| # | Part | What it does | Target it serves |
|---|---|---|---|
| 1 | **Foundation** | 65 Hz low shelf that lifts only what the track *lacks* against the target bass-to-mids balance (healthy balance + 2 dB, or the learned taste), max +3 dB; none if a boom is measured, none on the phone speaker, halved on crushed masters | Rahman / Massive Attack depth without boom |
| 2 | **Body** | +0.75 dB bell at 180 Hz (voice chest, guitar body), backing off with measured mud/boom | warm, present voice |
| 3 | **Softness** | -0.8 dB high shelf at 8.5 kHz, deepening by up to 2 dB with measured sharpness above the target | the airy, spiky hi-res complaint |
| 4 | **Punch** | bass-envelope punch (the existing tested BassShaper) on limited masters: 0 at PLR >= 10 dB, rising to 0.12 at PLR <= 6 dB | "hi-res versions sound less dynamic"; Wilco-like attack |
| 5 | **Atmosphere** | side ambience (StereoTuner space) when a mix is narrower than the target width, up to +1.2 dB side level; never on mono files or system effects | Wilco-like space |
| 6 | **Grounding** | odd-order body saturation with a level knee (baseline 0.7) and top-band spike restraint (baseline 0.25) | analog weight, calmer top |
| 7 | **Your sound** | "Learn this sound": Svaramanas measures a reference track you love and the targets of parts 1, 3, 5 and the tilt correction come from your references instead of the house values | the original vision: train Svaresa on the masters you love |

### Measured response of the voicing (EQ parts only, after loudness matching, 48 kHz)
| Track as heard | 30 Hz | 65 Hz | 180 Hz | 1 kHz | 8.5 kHz | 14 kHz | punch | space |
|---|---|---|---|---|---|---|---|---|
| healthy balance | +1.1 | +0.3 | 0.0 | -0.8 | -1.2 | -1.6 | 0 | 0 |
| thin bass | +2.0 | +0.7 | -0.1 | -0.9 | -1.3 | -1.7 | 0 | 0 |
| bass-heavy (Metro-like) | -0.2 | -0.1 | +0.6 | -0.2 | -0.6 | -1.0 | 0 | 0 |
| airy / bright master | +3.2 | +1.9 | +1.1 | +0.3 | -0.9 | -2.0 | 0 | 0 |
| limited and narrow | +0.6 | +0.2 | 0.0 | -0.4 | -0.8 | -1.2 | 0.12 | 0.20 |
| phone speaker | -0.2 | -0.1 | +0.5 | -0.2 | -0.6 | -1.0 | 0 | 0 |

Read the shape, not the absolute level (the preamp keeps loudness equal): on a healthy track the bass rises about
2 dB against the mids and the top above 8 kHz eases about 0.5 to 1 dB; an airy master gets the strongest grounding;
a track that already has deep bass is left alone.

### Grounding behaviour (from the tests; full depth = 1.0)
* Body: 3rd harmonic -63 dBc at -30 dBFS, -44 at -20, -32 at -12, -31 at -6 (the knee stops it climbing); no 2nd
  harmonic; fundamental compression at most 1 dB. The baseline 0.7 lowers these by about 3 dB.
* Restraint: a 6 kHz burst 30 dB over its bed is eased by 3.1 dB, never more than 4 dB, recovering within 100 ms.

### "Learn this sound" (Your sound)
* In the Svaramanas panel, Svaresa mode, with Hi-Fi on and a capturable player: play a reference track for at least
  20 seconds and tap **Learn this sound**. Repeat for several references (Rahman, Massive Attack, Wilco...).
* What is kept: overall tilt, bass-to-mids balance, relative sharpness, side-to-mid width and PLR, as a running mean
  (each new reference moves it by at least 1/13). Lossy references do not teach sharpness. Nothing else is stored,
  and it never leaves the phone.
* What it changes: the foundation target, the softness reference, the atmosphere width target and the tilt target
  (bounded to -4..-1 dB/oct). Every hard bound above still applies.
* **Use house voicing** forgets it.

## What "grounded" means in measurable terms
| Term | Quantity | Reading |
|---|---|---|
| **Fullness / spine** | `spineDb` (100 Hz-1 kHz power vs >3.5 kHz power) | higher = more body relative to the top |
| **Sharpness** | `relativeSharpness()` (DIN 45692-style, ratio) | higher = airier / brighter |
| **Spikiness** | `hfSpikeFrac`, `hfSpikeDb` | spiky top end |
| **Dynamics** | `plrDb`, `drTT` | low = more limited |
| **Space** | `correlation`, `sideToMidDb` | must be preserved |

## Honest state
* Tested: all bounds above, bit-exact bypass, stereo linking, ramping, plan scaling, sanitizer-clean core.
* **Not established:** that you will prefer it. The depths and constants are a first voicing from the owner's
  description and the literature. Qualification on the realme Buds Air 8 and the LG V60 is pending.
* Known interaction: the realme Buds Air 8 are reported to tune bass-heavy by default and Svan's headphone-correction
  index has no entry for them (it has the Air 3, and the matcher deliberately refuses to match 3 for 8). The
  foundation only lifts below about 100 Hz and only what a track lacks, which is safer than a mid-bass lift on such
  earbuds; if it is still too much, lower Svaresa strength or teach it your references.
* A closed-form claim is not a hearing test. Treat every number here as engineering evidence, not a promise.

## Listening protocol (before enabling for everyone)
1. 12 tracks: 6 well mastered from the owner's reference list, 6 that currently sound airy or fatiguing.
2. Lab blind listening at matched loudness, randomised order, a hidden reference and an anchor (e.g. a version with the
   voicing at 3x depth), at least 3 trials per track.
3. Record preferred / equal / worse and what changed (voice presence, rhythm weight, fatigue, harshness).
4. Ship by default only if no well-mastered track is judged worse and the others are preferred or equal. If a part
   fails, switch it off: parts 4 and 5 first, then 2, then 1.
5. Feed the results into the taste learner so depths adapt per listener.
