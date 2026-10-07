# Recording mode (Hi-Fi → Recording mode)

**Problem.** Android screen recorders cannot capture the audiophile engine: Svan's output opts out of
playback capture (`allowAudioPlaybackCapture="false"`, needed so Engine B never re-captures itself), and a
second `MediaProjection` can compete with the recorder's.

**Solution.** Svan records itself. `CaptureService` hands `ProofRecorder` the buffer it just read (dry,
before the DSP edits it in place) and the exact buffer it writes to the `AudioTrack` (processed). The two
streams are committed in lock-step, so the files are sample-aligned.

Saved without any storage permission (MediaStore, API 29+):

| File | Location |
|---|---|
| `svan-dry-input.wav`, `svan-processed-output.wav` (24-bit PCM stereo) | `Music/Svan Proof/<stamp>/` |
| `svan-proof-chart.png` (dry vs processed spectrum, change per third-octave, levels) | `Download/Svan Proof/<stamp>/` |
| `svan-proof-report.json` (levels, spectrum rows, settings, engine stats, notes) | `Download/Svan Proof/<stamp>/` |

**Making the video.** Screen-record with sound off; lay either WAV under it in a video editor (the
chart is a ready-made overlay). Alternating the two WAVs gives the audible before/after.

**Limits (also written into every report).**
- Only apps on the audiophile engine are recorded; system-effects apps are not.
- It is Svan's digital output. It excludes Android's mixer, the DAC/USB path, Bluetooth encoding and
  the headphones, and is not an acoustic measurement.
- Levels are sample peaks, not true peaks; overs are counted and clamped in the 24-bit WAV.
- The spectrum is a Welch-averaged third-octave estimate of L+R; only dry→processed differences mean anything.
- Maximum 10 minutes per recording. If storage falls behind, blocks are dropped from both files equally and reported.

**Verified:** `ProofRecorderTest` (JVM): a known +6.02 dB gain reads +6.02 dB at 1 kHz, WAV headers/samples
decode, overs are clamped not wrapped, silence yields no spectrum. **Not yet verified:** the on-phone UI,
MediaStore publishing and chart rendering (need CI/real-phone check on TECNO/LG).
