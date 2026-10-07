# Recording mode: audio for an externally filmed demo

Hi-Fi → Recording mode saves Svan's own **digital** dry input and processed output.
The TECNO LH7n runs Svan; the LG V60 films the TECNO screen as a camera. The LG microphone
provides a sync reference only. The finished video's sound comes from Svan's files.
This excludes Android's output mixer, DAC/USB conversion, Bluetooth encoding and acoustics.
It is not a headphone or DAC measurement.

Android screen recorders cannot hear Engine B because Svan excludes its own output from
playback capture to prevent feedback. Recording mode uses the existing dry/wet taps; it
creates no screen recorder, VirtualDisplay or additional MediaProjection. Engine B still
requires its normal playback-capture consent. Apps on system effects are outside these files.

## Make a video in VN

1. Start the audiophile engine in Hi-Fi. Play the chosen source and check that it actually
   connects to Engine B and carries signal. Choose your listening headphones as usual.
2. Set up the LG to film the TECNO screen at normal distance and room light. Include the
   clock and segment label. Enable the LG microphone so it can hear the speaker sync cue.
3. Start filming on the LG. In Svan's Recording mode choose **Start with countdown**. A big
   3, 2, 1 leads to recording at 0 with a sync flash and speaker clicks. Alternatively choose
   **Start recording**, then press **Sync** once filming is underway.
4. Demonstrate settings. The fixed recording panel stays above every tab, with a large
   `m:ss.mmm` clock, segment label, **Sync**, **Mark now** and **Stop**. Use Mark now to type a
   label of up to 40 characters for the next stretch. Pause after changing settings so the
   automatic setting marker can settle. Additional syncs are allowed and reported in order.
5. Press **Stop**, wait for saving, then stop filming. Files are in the session folder shown
   by Svan: audio under `Music/Svan Proof/<stamp>/`, charts/report under
   `Download/Svan Proof/<stamp>/`. Transfer Svan's audio files to the LG locally as needed.
6. In VN import the LG video, `svan-processed-from-sync.wav`, and temporarily
   `svan-processed-sync-cue.wav`. The two processed files share the same start and underlying
   audio; the cue copy adds three clicks at its start. Align their starts together.
7. Expand VN's audio waveforms. Match the three clicks in the LG clip's microphone track to
   the clicks in the cue copy. Check the white flash frame and read the on-screen clock.
   The clock refers to the **full** WAV: for a from-sync file, subtract the first `syncSeconds`
   in the report from the displayed clock. Adjust the clip/audio offset accordingly.
   If clicks are unavailable, use the flash and clock directly. Use later syncs/clocks to
   check alignment across the demo, especially if `droppedFrames` is nonzero.
8. Delete the cue copy from the project and mute the LG microphone track. Keep the plain
   processed-from-sync file as the finished soundtrack. For A/B demonstrations, the
   dry-from-sync file has the same frame alignment. Judge differences at matched RMS level;
   louder often sounds better. Export your video from VN.

Speaker/flash delay has **not** been measured on either owner phone. The clicks are an
approximate acoustic alignment aid; no “few tens of ms” guarantee is established. The
frame-derived clock is the precise **file-position** reference, displayed on UI frames,
not a measurement of capture-to-speaker, camera, headphone or Bluetooth latency. Follow the
measurement protocol in [PHONE_VALIDATION.md](PHONE_VALIDATION.md) before relying on acoustic sync.

## Files and editor compatibility

| File | Purpose |
|---|---|
| `svan-dry-input.wav` | Full pre-DSP input |
| `svan-processed-output.wav` | Full processed output |
| `svan-processed-output.m4a` | Convenience AAC-LC export; requested 48 kHz stereo, 256 kbps |
| `svan-dry-from-sync.wav` | Exact byte-copy tail of the dry WAV at the first sync frame |
| `svan-processed-from-sync.wav` | Exact byte-copy tail of the processed WAV at the first sync frame |
| `svan-processed-sync-cue.wav` | Alignment-only copy with clicks mixed at 0, 250 and 500 ms |
| `svan-processed-matched.wav` | Optional full-duration processed audio with per-segment RMS matching |
| `svan-proof-chart.png` | Whole-recording spectrum and levels |
| `svan-settings-effects.png` | Per-segment spectrum difference and RMS difference |
| `svan-proof-report.json` | Settings, measurements, exact markers and export details |

The three sync files exist only when a sync was used, including countdown. If the recording
ends during the cue, its alignment copy is padded to contain all three clicks; the plain
files are never extended or given clicks. Delete the cue track after alignment: mixing it
with loud content can clip. Later syncs are listed in the report; the exported cue copy uses
the **first** sync only.

