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

**Showing what settings do (screen + sound).** Pick *Screen + sound* and start recording. Svan records the
screen from the engine's existing MediaProjection and muxes it with its own processed output into one
`svan-demo.mp4` (`Movies/Svan Proof/<stamp>/`), so the video's audio is what Svan really sent out, live,
as you change settings. Choose *Entire screen* in Android's dialog, or the video shows only that one app.
Android 14+ allows one screen recording per audiophile-engine start (one VirtualDisplay per projection);
stop/start the engine to record the screen again. *Sound only* skips the video.

**What each setting did.** A setting change that stays put for 0.7 s starts a new segment (up to 12; knob
drags don't split). Each segment is measured separately (levels and third-octave dry→processed change) and
labelled with what changed, e.g. `Quality: Efficient → Audiophile`. `svan-settings-effects.png` draws one row
per segment; the same data is in `svan-proof-report.json` under `segments`. Svaresa's automatic changes also
start segments. Segments shorter than ~0.5 s are listed as too short to measure.

**Video/sound alignment.** Audio is timed from its first committed block, video from the first encoder frame:
expect about ±100 ms at the start, plus any Bluetooth/DAC delay the listener would have (not removed).
The WAV pair can still be used in an editor instead.

**Limits (also written into every report).**
- Screen video size is about 720p on the short side, fixed at start (rotating mid-recording distorts it).
- Only apps on the audiophile engine are recorded; system-effects apps are not.
- It is Svan's digital output. It excludes Android's mixer, the DAC/USB path, Bluetooth encoding and
  the headphones, and is not an acoustic measurement.
- Levels are sample peaks, not true peaks; overs are counted and clamped in the 24-bit WAV.
- The spectrum is a Welch-averaged third-octave estimate of L+R; only dry→processed differences mean anything.
- Maximum 10 minutes per recording. If storage falls behind, blocks are dropped from both files equally and reported.

**Verified:** `ProofRecorderTest` (JVM): a known +6.02 dB gain reads +6.02 dB at 1 kHz, flat/+6/−6 dB segments
measure 0/+6.02/−6.02 dB, WAV headers/samples decode, overs are clamped not wrapped, silence yields no
spectrum, setting labels read correctly. **Not yet verified (no Android SDK where this was written):**
the on-phone UI, screen+audio MP4 muxing and A/V sync, MediaStore publishing and chart rendering.
Check on TECNO LH7n (Android 14) and LG V60 (Android 13) before relying on it.
