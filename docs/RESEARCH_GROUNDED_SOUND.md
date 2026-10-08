# Research: what makes music "grounded", and what Svan should (not) build

Written 8 October 2026, before any further build. Method: peer-reviewed literature search (SciSpace, Consensus),
web search, and direct reading of the dataset licence pages. **Limits:** for most papers I read abstracts and
summaries, not full texts. Evidence strength is labelled **[strong]** (replicated, or a meta-analysis / large
listening test), **[moderate]** (one controlled study), **[weak]** (small study, different material, or an
extrapolation) or **[anecdotal]** (forums, opinion). Engineering inferences are marked *inference*.

## Bottom line
1. **The hi-res format is not the likely cause of the "too airy, too spiky" sound.** Controlled evidence says format
   differences are small, and the plausible causes are a different master, the reconstruction filter or DAC chain,
   unmatched loudness, and expectation. Diagnose the specific tracks before building a fix.
2. **"Body" is mostly a linear tonal-balance matter** (bass and lower-mid strength, "fullness"), not harmonic
   distortion. The first lever should be a measured low-mid balance, personalised per listener.
3. **The evidence for adding "analog" harmonic saturation is weak, and partly negative.** On average, added
   distortion lowers pleasantness, and asymmetric (even-harmonic) distortion is worse than symmetric. The
   harmonic-body stage built in the first pass should not be on by default.
4. **Personalisation has better evidence than any fixed "house sound".** Preferences differ systematically between
   listeners, and a person's EQ preference can be learned from about 25 ratings.
5. **Automatic mastering by machine learning is real, but it is offline and heavy.** The usable idea for Svan is
   predicting a few bounded parameters, not generating audio.
6. **The public datasets are research-only.** They cannot simply feed a closed-source product.

---

## 1. Why hi-res can sound airy or spiky

