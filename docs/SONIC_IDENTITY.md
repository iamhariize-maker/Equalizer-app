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

## How the research shaped it
The evidence (see the research note) says the audible body and softness should come mostly from **linear tonal
shaping** (fullness, softer top), which is strongly supported, with the harmonic "analog" layer kept **bounded and
odd-order only** (even-order was the less pleasant kind), because its effect on preference is not established. So the
house sound is built from five parts, in this order of importance:

| # | Part | What it does | Where | Evidence |
|---|---|---|---|---|
| 1 | **Fullness** | wide +1.5 dB bell at 170 Hz (Q 0.8); backs off linearly to 0 as measured boom/mud reach 4 dB; halved on crushed masters | `svaramanas.cpp` house voicing | strong (fullness / bass + lower-mid are the main perceptual dimensions) |
| 2 | **Softness** | -0.8 dB high shelf at 8.5 kHz, deepening by up to a further 2.0 dB as measured *sharpness* exceeds a healthy balance; zero for lossy streams ending below 12 kHz | same | moderate (sharpness is a standard psychoacoustic measure) |
| 3 | **Existing Svaresa corrections** | measured boom, mud, harshness, tilt, air | unchanged | existing tests |
| 4 | **Restraint** | stereo-linked, downward-only restrainer on the >3.5 kHz band; reacts to that band's crest only; max 4 dB; first ~0.5 ms of a spike passes, so the click stays and only the overshoot is eased | `grounding.cpp` | weak (low risk, benefit unproven) |
| 5 | **Body** | level-dependent odd-order saturation of 100 Hz-1 kHz with a level knee | `grounding.cpp` | weak (owner-chosen; A/B required) |

Parts 1 and 2 are the main audible character. Parts 4 and 5 are subtle, bounded refinements.

### Measured behaviour (from the tests; full depth = 1.0)
* Body (part 5), 300 Hz tone: 3rd harmonic -63 dBc at -30 dBFS, -44 dBc at -20 dBFS, -32 dBc at -12 dBFS, -31 dBc at
  -6 dBFS (the knee stops it climbing); fundamental compression 0.7 dB at -12 dBFS and 1.0 dB at -6 dBFS; **no 2nd
  harmonic** (even-order is off). The shipped baseline depth is 0.7, which lowers these harmonic levels by about 3 dB.
  The old plain-tanh version measured 18.6 dBc harmonics and 3.3 dB squash at -6 dBFS; the knee is what makes it
  musical rather than gritty.
* Restraint (part 4): a 6 kHz burst 30 dB over its bed is eased by 3.1 dB in energy over the burst, never more than
  4 dB, recovering within 100 ms; sustained air and 200 Hz content are unchanged within 0.1 dB.
* Softness (part 2) uses `relativeSharpness()`: a ratio to the sharpness of a healthy-balance spectrum (1.0 = healthy).
  It is monotonic in tilt (0.60 at -6 dB/oct, 1.00 at -2.5, 1.14 at -1.5, 1.71 at +2.0), independent of level, and
  lower for a 16 kHz lossy file (0.80). It is blind to a mid-range 2-5 kHz bump by design (sharpness is a high-frequency
  centroid); shrillness there is the existing harshness correction's job.
* Everything is loudness matched: the preamp offsets the predicted K-weighted change, so the voicing never wins by being
  louder.

### How Svaresa sets the depths
* Fullness and softness: the house values above, scaled by Svaresa strength, modified by the measured evidence.
* Grounding: baseline body 0.7 and restraint 0.25; restraint rises with measured air, harshness and bright tilt, and
  body rises with bright tilt (cap 1.0); body is halved on crushed masters. Guided (non-Svaresa) mode has none of it.
* The plan carries two notes for the UI (`kNoteVoicing`, `kNoteGrounded`) and the blind-listening render includes all of
  it, so A/B tests judge the real chain.
* Engine A (system effects / DynamicsProcessing) can apply the linear voicing (it is only band gains) but cannot run the
  Grounding processor; that is a capture-engine feature.

### The dials (one place each)
`kHouseFullnessDb`, `kHouseSoftnessDb`, `kSoftnessMaxDb`, `kSoftnessSlopeDb`, `kSharpnessDeadband`,
`kGroundingBaseBody`, `kGroundingBaseRestraint`, `kGroundingMaxBody` in `svaramanas.h`; `kDrive`, `kBodyKnee`,
`kCrestThresholdDb`, `kMaxRestraintDb` in `grounding.cpp`. Tune these by ear first.

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
  index has no entry for them (it has the Air 3, and the matcher deliberately refuses to match 3 for 8). Added fullness
  on top of an already bass-heavy earbud may be too much; the voicing scales with Svaresa strength, and a measured
  correction for the Air 8 would be the proper fix.
* A closed-form claim is not a hearing test. Treat every number here as engineering evidence, not a promise.

## Listening protocol (before enabling for everyone)
1. 12 tracks: 6 well mastered from the owner's reference list, 6 that currently sound airy or fatiguing.
2. Lab blind listening at matched loudness, randomised order, a hidden reference and an anchor (e.g. a version with the
   voicing at 3x depth), at least 3 trials per track.
3. Record preferred / equal / worse and what changed (voice presence, rhythm weight, fatigue, harshness).
4. Ship by default only if no well-mastered track is judged worse and the others are preferred or equal. If a part
   fails, switch it off: parts 4 and 5 first, then 2, then 1.
5. Feed the results into the taste learner so depths adapt per listener.
