# What Android's system-effects framework allows (and what the measurements say)

Status: source analysis plus a CI measurement lab. Every gain below is either **measured** (with the run that
measured it) or **predicted** (from the framework source, not yet measured). Nothing here is a phone result. The
CI emulator runs the AOSP reference implementation of these effects; a vendor implementation (TECNO, LG) can differ and
needs its own run (`android/scripts/system_effects_lab.py` on the phone is the planned route).

## 1. What the framework exposes

| Effect | What it can do | Limits found in the source |
| --- | --- | --- |
| DynamicsProcessing (per session) | 128 pre-EQ bands, 128 post-EQ bands, up to 128 MBC bands, input gain, a limiter, all per channel | Processes in an overlap-add FFT. Block = requested duration x sample rate, rounded to a power of two, between 8 and 16384 samples (`dsp/DPFrequency.cpp` `configure`, `aidl/DynamicsProcessingContext.cpp` `dpSetFreqDomainVariant_l`). The time-resolution variant returns "not available now" in the AOSP 14 reference (`setEngineArchitecture`). |
| Visualizer (per session) | Mono, 8-bit waveform and FFT, peak/RMS measurement, capture of up to 1024 samples | Registered as `INSERT_FIRST` (`media/libeffects/visualizer/EffectVisualizer.cpp`), so it sees the audio **before** DynamicsProcessing (`INSERT_LAST`). The repo's earlier note that the Visualizer reads pre-effect audio agrees with this. |
| Two DynamicsProcessing on one session | Not possible | `createEffect_l` reuses the effect module that already has the same UUID on the session (`getEffectFromDesc_l`), so a second instance adds a handle, not a stage. |
| Stereo cross-channel processing (mid/side, rotation, delay) | Not possible | DynamicsProcessing processes each channel separately. It has no delay and no cross-channel mixing. |
| Equalizer, BassBoost, Virtualizer, LoudnessEnhancer, reverbs, DTS (TECNO list) | Not characterised | Their parameters and processing are not measured here. The TECNO list shows an NXP Equalizer and an Xperi DTS Audio effect. Neither is used. |

## 2. The DynamicsProcessing maths that limit quality (AOSP android-14-release)

