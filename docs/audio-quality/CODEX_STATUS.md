# Codex Kotlin integration status — 7 October 2026

Codex branch: `claude/codex-audio-crackling-amplifier-gkj007`.
Claude branch merged without conflicts: `ccr-2e937472-6z53b0`, source `b3022a9863621b5d79cf3342c9ded89e156be08b`.
Merge commit: `2f04da5`. Claude's branch was not rewritten. No PR, release or version change was made.
Core, JNI/CMake, AGENTS, LICENSE, privacy docs, Claude STATUS and generated POLICY_RULES were not edited by Codex.

## Implementation

| Step | State | Evidence and limits |
|---|---|---|
| K0 | Local checks pass | Both arm64-v8a/x86_64 NDK builds accepted the supplied APIs. Core: 142 tests, 0 failed checks. Android debug/release/lint/JVM checks pass; 177 JVM tests, 0 failures/errors. Claude CI 37603033259 failed only the old PlaybackHeadClock fixture; Codex corrected the ambiguous jump, retaining wrap/reset assertions. No JNI fixes needed. |
| K1 | UI/matrix ready; screenshots pending CI | Backing/Binaural first row; Resolve/Auto switch retained. Bass dials size from actual card width. Per-player text distinguishes native controls, system approximations, off and unavailable. Matrix covers 320/360/411 dp × font 1.0/1.5 and saves XML/PNG. |
| K2 | Wired; phone tuning pending | Fast default/migration; Detailed flag through JNI; total native latency reported/aligned. Recorded clips retain applied settings, including Fast fallback. A physical missed deadline plus underrun, or existing exhausted-buffer recovery, retries Fast/48k once while preserving saved choices. No invented phone DSP% threshold. |
| K3 | Wired; phone/clock qualification pending | Safe 48k default, serial RatePolicy candidates, strict client-format validation and safe fallback. Router ownership/mutes surround all epochs. Source changes reset native state at a faded boundary and invalidate clips. Frames, DSP, clips, WAV, renderer and probes use actual rates. RateFacts separate client/device formats; source/DAC unknown. Oversampling preserves internal family targets; phone performance unmeasured. |
| K4 | Read-only UI/JVM parity wired | Compiled JSON supplies “How Svaresa decides”: inputs, bounds, reasons, coordination, rollback. JVM tests compare context/Resolve bounds to generated matrix. SvaresaBrain behavior unchanged. Runtime admission remains Claude's core work and UI explicitly says this. |
| K5 | Opt-in experiment wired | Off default, never enabled by Auto; synthetic-only/misclassification copy; <=2dB combined; setter/diagnostics. Diagnostics sampled after process on capture thread and published as immutable UI snapshots. Music validation pending. |
| K6 | No phones available | `adb devices -l`: none attached; no /dev/kvm locally. No TECNO/LG CPU, battery, underrun or blind preference results. Owner's Amazon HD/Spotify lossless success retained without inferring source/DAC format. |

## Verification

- Core Release tests: `/workspace/scratch/release-build-environment/core-build/eqcore_tests`;
  `/workspace/scratch/audio-quality-design/k0-core.log`: 142 tests, 0 failed checks.
- Android logs under `/workspace/scratch/audio-quality-design/`: `kotlin-integration-build.log`
  (4m08s), `final-integration-build.log` (3m22s), `current-tree-build.log` (3m28s),
  and final `prepush-build.log` (3m18s, BUILD SUCCESSFUL; 177 JVM tests).
- Python screenshot/control assertions: 11 pass; shell syntax and diff whitespace pass.
- `AudioQualityLab` exercises actual Fast/Detailed JNI, aligned identity and exact extra N frames
  at 44.1/48/96k, five-field diagnostics and the 19-rule JSON. It runs in existing quality checks,
  including production UI checks, and separately in the screenshot suite. Device execution is
  pending CI; do not call it passed without its READY marker.
- Separate **host** JNI execution against the actual compiled Kotlin NativeEngine and unmodified
  JNI/core passes the same 44.1/48/96k constructor/delay/identity calls: aligned max error 0;
  diagnostics length 5; rule count 19. This is not Android execution. Harness and host shared library
  are in `/workspace/scratch/audio-quality-design/HostNativeSmoke.java` and `libeqjni.so`.
- UI matrix is invoked from `screens.sh`. Failure enters `interaction.txt`, rejected by the
  existing CI results gate. Workflow/core steps and established check counts are untouched.

## Remaining evidence and requests

See `REQUESTS_FROM_CODEX.md`: Fast pure-side semantics, unmask getter thread restrictions, runtime
registry admission, and duration-preserving high-rate analysis. High-rate route changes require a
capture restart to renegotiate rate. Capture backlog and independent clock drift are unmeasured.
The nominal system-effects curve is explicitly `CURVE_SAMPLE_RATE`; it is not live capture format
provenance or the full dynamic Detailed response.

When a reviewed preview is available, request the owner's Share diagnostic reports for TECNO
LH7n/API34 and LG V60/API33 with Spotify, Amazon Music and YouTube Music active, specifying output
route; sustained Fast/Detailed playback, underruns/battery observations; and matched blind votes
for Backing/Binaural, Resolve and optional unmasking. No such evidence exists for this implementation.
