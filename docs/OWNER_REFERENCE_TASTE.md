# Owner reference taste and listening setup (8 October 2026)

## Reference artists (the target taste)
Well-mastered work the owner admires, in the owner's words, weakest last:
A. R. Rahman, Amit Trivedi, Wilco, Jeff Buckley ("Everybody Here Wants You"), Steven Wilson, Massive Attack, and
Metro Boomin (the weakest of the list).

These span film-score orchestration and layered production (Rahman, Trivedi), band recordings with an analog,
room-like feel (Wilco, Buckley), meticulously mixed and spatial modern production (Wilson, Massive Attack) and
heavy-bass trap (Metro Boomin). The intersection the owner is pointing at is: body and warmth in the voice, real
dynamics, depth, and a bass foundation that does not smother the top.

How this is used:
* They are the **reference tier** for building personal targets (`tools/mastering/analyze_corpus.py` on a folder of the
  owner's own files, features only). Nothing from the audio is stored or shared.
* The Metro Boomin material is a deliberate stress case: very strong low end and heavy limiting. The voicing must not
  push bass on top of that (the foundation lifts only what a track lacks and backs off with measured boom).
* Do not claim that any of this music "defines" a correct sound. It anchors the owner's preference, which is the point.

Owner's refinement (8 Oct 2026): **bass mastering like A. R. Rahman and Massive Attack; transients and atmosphere
like Wilco.** These drive the foundation, punch and atmosphere parts of the house voicing (SONIC_IDENTITY.md).

How to teach Svan these references with no PC and no uploads: in Svaresa mode, play one of these tracks with Hi-Fi on
and tap **Learn this sound** after 20 seconds or more. Do it for a handful of tracks from different artists. The phone
keeps only the measured balance (no audio), and Svaresa aims other tracks toward it.

## Listening setup
* **realme Buds Air 8** (in-ear, Bluetooth 5.4). Retailer pages list AAC, SBC and LDAC; one also lists LHDC 5.0. These
  are retailer claims and were not verified against realme's own specifications. A reviewer describes the default tuning
  as bass-heavy.
* Consequences of Bluetooth: the link is lossy, and which codec is actually used depends on the phone and settings. A
  "hi-res" stream therefore cannot arrive bit-perfect. It is resampled and re-encoded on the way, and any file-format
  difference between CD-quality and hi-res is largely moot. Differences the owner hears between versions are far more
  likely to be master differences, which can be tested (below).
* Svan's headphone-correction index has the Realme Buds Air 3, not the Air 8; this is intentional in the matcher.

## Owner's answers to the research questions
* Hi-res sounds worse than the standard version in Amazon Music HD, Apple Music and Neutron too, but less so.
  They "sound much less dynamic".
* Preferred outcome: a clearly audible house sound, organic and analog in tone, without compromising quality.

*Interpretation:* "less dynamic" in hi-res versions is a master property (more limiting), not something format or
Svan's processing causes. Svan can make a limited master feel more grounded, but it cannot restore peaks that were
clipped away; nobody has shown a reliable way to. Be wary of any feature that claims to.

## The diagnostic that settles "same master or not"
For one album you can hear the difference on, have both versions as files and run:
```
python tools/mastering/ab_compare.py standard_version.wav hires_version.wav
```
It aligns the two, matches loudness, and reports: a null-test residual (a very low residual means the same master), PLR
and DR (dynamics), tilt, spine and top-band spikiness, and a one-line verdict: SAME MASTER / DIFFERENT MASTERS /
SAME RECORDING, LIGHT PROCESSING DIFFERENCE. If the verdict is "same master", the difference you hear is the playback
chain (here the Bluetooth link), level or expectation. If it is "different masters", the numbers show how (more
limiting, brighter, etc.) and that is what Svaresa's voicing should be tuned against.

No PC? Upload the two files (30 to 90 s excerpts are enough) to the session and the comparison can be run there.

## Second reference list (8 Oct 2026, owner)
Daft Punk (*Random Access Memories*), Tame Impala (*Currents*), Beck ("Debra", "Blue Moon", "Paper Tiger"), The Marshall
Tucker Band ("Can't You See"), LCD Soundsystem, Nine Inch Nails ("Copy of A", "Closer", "Discipline"), Tool ("Fear
Inoculum", "7empest", "The Pot"), The Rolling Stones, remasters ("Monkey Man", "Gimme Shelter"), Lynyrd Skynyrd ("Call Me
the Breeze"), The Who ("Baba O'Riley", "Won't Get Fooled Again"). More to come.

What this adds to the picture: much more electronic and heavily produced work (Daft Punk, Tame Impala, LCD, NIN, Tool)
next to 1970s analog band recordings (Stones, Skynyrd, Marshall Tucker, The Who). The common thread is not one tonal
balance. It is **mastering that keeps the music's own character**: synthetic bass that is deep but controlled, wide
and phasey stereo that stays wide, aggressive material that is dense without sounding crushed, and old analog
recordings that keep their warmth and top-end grain.

### What Svan can and cannot do with a list of names
* It cannot learn from names, and nothing here is training data in the machine-learning sense. The app never uploads
  audio: "Learn this sound" measures features of what the owner plays on the phone, and only that. (Separately, the
  owner may choose to share excerpts with a working session for the same-master test above; the app is not involved.)
* Daft Punk's Atmos / 360 Reality Audio mixes are object-based. Svan only ever sees the two-channel stream the phone
  outputs, so it treats them like any stereo source. The wide, rendered image must survive, which is the existing
  "never narrow the stereo" rule.

### Proposed split (owner to confirm or change)
"Learn this sound" keeps a **running mean** (tilt, bass-to-mids, sharpness, width, PLR). Averaging records as different as
Marshall Tucker and Nine Inch Nails gives a target that matches neither, so only records whose *balance* the owner wants
Svan to move other music toward should be taught.

* **Teach** (balance to move toward): Rahman and Massive Attack (bass), Wilco (transients and space), plus *Random
  Access Memories* as the modern, dynamic, deep-low-end reference. Five to eight tracks, 20 s or more each, Hi-Fi on.
* **Evaluation only** (never teach, listen for damage): Tame Impala *Currents* (wide, phasey stereo: width and
  atmosphere must not collapse), Tool and NIN (dense, aggressive: grounding must not dull them or add grit), LCD
  Soundsystem (clean synthetic bass: the foundation must not boom), and the 1970s band recordings (Stones remasters,
  Skynyrd, Marshall Tucker, The Who: already warm, so softness must stay near zero where measured sharpness is low).
* Beck sits between the two groups ("Blue Moon" and "Paper Tiger" are close to the Wilco side, "Debra" is a studio-funk
  record). Treat them as evaluation tracks unless the owner wants them taught.

### Blind-listening set seeded from this list
For the protocol in SONIC_IDENTITY.md (12 tracks, 6 well mastered and 6 fatiguing), the owner's picks give these
well-mastered candidates: "Get Lucky" or "Giorgio by Moroder" (RAM), "Let It Happen" (Currents), "Blue Moon" (Beck),
"Gimme Shelter" (Stones remaster), "Baba O'Riley" and "Won't Get Fooled Again" (Who), "Fear Inoculum" (Tool), "Closer"
(NIN). Pick the six fatiguing tracks from whatever currently sounds airy or spiky on the Buds. The most useful result
is a **wrong-direction** note: any track where Svaresa made it worse.

### Risks to check first on this list
1. Dense, saturated mixes (Tool, NIN): the body saturation has a level knee, but it has not been judged on loud
   distorted guitars. If they sound smoothed or flattened, lower the grounding body depth first.
2. Wide, phasey mixes (*Currents*): atmosphere only ever adds side level on narrow mixes, so this should be untouched.
   If the image shrinks, that is a bug and should be reported with the track and time.
3. Analog-era records: the softness shelf should stay shallow where measured sharpness is below target. If they sound
   dull, that is the first thing to switch off.

## Vocal masters (10 Oct 2026, owner)
Melody Gardot, "Morning Sun"; Amit Trivedi, "Shauq"; A. R. Rahman, "Tere Paas Main" (female version); Hale, "Blue Sky".
The owner's criterion: the voice feels real and its emotional texture is almost tangible; raising `space` must not make it
digital, light or too airy. Used as negative controls for the shrill guard, `space` and the harshness policy; see
`BUILD_BRIEF_0.5.14.md` §7 and WP8. Not yet measured: no excerpts have been analysed.

## Bass and highs goals (10 Oct 2026, owner)
Bass: as much detail as possible, from bass-guitar picks to bass waves, tube-like and rubbery ported-box bounce, with
texture that feels three-dimensional. Highs: preserve detail and clarity but favour a pleasant analogue sound over raw
brutality, because unbalanced highs make listeners change track or lower the volume; winds (sax, trumpet) and moody
strings (violin) should be emotional, not loud. Work packages WP9 and WP10 in `BUILD_BRIEF_0.5.14.md`. Nothing here has
been measured on the owner's tracks.