**VN on the LG V60: 24-bit WAV import is unverified.** The UI defaults to 16-bit stereo PCM
WAV with TPDF export dither for editor compatibility; this is a conservative choice, not a
claim that VN rejects 24-bit. Uncheck **16-bit WAV for editors** to retain 24-bit PCM.
Neither choice changes DSP or live output. Record and import both choices during phone validation.
M4A is lossy and may have AAC encoder delay: prefer WAV for precise alignment. It contains
the full recording, so also account for the first sync offset when using it. AAC failure is
reported separately while available WAVs are saved.

**Also save RMS-matched audio** defaults off. As in BlindRenderer, matching uses the RMS ratio
between the saved streams for each segment, with a peak cap to prevent new export clipping.
The report lists `matchedGainLinear`, `matchedGainDb` and `matchLimitedByPeak`. Silence can require
zero gain; a peak-limited segment cannot necessarily reach dry RMS. Gain is constant within
a segment; changes at boundaries may be audible. This optional file starts at the full
recording's frame zero. No LUFS/BS.1770 claim is made.

## Clock, sync and segments

- Clock time is committed stereo frames (`written / 2`) divided by sample rate. It advances
  with saved audio rather than wall time, stops during missing capture blocks, and does not
  include jointly dropped frames. It is refreshed on UI frames, with fixed space for the
  clock/label/buttons. The app window stays awake and uses full window brightness while
  recording/counting down; previous window brightness and keep-awake state are restored.
- `markSync()` reads the same volatile committed position as `mark`, on a control thread,
  and records it with `System.currentTimeMillis()`. The report has ordered `syncFrames`,
  full-precision `syncSeconds`, and corresponding `syncEpochMs` arrays. `recordedAtEpochMs`
  is the start request; `clockStartEpochMs` estimates the first dry block's epoch from the
  start epoch and monotonic elapsed time, or is null if no block arrived.
- `firstSignalSeconds` is the start of the first fixed 240-frame block whose stereo RMS is
  above -90 dBFS in either **original** stream, before export dither. It is null for silence.
  This is block-resolution detection, independent of disk chunks or segment boundaries.
- The flash is requested for about 150 ms. A separate AudioTrack plays the tested digital
  cue: three 2 kHz, 10 ms sine bursts, 250 ms start spacing, -6 dBFS peak. It opts out of
  playback capture, prefers the built-in speaker and checks the actual route with silence
  before submitting clicks. Missing/unverifiable routing suppresses clicks and shows a
  message; a detected route change pauses/flushes the cue. Volume and audio focus are not
  changed. Android preferred-device routing is advisory; owner headphones and OEM routing
  still require the explicit phone check. The cue is never injected into the recording taps.
- A setting snapshot stable for 700 ms opens a segment. Snapshots include applied EQ curves,
  tuners, quality/protection and headphone correction, so edits with unchanged band count
  are detected. Manual marks use the same committed frame boundary. The finished PCM files
  are analysed at those exact boundaries, fixing markers that arrive after a writer chunk
  has drained. Marks at zero replace the opening label; trailing zero-length marks are omitted.
- At most 12 segments are measured; later changes merge into the final segment and the report
  says when the limit was reached. All syncs remain separate from the segment limit.

## Limits and verification

Full-recording measurements are taken before PCM export; segment measurements are of the saved
PCM, including quantisation/dither. Per-segment `rmsChangeDb` is processed minus dry RMS, not
LUFS. Short segments may have RMS but no complete spectrum window. Peaks are sample peaks,
not reconstructed true peaks. Overs are counted before PCM clipping. The spectrum is a
Welch-averaged third-octave mid (L+R) estimate; compare dry→processed deltas.

The recording limit is ten minutes, with a final buffered tail possible. If storage falls
behind, matching blocks are dropped from both files and counted. File time then differs from
camera wall time; align/check several points. WAV trimming preserves original quantisation
and dither sample-for-sample and rewrites only the header. All filesystem operations, charts,
segment analysis, dither, matching, MediaCodec and MediaMuxer work run in the writer/finishing
path. `offerDry` and `commitWet` remain allocation-free and wait-free, unchanged.

No storage permission or upload is added. MediaStore publishes audio and Downloads, never Movies.
The last successful session also stays in app-private storage for diagnostics; older successfully
published private sessions are removed on subsequent success. Failed sessions remain private.

Local numerical tests and CI/emulator evidence are recorded in [HANDOFF.md](HANDOFF.md).
Physical clock readability, acoustic click latency/routing, live headphone equivalence and VN
import remain **unverified** until the owner completes [PHONE_VALIDATION.md](PHONE_VALIDATION.md).
