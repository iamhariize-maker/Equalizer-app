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
  push fullness on top of that (fullness already backs off with measured boom/mud).
* Do not claim that any of this music "defines" a correct sound. It anchors the owner's preference, which is the point.

How to get these measured without a PC: upload 30 to 90 second excerpts (or whole files) to the working session and the
features can be extracted there.

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
