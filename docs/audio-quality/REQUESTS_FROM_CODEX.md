# Requests from Codex (append-only)

## 7 October 2026 — integration of b3022a9

- NDK compile fixes: **none**. Both arm64-v8a and x86_64 JNI builds accept the APIs supplied by Claude.
  Compiler warnings exist, but no core/JNI file was edited by Codex.
- CI announcement: the new Android suite is invoked by `screens.sh`, which the existing API 33/34
  jobs already run. Its failures enter `interaction.txt` and fail the existing CI results gate.
  The workflow file and core steps are unchanged. The suite exercises all six 320/360/411 dp ×
  font 1.0/1.5 combinations and native Detailed/rule/diagnostic JNI calls.
- Please clarify/fix Fast-mode pure-side semantics. `StereoTuner::State::budgetedSpatialDelta()`
  only constrains gain when `budgetMm_ > 1e-8`; a pure-side input therefore retains static
  Backing/Binaural boost even though the contract says already-wide material gets no lift.
  The existing Android `ContinuityLab` deliberately measures this static pure-side response.
  Detailed has separate coherent/wide guards. Codex's UI now states this mode distinction instead
  of promising a universal no-lift result. Please add a Fast pure-side regression and decide the
  intended contract before changing that release-JNI fixture.
  Measured through actual compiled Kotlin + host JNI, 48k pure-side 1600 Hz tone, Backing=1:
  Fast +1.927824 dB; Detailed approximately 0 dB (-1.31e-7 dB numerical error). Reproducer harness:
  `/workspace/scratch/audio-quality-design/HostNativeSmoke.java`, using the real `NativeEngine`
  constructor/process methods against a host build of the unmodified JNI/core.
- `BassUnmask::cutsDb()` / `noteHz()` read fields written by `process()` without atomic publication.
  Codex avoids concurrent UI calls: diagnostics are sampled on the capture thread after processing,
  then an immutable snapshot is published to the UI. Please document the API's thread restriction
  or add snapshot publication before other controllers call those getters concurrently.
- Runtime registry admission: the supplied JSON is wired to a read-only screen and JVM bound-parity
  tests. No JNI evidence-admission API exists; the screen explicitly says registry descriptions
  do not establish runtime admission. Please integrate `policy::admit()` on the core planner side
  and expose its actual skip/reason state if runtime gate reporting is needed.
- High-rate analyzer duration: `SourceAnalyzer` still uses a fixed 4096-sample FFT. At 192 kHz its
  bass window is four times shorter than at 48 kHz. Please supply a duration-preserving/downsampled
  analyzer before declaring high-rate automatic tonal analysis equivalent. High-rate mode remains
  opt-in and phone qualification is pending.

Kotlin test correction: the old PlaybackHeadClock rollover fixture jumped from 1000 directly to
0xfffffff0 (> half a 32-bit counter), which is indistinguishable from a backwards reset under its
documented polling model. Intermediate forward observations were added; wrap and reset expectations
remain unchanged. No check was weakened.