| Finding | Source | Strength |
|---|---|---|
| Meta-analysis of 18 experiments (400+ listeners, 12,500+ trials): a small but statistically significant ability to tell hi-res from CD quality, much larger after extensive training | Reiss, *JAES* 2016, [aes.org/e-lib/browse.cfm?elib=18296](https://www.aes.org/e-lib/browse.cfm?elib=18296) | strong, effect small |
| Double-blind tests, a year, professional and audiophile systems: a 16-bit/44.1 kHz bottleneck inserted into hi-res playback was undetectable at normal-to-loud levels | Meyer & Moran, *JAES* 2007, [elib=14195](https://www.aes.org/e-lib/browse.cfm?elib=14195) | strong |
| High-frequency intermodulation from ultrasonic content: findable and relevant to some threshold experiments, "not pertinent to playback of current high-resolution recordings" | Stuart, Hollinshead, Capp, *JAES* 2019, [elib=20459](https://www.aes.org/e-lib/browse.cfm?elib=20459) | moderate |
| Current view: beyond dynamic range, the most likely technical differentiator between digital formats is the **filter chains** in sampling and reconstruction | Melchior, *JAES* 2019, [elib=20455](https://www.aes.org/e-lib/browse.cfm?elib=20455) | moderate (review) |
| Hi-res and CD versions are sometimes handled by different mastering engineers, and a later hi-res reissue can be more compressed than the original CD | forum posts (stereonet, Volumio) | anecdotal, but testable |
| Hi-res streaming tiers differ by sample rate and bit depth (e.g. Amazon HD 16-bit/44.1 kHz, Ultra HD 24-bit up to 192 kHz); nothing in the service pages says the master differs | Sonarworks blog, Amazon help page | factual, not evidence about sound |

*Inference:* if a hi-res stream sounds worse on the owner's phone, the likely explanations are (a) it is a different
master of the same album, (b) the Android audio path or DAC resamples or filters differently from the path used for
the CD version, (c) the levels were not matched, or (d) both listening conditions are not blind. None of these is
fixed by processing that assumes "hi-res is harsh". They are diagnosable.

**Recommended first step (cheap, decisive):** for 3 to 5 albums where the owner hears the problem, run
`tools/mastering/features.py` on the CD-quality and hi-res versions from the owner's own FLAC library. If the
features (PLR, tilt, spine, top-band spikiness) differ, it is a master difference. If they match, look at the audio
path and the DAC.

## 2. What "grounded", "present", "organic" mean perceptually

* Multidimensional studies of reproduced sound repeatedly find dimensions such as **clearness, sharpness/hardness vs
  softness, brightness vs darkness, fullness vs thinness, feeling of space, nearness, and disturbing sounds**
  (Gabrielsson & Sjögren, *JASA* 1979, [doi:10.1121/1.382579](https://doi.org/10.1121/1.382579)). [strong, classic]
* Polyphonic music timbre ratings reduce to three factors, **Activity, Brightness, Fullness** (Alluri & Toiviainen,
  *Music Perception* 2010, [doi:10.1525/MP.2010.27.3.223](https://doi.org/10.1525/MP.2010.27.3.223)). [moderate]
* Headphone sounds differ along two dominant dimensions: **relative bass strength and relative lower-midrange
  strength** (r = 0.89 to 0.97 with proposed metrics) (Volk et al., *JASA* 2016,
  [doi:10.1121/1.4967225](https://doi.org/10.1121/1.4967225)). [moderate]
* Sensory pleasantness behaves as a composite: loudness, roughness and **sharpness** count against it,
  tonalness counts for it (Vianna, *Timbre Perception*, 2023,
  [doi:10.1007/978-3-031-25566-3_6](https://doi.org/10.1007/978-3-031-25566-3_6)). [moderate, review]
* Listeners identify timbre primarily from **spectral envelope shape**; only extreme attack differences mattered
  (Hall & Beauchamp 2009, *Canadian Acoustics*). [moderate]
* "Punch" depends on onset time and frequency components across octave bands (Fenton & Lee, *JAES* 2015). [moderate]

**What this means for the owner's description.** "Spine / body / earthy" maps to the **fullness-thinness** and
**bass + lower-mid strength** dimensions. "Too airy / spiky" maps to **sharpness** (and, if rough, roughness).
"A real person sitting there" maps to **nearness**. All of these have established measures, so we can define the
target with standard metrics rather than invented ones.

**Standard measures available** (ISO 532-1 loudness, DIN 45692 sharpness, Daniel & Weber or ECMA-418-2 roughness,
Osses fluctuation strength, Aures tonality) are implemented in the open-source SQAT MATLAB toolbox
(Greco et al., *Inter-Noise* 2023, [doi:10.3397/in_2023_1075](https://doi.org/10.3397/in_2023_1075)). Sharpness is
cheap once specific loudness is known. *Inference:* a third-octave version could run on the phone from the
analyser's existing band levels, with SQAT as the offline reference to calibrate against.

## 3. "Analog warmth" through harmonic distortion: weak and partly negative

| Finding | Source | Strength |
|---|---|---|
| Synthetic triads: perceived pleasantness **decreased with distortion**, more for **asymmetric** than symmetric distortion; some symmetric-distorted major triads in low or wide positions were still rated pleasant | Baltes, Chemnitz, Lange, *JASA* 2023, [doi:10.1121/10.0020667](https://doi.org/10.1121/10.0020667) | moderate, synthetic sounds |
| Heavy guitar distortion: tolerance and pleasantness depend on chord structure, genre habit, and the listener being a guitarist | Herbst, *Music Perception* 2019, [doi:10.1525/MP.2019.36.4.335](https://doi.org/10.1525/MP.2019.36.4.335) | moderate, guitar only |
| Slight transient intermodulation was heard as a change of tonal character rather than as distortion; some listeners preferred the slightly distorted sound; audibility varied widely with music, medium and person | Petri-Larmi et al., *IEEE TASSP* 1980, [doi:10.1109/TASSP.1980.1163343](https://doi.org/10.1109/TASSP.1980.1163343) | weak (old), but a real preference signal |
| Added harmonic overtones **lowered** perceived brightness relative to what "sharpness" predicts | Tsumoto et al., *JASA* 2016, [doi:10.1121/1.4970811](https://doi.org/10.1121/1.4970811) | weak, distorted guitar |
| Tube preference in guitar amps may stem from familiarity with their sound | Pakarinen & Yeh, *Computer Music Journal* 2009 | weak (a hypothesis in a review) |
| Distortion audibility in headphones, at equal frequency response, correlates with preference | Temme & Olive, *JAES* 2014 | moderate, trained listeners |

**Reading:** there is no solid, general evidence that adding even-order harmonics to arbitrary music makes it
sound better. There is evidence that distortion usually costs pleasantness and that even-order is the worse kind.
The one encouraging thread is that slight, symmetric distortion can read as a tonal change that some listeners
like, and that added harmonics can soften perceived brightness. That is a hypothesis to A/B, not a feature to ship.

## 4. Dynamics, compression and loudness

* Loudness-matched comparisons: a small amount of compression can be preferred; the highest levels are generally
  detrimental to quality whether or not loudness is equalised (Croghan, Arehart, Kates, *JASA* 2012,
  [doi:10.1121/1.4730881](https://doi.org/10.1121/1.4730881)). [moderate]
* Normal-hearing listeners showed no preference for less compressed popular music and low response consistency
  (Hjortkjaer & Walther-Hansen, *JAES* 2014, [doi:10.17743/jaes.2014.0003](https://doi.org/10.17743/jaes.2014.0003)).
  [moderate]
* 130 listeners: moderate compression applied before summation was preferred over limiting on the full mix;
  intermodulation from heavy master-bus limiting is the proposed reason (Campbell, Paterson, van der Linde, *JAES*
  2017, [doi:10.17743/JAES.2017.0019](https://doi.org/10.17743/JAES.2017.0019)). [moderate]
* Untrained listeners generally could not perceive hyper-compression artefacts; mastering engineers could (a 2025
  thesis, [doi:10.34961/3209](https://doi.org/10.34961/3209)). [weak, thesis]
* The detectability of compression tracks how much the modulation spectrum changes (Sabin et al., *JASA* 2013,
  [doi:10.1121/1.4816410](https://doi.org/10.1121/1.4816410)). [moderate]

**Implications.** (a) Svan cannot undo a limited master, and nobody has shown a reliable way to. Do not promise it.
(b) A downward-only restrainer on one narrow band changes the modulation depth of that band only; by this evidence
it is a low-risk, low-detectability tool, but there is **no evidence yet that it improves preference**. (c) Never
compare A/B at unmatched loudness; Svan already enforces this.

## 5. Listeners differ, and preferences can be learned

* Headphone preference clusters into **three listener segments** with demographic and acoustic correlates (Olive,
  Welti, Khonsariopour, *JASA* 2019, [doi:10.1121/1.5136749](https://doi.org/10.1121/1.5136749)); a single target
  curve is preferred by the majority, not everyone. [moderate]
* Preferred spectral balance differed across listeners from four countries (Kim et al., *JASA* 2016). [weak]
* Listeners have **consistent but individual** timbre preferences, and prefer the mix they *think* is theirs even
  when they cannot identify it, so blind testing matters (Dobrowohl et al., *Applied Sciences* 2019). [moderate]
* **An EQ descriptor curve ("warm") can be learned in about 25 listener ratings** (Sabin & Pardo, *JAES* 2008,
  [elib=14733](https://www.aes.org/e-lib/browse.cfm?elib=14733)). [moderate]
* Per-band paired-comparison Bayesian personalisation of hearing-aid gain: personalised settings were preferred about
  six times as often as the standard prescription in 8 hearing-impaired subjects (Ni, Lobariñas, Kehtarnavaz, *IEEE
  Access* 2024, [doi:10.1109/access.2024.3441762](https://doi.org/10.1109/access.2024.3441762)). [weak, n = 8]

**Implication.** A learned, bounded, per-listener preference model (Svan's "Your taste") has stronger support than a
fixed genre-based house sound. The fixed voicing should be a modest prior.

## 6. Targets: what well-made music looks like

* 772 US/UK number-one singles (1950 to 2010): a consistent leaning toward a **target equalisation curve**, with
  per-decade and per-genre variation (Pestana et al., *JAES* 2013,
  [elib=17010](https://www.aes.org/e-lib/browse.cfm?elib=17010)). The authors publish detailed data on a companion
  site. [moderate]
* Recent streaming playlists show an **increased slope in the presence band** versus earlier characterisations; the
  author recommends presence-band optimisation at playback (Tamer, *JAES* 2021,
  [doi:10.17743/jaes.2021.0006](https://doi.org/10.17743/jaes.2021.0006)). This is the same idea as Svan's. [moderate]
* Bass level in popular music has risen over decades, mostly in the lowest bands (Hove et al., *JASA* 2019). [moderate]

**Implication.** Priors for "healthy balance" can come from published aggregate curves plus the owner's own library.
They should be era- and genre-conditioned and treated as soft targets.

## 7. "A real person is there": nearness

* Auditory distance is judged by combining **intensity** and **direct-to-reverberant energy ratio**, with weights that
  change by source type (Zahorik, *JASA* 2002, [doi:10.1121/1.1458027](https://doi.org/10.1121/1.1458027)). [strong]
* Microphone proximity adds low-frequency boost as a source gets closer (Clifford & Reiss, *JAES* 2011). [strong,
  physics]
* A talk abstract by Griesinger argues that harmonic **phase** relationships above about 1 kHz underlie the sense of
  proximity and that sound systems and reflections that scramble phase degrade it. [weak, single abstract]

*Inference (hypotheses to test, not facts):* a voice sounds near when it has low-mid weight on the centre, a clear
direct sound with limited ambience, and intact upper-harmonic phase. Therefore (a) body on the centre channel helps,
(b) boosting side-channel ambience trades against nearness, and (c) processing that shifts phase in 1 to 5 kHz on the
vocal should be avoided. The corrected Grounding stage keeps its top-band path in phase with the dry signal for this
reason.

## 8. Automatic mastering by machine learning: what is proven

| Work | What it did | Evaluation | Caveat |
|---|---|---|---|
| Mimilakis et al., *JAES* 2016 | DNN predicts the gain coefficients for dynamic range compression | listening test with producers and mastering engineers: on average equivalent to professionally mastered content, better than relevant commercial software | offline; dynamics only |
| Martínez Ramírez et al., *ICASSP* 2021, [arXiv:2105.04752](https://arxiv.org/abs/2105.04752) | encoder predicts parameters of stateful black-box effects; applied to automatic mastering | subjective test: comparable to a specialised commercial mastering product | offline; heavy training |
| Steinmetz, Bryan, Reiss, *JAES* 2022, [arXiv:2207.08759](https://arxiv.org/abs/2207.08759) | self-supervised style transfer by differentiable effects; no paired data; interpretable parameters | listening tests | style transfer, not "fix a bad master" |
| Melechovský et al., SonicMaster 2025, [arXiv:2508.03448](https://arxiv.org/abs/2508.03448) | generative restore-and-master model trained on tracks degraded with 19 simulated defects in 5 groups (EQ, dynamics, reverb, amplitude, stereo) | listeners preferred outputs over the degraded originals | not real-time; compared with degraded input, not with good masters |
| Naiduchowski et al., 2018 | reference-based mastering from roll-off, band energy, amplitude histogram, tempo, envelope timing, LUFS | online listening test | small study |

**Implications.** (1) Degrade-and-restore is an established training recipe. (2) **Predict bounded parameters, don't
generate audio**: interpretable, safe, cheap on a phone. (3) None of this runs live on a phone today, which
supports "train offline, ship a tiny parameter predictor". (4) Beating good human masters is not demonstrated
anywhere; the reported wins are "equivalent", "comparable" or "preferred over the degraded input".

## 9. Datasets and licences (read from the primary pages)

| Dataset | Size | Licence as stated | Consequence for Svan |
|---|---|---|---|
| FMA ([github.com/mdeff/fma](https://github.com/mdeff/fma)) | 106,574 tracks, 161 genres (subsets 8k/25k/106k) | metadata CC BY 4.0; **audio under the licence the artist chose**; "meant for research purposes"; commercial use not addressed | per-track licence check needed; research use only until cleared |
| MTG-Jamendo ([github.com/MTG/mtg-jamendo-dataset](https://github.com/MTG/mtg-jamendo-dataset)) | 55,525 annotated tracks, 195 tags | metadata CC BY-NC-SA 4.0; audio under per-track CC licences; "solely for non-commercial research and academic use", otherwise written authorisation from Jamendo | not usable for a commercial product without permission |
| MUSDB18 / -HQ ([sigsep.github.io/datasets/musdb.html](https://sigsep.github.io/datasets/musdb.html)) | 150 tracks with stems | access must be requested, "academic purposes"; 46 MedleyDB tracks are CC BY-NC-SA 4.0 | academic only |

Slakh, MoisesDB and MedleyDB's own terms were **not** verified here. *Not legal advice.* The practical route for a
closed-source app: use the owner's own purchased library for private feature analysis (features only), published
aggregate curves, and only tracks whose individual licences are checked and permit the use.

## 10. What this changes in the build

| Item from the first pass | Verdict from the research | Action |
|---|---|---|
| Harmonic **body** stage on by default (baseline 0.25) | Not supported; even-order part is actively disfavoured | remove the even term; default **off**; keep an odd-only "Warmth" experiment behind a switch until A/B passes |
| HF transient **restraint** on by default (baseline 0.15) | Plausible and low risk, but no evidence of benefit | default off until A/B on real tracks; keep the corrected in-phase design |
| "Spikiness" as the measure | ad hoc | replace or cross-check with DIN 45692 sharpness and roughness |
| Fixed Svaresa "grounded" baseline | personalisation has better support | make depth a learned, bounded, per-listener value |
| Low-mid **fullness** | strongest perceptual support | add a measured fullness target (bass + lower-mid vs top) with linear EQ, using published priors |
| Dataset list | licences mostly research-only | corrected in `MASTERING_TRAINING.md` |
| A/B method | listener bias is real | matched loudness, randomised order, hidden reference and an anchor, several trials per track |

## 11. Open questions for the owner
1. Which 3 to 5 albums sound worst in hi-res, and do you have the CD-quality or standard version of the same album?
2. Does the airy, spiky sound also happen **without Svan** in Amazon Music HD, Apple Music and Neutron? (It should if
   it is a master or DAC effect.)
3. Which headphones, and over which output (USB DAC, Bluetooth LDAC, 3.5 mm, speaker)?
4. Is the aim "never worse than the master and fix airy ones", or a stronger, audible house voicing?

## 12. Source list
Primary sources are linked inline. Papers were found through SciSpace and Consensus; dataset licences were read
directly from each project's page. Anything marked anecdotal comes from forum posts and should be treated as a lead
to test, not a result.