**EQ is applied per FFT bin, not as filters.** Each band's cutoff is rounded to a bin: `binStop = (int)(0.5 + cutoff *
block / rate)`. A band fills the bins from the previous band's stop + 1 up to its own stop. A band whose stop does not
pass the previous one fills no bin, so its gain is never applied. With 128 log-spaced bands, 64% of the 42 bands below 200 Hz
get no bins at a 4096-sample block (11.7 Hz bins); 81% at 2048, 38% at 8192 and 14% at 16384. Bins are the same width at 100 Hz and 25 Hz, so the bass
EQ resolution is set by the block size, not by the band count.

**Dynamics are block-rate.** The MBC and limiter detectors update once per hop (half a block). A block's energy is the
detector input, so attack and release shorter than one hop cannot take effect. Their gain is applied across the
synthesis window, so the effective response is about one hop.

**The limiter is an energy limiter with its threshold near full scale.** Its envelope is the RMS-like energy of a
block. Svan's threshold is -0.5 dB, so the limiter starts to act only when the block RMS is above about -0.5 dBFS. For a
sine that means a peak above about +4 dBFS before the limiter engages, and music with normal crest factor clips before
it ever engages. The limiter does not protect against sample peaks. Headroom (input gain) is the only protection.

**Latency is about one block.** With the 80 ms request Svan sends, the block is 4096 samples (85 ms).

**What the framework can do well:** EQ accuracy above roughly 200 Hz at 4096 samples, and above about 50 Hz at 16384 samples;
independent per-channel parameters; a 128-band post-EQ and a dynamic-EQ-style MBC on bins.

## 3. Predictions and measurements

The lab (`app/.../diag/EffectsLab.kt`, debug build only, run as `effects_lab` on the CI emulator) plays known signals
through a DynamicsProcessing it owns. `android/scripts/system_effects_lab.py` measures the host capture and prints each
result next to the framework model. The check lines are infrastructure only; the measurements are INFO lines.

| Question | Framework model (prediction) | Measured |
| --- | --- | --- |
| Bass EQ at 25 Hz, block 4096 (+6 dB band) | band gets no bins: about 0 dB | pending |
| Bass EQ at 63 Hz, block 4096 | band gets no bins: about 0 dB | pending |
| Bass EQ at 63 Hz, block 8192 | about +4.5 dB (Hann-weighted) | pending |
| Bass EQ, block 16384, 25–160 Hz | about +2 to +6 dB, smeared across bins | pending |
| Output change of a +12 dB band at 1 kHz | +12 dB | pending |
| Visualizer change for the same tone | +0 dB (input tap ahead of the EQ) | pending |
| Full-scale clip, +12 dB boost, limiter on | still clipped (limiter engages only above full scale) | pending |
| Same, limiter off | clipped | pending |
| Same, input gain -12 dB (headroom) | no clipping, exact gain | pending |
| MBC 4:1 band, steady gain for a 63 Hz tone at -20 dBFS | -3.9 dB (block detector) or -5.3 dB (RMS detector) | pending |
| MBC attack 1 ms against 40 ms: time to 63% of the gain change | nearly equal (attack below one hop has no effect) | pending |

The CI step runs at the end of the emulator job, on API 33 and 34, as a research step (`|| echo`), so it cannot block
the existing gates. Its results are in the `e2e-results-api34` / `api33` artifacts under `effects-lab/`.

## 4. What this means for quality (before measurement)

* **Headroom must come from the realised response.** Compute the maximum gain over the bins actually in use (EQ x MBC
  x post-EQ), not the sum of the band boosts. Set input gain to the negative of that maximum. Measure the clip count
  with and without it.
* **Sub-50 Hz EQ cannot be as accurate as Engine B on this framework.** The bin grid is the limit. Engine B's IIR
  filters have arbitrary Q at any frequency. This is structural, not a tuning problem, and it matters most for headphone
  bass corrections (AutoEq) below about 60 Hz.
* **The Visualizer gives an input tap on Engine A.** It sees the source before the EQ, so Svaresa could analyse the source
  there. It is mono, 8-bit and limited to 1024-sample captures at up to 20 Hz, so it can give loudness and coarse
  spectral balance, not the spectral detail Engine B has. Its accuracy is not measured yet.
* **Dynamic EQ is the framework's strength.** An MBC on bins with up to 128 bands can act as a dynamic EQ: each band
  compresses its own bins without a crossover. The cost is the block-rate timing (section 2).
* **Stereo geometry needs Engine B or the app's own player.** The framework has no cross-channel processing. The
  options on Engine A are coloration (reverb, the Virtualizer), and they should be measured for correlation and
  spectral change before any claim.

## 5. Research notes (sources checked)

* Compressor design: Giannoulis, Massberg and Reiss, "Digital Dynamic Range Compressor Design: A Tutorial and Analysis",
  JAES 60(6), 2012. Bibliographic details from the [Academia listing](https://www.academia.edu/31481096/Digital_Dynamic_Range_Compressor_Design_A_Tutorial_and_Analysis)
  and the [AES e-library](https://aes2.org/publications/elibrary-page/?id=16354); the full text was not retrieved.
  Its abstract favours a feed-forward design with the level detector in the log domain after the gain computer, for a
  smooth envelope without attack delay. The framework's block detector is the opposite trade-off.
* True peak: EBU R 128 sets a -1 dBTP ceiling for programme true peak in production
  ([EBU R 128 v4](https://tech.ebu.ch/docs/r/r128v4_0.pdf) as listed by search; not opened here). Measuring true peak
  needs 4x oversampling (ITU-R BS.1770). The framework's limiter can't provide that.
* Multiband versus dynamic EQ: a multiband compressor splits the signal with crossovers, which can add distortion or
  phase artefacts; a dynamic EQ changes each filter's gain instead
  ([Production Expert](https://www.production-expert.com/production-expert-1/2019/4/14/multiband-compression-vs-dynamic-eq-do-you-know-the-difference),
  [Sonarworks](https://www.sonarworks.com/blog/?p=6635)). The framework's bin-domain MBC has no crossovers, but its
  block rate limits the timing.
* Differentiable and neural controllers: the best-supported pattern is a neural controller steering a classic IIR or
  biquad EQ, with the audio path as plain DSP (for example [arXiv 2603.02794](https://arxiv.org/pdf/2603.02794),
  [arXiv 2606.22563](https://arxiv.org/pdf/2606.22563), both from search results, not read in full). No measured
  real-time music EQ latency was found, so no neural component is proposed for the audio path.
* "Geometric sonic reasoning": no established method by that name was found. The nearest matches were geometry-aware
  audio language models ([arXiv 2509.26140](https://arxiv.org/pdf/2509.26140)) and geometry-based spatial audio coding
  patents. This document interprets the phrase as stereo geometry and room geometry, and treats both as Engine B work.

## 6. Next steps (in order)

1. Read the CI results for `effects-lab` on API 33 and 34, and replace the "pending" rows with the measured values.
2. If the bass prediction holds, decide the block size per listening mode, and report the sub-bass limit honestly.
3. Build the headroom calculation from the realised response, and measure its clip count on the same lab.
4. Measure the Visualizer analysis tap's accuracy against known spectra (pink noise with a known tilt, a sine sweep).
5. Run the same lab on the TECNO and the LG (`system_effects_lab.py` against the phone), since their DynamicsProcessing
   implementations may differ.
