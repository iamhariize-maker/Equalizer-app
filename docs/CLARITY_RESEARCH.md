# Clarity investigation — 4 October 2026

The user's reference is local FLAC/WAV playback in a dedicated player. Svan's
reported problem is YouTube Music over Bluetooth on a TECNO LH7n: undetected
playback, delay or double audio, and a buried sound when processing is enabled.
Those are separate problems; more bands or higher oversampling cannot fix routing
or an inappropriate correction curve.

## Findings and changes

1. **Connection comes first.** Android playback capture requires permission and
   the source app's capture policy. Svan excludes unknown or unmuted sources to
   avoid replaying a second copy. The enhanced music-detection setup grants Svan
   DUMP through a short-lived Shizuku helper; CI tests a player already playing,
   retained detection after the helper stops, and exactly one processed copy.
   System effects are now the fresh-install default to avoid capture/replay.
2. **Attenuation was sticking.** The native protection retained a previous
   overload reduction through quieter music. It now recovers with a 250 ms release,
   with stereo linking and ceiling regressions. Automatic headroom still lowers
   level for positive EQ curves; compare at matched loudness before judging clarity.
3. **Bass shaping could cancel its own attack.** Recombining a phase-shifted LR4
   low-pass with the dry signal lost approximately 6 dB near its crossover. The
   complementary first-order split passes attack-energy checks at four bass
   frequencies and two sample rates. Switching bass off restores exact identity.
4. **Flat did not clear all layers.** Built-in Flat and Reset all sound now clear
   headphone correction, bass, vocal, instrument and manual EQ together. Start
   comparisons here; stacked boosts/cuts can obscure music even when each control
   performs its advertised operation. Other presets preserve independent layers.
5. **Android band count is not effective bass resolution.** The reference system
   effect rounds its FFT size to a power of two and cutoff frequencies to bins.
   At 48 kHz, a requested 10 ms window means roughly 93.75 Hz bin spacing; 40 ms
   means 23.44 Hz. CI `37207558523` measured only +4.9/−4.6 dB for requested
   +6/−6 dB bells at 63 Hz with 40 ms. A separate spectral/window model predicts
   +4.90 dB and +5.57 dB at 40 and 80 ms respectively. Detailed now requests
   80 ms, retaining 10/40 ms options. The unchanged actual-output ±1 dB tests
   must pass before delivery. More delay is the tradeoff; OEM behavior is unverified.
6. **Capture precision controls do not control the output hardware.** Native
   oversampling can improve high-frequency EQ accuracy, but it cannot change a
   Bluetooth codec, restore information missing from a source, or make Android's
   system effect use the native filters. UI now separates these controls and uses
   unquantized float output by default.

## Bit-perfect playback and the reference player

Neutron's own bit-perfect instructions require DSP, EQ and dither to be disabled
and a compatible output route. EQ intentionally changes the samples. An accurate
EQ can offer excellent fidelity, but the result is no longer identical to the
source. A dedicated player with direct USB output controls a different path from
a global streaming-app equalizer. Android's preferred USB mixer attributes are
available to the app playing its own track on supported devices; they do not grant
Svan exclusive control of another app's Bluetooth playback.

YouTube Music documents high-quality AAC/Opus at 256 kbps. Spotify currently also
offers lossless FLAC on supported plans and devices. Source quality alone does not
establish bit-perfect transport. Svan cannot promise bit-perfect global streaming
or Bluetooth playback.

## Primary references

- [Neutron: bit-perfect output](https://neutroncode.com/faq/38-bit-perfect-output)
- [Neutron: best settings](https://neutroncode.com/faq/46-best-settings)
- [Android: playback capture and source policies](https://developer.android.com/media/platform/av-capture)
- [Android: preferred USB mixer attributes](https://source.android.com/docs/core/audio/preferred-mixer-attr)
- [Android: DynamicsProcessing API](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing)
- [Android 14 reference frequency processing](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-14.0.0_r1/media/libeffects/dynamicsproc/dsp/DPFrequency.cpp)
- [YouTube Music: audio quality](https://support.google.com/youtubemusic/answer/9076559?hl=en)
- [Spotify: audio quality](https://support.spotify.com/us/article/audio-quality/)
- [ITU-R BS.1116: controlled listening comparisons](https://www.itu.int/rec/R-REC-BS.1116)

The next evidence must come from the user's phone: connected player status,
level-matched music with all layers reset, then one conservative EQ change,
followed by screen-off and Bluetooth stability testing. Emulator tones establish
routing and gain response; they cannot establish subjective superiority.
