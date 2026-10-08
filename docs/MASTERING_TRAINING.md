# Teaching Svaresa and Svaramanas to master (roadmap + the tools in this repo)

Goal: Svaresa decides like a very good mastering engineer, in real time, on the phone, for *this* listener, and it
can show evidence for each decision. "Training" here means three things, not one: encode the craft, learn the
targets from well-mastered music, learn the listener. A large neural net deciding live on the audio thread is
explicitly not the plan.

## Phase 1: ears (Svaramanas measures)  ✅ started
Already on device: loudness, PLR, clipping, stereo correlation, lossy ceiling, third-octave balance, mud / boom /
harsh / air. This change adds the offline equivalents plus the grounding measures (spine, spikiness) in
`tools/mastering/features.py`, which mirrors the on-device definitions so offline targets and live readings are
comparable.

## Phase 2: reference corpus (what "well mastered" looks like)  ✅ tooling done, corpus is yours to supply
```
corpus/<genre>/*.wav|flac       reference tier: well-mastered, licensed
corpus/_weak/<genre>/*.wav      weak tier: poorly mastered examples, to measure the gap
python tools/mastering/analyze_corpus.py corpus features.jsonl
python tools/mastering/build_targets.py features.jsonl targets.json
```
`targets.json` holds, per genre (and `all`), the median and inter-quartile range of each scalar feature plus a
30-band shape curve relative to the track's own tilt (a *shape*, so loud and quiet masters compare fairly). Genres
with fewer than 20 tracks fall back to `all`; nothing is invented from thin data. Only features are stored, never
audio, never file paths. FLAC needs `pip install soundfile` or an ffmpeg conversion to WAV.

Where to find music (verified from each project's page on 8 Oct 2026; details in
[RESEARCH_GROUNDED_SOUND.md](RESEARCH_GROUNDED_SOUND.md) section 9):
* **FMA, Free Music Archive**: 106,574 tracks, 161 genres. Metadata CC BY 4.0, audio under the artist's own licence,
  "meant for research purposes". Check each track's licence.
* **MTG-Jamendo**: 55,525 annotated tracks. "Solely for non-commercial research and academic use" unless Jamendo
  authorises otherwise. Not usable for a commercial product as is.
* **MUSDB18 / MUSDB18-HQ**: 150 tracks with stems. Access on request, academic purposes only.
* **MedleyDB, Slakh, MoisesDB**: terms not verified; check before use.
* **Your own purchased FLAC library**: analysed locally, features only. Often the best "reference" source because
  you know which albums are well mastered. This is the practical route for a closed-source app.
* **Published aggregate curves** (e.g. Pestana et al. 2013 for 772 number-one singles; Tamer 2021 for streaming
  playlists) as priors that need no audio at all.

Public sets are *not* automatically well mastered. Choose the reference tier by ear and by dynamics (PLR, clipping).
This is not legal advice; confirm licences before any commercial use.

## Phase 3: explicit policy  (next)
Replace remaining ad-hoc thresholds in `svaramanas.cpp` (tilt target -2.5 dB/oct, harshness thresholds, grounding
baselines) with values read from `targets.json`: "distance from this genre's healthy band" instead of one global
number. Ship the file as an app asset; keep the C++ defaults as the fallback. Every threshold change needs a
measured test, as for all DSP here.

## Phase 4: learned mastering decisions (optional, offline training, tiny on-device model)
Precedent: Mimilakis 2016 and Martínez Ramírez 2021 (parameter prediction, listening tests comparable to professional
or commercial mastering), Steinmetz 2022 (self-supervised style transfer), SonicMaster 2025 (degrade-and-restore with
19 simulated defects). All are offline; none shows beating good human masters.

Degrade-and-restore: take well-mastered tracks, apply randomised "bad mastering" (tilt, boom, mud, harshness,
over-limiting, widened highs), and train a small model to predict the *parameters* that undo it (EQ band gains,
grounding depth, tuner values), not audio. Literature to follow: differentiable-processing research on automatic
mixing / mastering and audio-effects style transfer (Steinmetz et al., DeepAFx-ST, music mastering style
transfer). A few real mastering engineers' premaster -> master pairs would be the ground truth worth paying for.
Distil to a model of a few thousand weights; run it at the plan rate (seconds), never per sample. The result must
still pass every guardrail and bound already tested, so a bad prediction cannot hurt.

## Phase 5: the listener (this is where it beats a studio)
Evidence: an EQ preference can be learned from about 25 ratings (Sabin & Pardo 2008); preferences cluster into
segments (Olive 2019). This has stronger support than a fixed genre voicing, so it should come before Phase 4.
No engineer knows your headphones, your volume tonight, or your ears. Use the blind-listening A/B and dial
nudges as preference signals: Bayesian optimisation or a bandit over the three or four depths that matter
(grounding restraint, body, tilt, vocal presence), with strong priors from `targets.json` and hard bounds.
`Your taste` in the app is the home for it.

## Evidence rules
* Every threshold has a test, every claim has a number or a blind-listening result (AGENTS.md rule 2).
* Matched-loudness comparisons only. Louder is never a win.
* "Never worse than the master" is the bar for shipping a behaviour on by default; "better" is demonstrated per track
  class in Lab, not asserted.
