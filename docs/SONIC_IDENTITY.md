# Svan sonic identity — "Grounded" (owner brief, 8 October 2026)

## The owner's words, condensed
High-resolution playback exposes weak masters. The top end gets too airy, the transients stand taller than the
music under them, and the result feels thin and nervous instead of present. Well-mastered tracks do not have this
problem. Svan should make *every* track feel **organic and grounded**: the voice sounds like a real person sitting
in the room, the guitar and bass have a spine, the whole spectrum feels a little analog and earthy. This is **not**
a request for dull or narrow sound. The owner loves transients, atmosphere, wide studio stereo and binaural effects;
those only sound good when the music has body under them. Body first, so detail and space can fly without
overwhelming the spine of the song.

## What "grounded" means in measurable terms
Nothing here is a claim about taste. These are the quantities Svan can measure on any track, and the ones the
grounding stage moves. Definitions live in `tools/mastering/features.py` and `core/src/grounding.cpp`.

| Term | Quantity | Reading |
|---|---|---|
| **Spine** | `spineDb` = power in 100 Hz-1 kHz minus power above 3.5 kHz | Higher = more body relative to the top. Airy or thin masters score low. |
| **Spikiness** | `hfSpikeFrac`, `hfSpikeDb` = how often, and by how much, the >3.5 kHz band's fast level exceeds its own slow level by more than 5 dB | Spiky, nervous top end scores high. |
| **Tilt / air** | `tiltDbPerOct`, `airDb`, `harshDb` (analyser.h) | Bright or shrill balance. |
| **Space** | `correlation`, `sideToMidDb`, `lowSideToMidDb` | Must be preserved, never narrowed. |
| **Punch** | `plrDb`, crest of the body band | Must be preserved, never squashed. |

A track is **grounded** when its spine is healthy and its top-end spikes sit inside a band of the music's own
weight. The healthy range per genre is learned from well-mastered reference tracks, not guessed
(`docs/MASTERING_TRAINING.md`).

## How the signal path delivers it (core/include/eqcore/grounding.h)
Two small, bounded stages, plus what Svaresa already does.

| Owner's complaint | Mechanism | Hard bounds | Measured by |
|---|---|---|---|
| "Transients are too much, too airy" | **Restraint**: stereo-linked, downward-only restrainer on the >3.5 kHz band. It reacts to that band's *crest* only, with a soft ratio. Sustained air and ordinary transients pass untouched. The first ~0.5 ms of a spike passes, so the click stays and the overshoot is eased. | max 4 dB, 0.5 ms attack, 15 ms release, bypass is bit-exact | `grounding_restraint_*`, `grounding_gain_is_stereo_linked_*` |
| "Needs a body, a little analog" | **Body**: level-dependent low-order harmonics on the 100 Hz-1 kHz band (voice fundamentals, guitar, bass body, snare shell). Clean when quiet, weightier when louder, like a console or tape path. | about -33 dB THD at a -12 dBFS band level, about 0.5 dB fundamental compression, quiet-level THD below -75 dB | `grounding_body_*` |
| Overall airy / bright balance | Svaresa's existing measured tilt, harshness and air corrections (`svaramanas.cpp`) | unchanged limits | existing Svaresa tests |
| "Voice should be there" | Svaresa suggests grounding depth; the existing vocal tuner (`intimacy`, `warmth`, `smoothness`) remains the manual voice control | unchanged | existing tuner tests |

**What grounding never does:** it does not low-pass or roll off the top, it does not reduce stereo width, space or
binaural cues, it does not compress the body band, and it does not touch material that has no spikes (on a calm,
well-mastered track the restraint sits at its small baseline and the body at its quiet-level linear behaviour).
Gain protection and the true-peak limiter still run after it.

## How Svaresa sets the depth (svaramanas.cpp, Request.svaresaMode only)
* Baseline voicing, always on in Svaresa: body 0.25, restraint 0.15 (the owner-chosen signature).
* Raised by *measured* evidence only: restraint grows with `airDb` above +1, `harshDb` above +1 and tilt more than
  0.5 dB/oct brighter than the -2.5 target; body grows with the same tilt excess, capped at 0.7.
* Halved body on crushed masters (PLR below 8 dB or clipping): they are already dense.
* The Plan carries a note (`kNoteGrounded`) so the UI can say why. Guided (non-Svaresa) mode never grounds.
* Depth ramps over 20 ms in the engine; the plan itself moves slowly because the analyser averages over seconds.
* The blind-listening render includes grounding, so matched-loudness A/B tests judge the real chain.

## Honest state
* Measured and tested: bounds, linearity, harmonic levels, spike easing, stereo linking, bit-exact bypass, plan
  scaling, sanitizer-clean (core tests).
* **Not yet established: that listeners prefer it.** The depths and the 5 dB / 3.5 kHz / 100 Hz-1 kHz constants are
  a first voicing, set from the owner's description, and must be tuned by ear. Qualification is pending on the
  TECNO LH7n and LG V60 (`docs/PHONE_VALIDATION.md`).
* Engine A (system effects / DynamicsProcessing) cannot run this processor. Grounding is a capture-engine feature.

## Listening protocol (do this before enabling by default)
1. Pick 12 tracks: 6 well mastered (must be transparent: restraint average under 0.5 dB, no audible change at
   matched loudness) and 6 spiky hi-res masters (should improve).
2. Use Lab blind listening at matched loudness: original vs Svaresa with grounding, then Svaresa without.
3. Record, per track: preferred / equal / worse, and what changed (voice presence, rhythm weight, fatigue).
4. Ship by default only if no well-mastered track is judged worse and the spiky ones are preferred or equal.
5. Tune three numbers first if it fails: `kCrestThresholdDb`, `kMaxRestraintDb`, `kDrive`.
6. Feed the A/B results into the taste learner (`MASTERING_TRAINING.md`, phase 5) so the depth adapts to the listener.
