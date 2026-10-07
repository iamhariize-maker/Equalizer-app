# Codex Kotlin integration status — 7 October 2026

Codex branch: `claude/codex-audio-crackling-amplifier-gkj007`.
Claude branch merged without conflicts: `ccr-2e937472-6z53b0`, source `b3022a9863621b5d79cf3342c9ded89e156be08b`.
Merge commit: `2f04da5`. Claude's branch was not rewritten. No PR, release or version change was made.
Implementation commit: `1c437cf7a90f6c13ed13dfbfda5cb9a7db7b1609`.
This record includes a tested follow-up: live protection/unmasking metadata follows applied controls,
clips cancel if those controls change mid-recording, and UID reopens retain them without applying
pending format changes or undoing a Fast/48k safety fallback. Its own hosted CI is pending push.
Core, JNI/CMake, AGENTS, LICENSE, privacy docs, Claude STATUS and generated POLICY_RULES were not edited by Codex.

## Implementation

| Step | State | Evidence and limits |
|---|---|---|
| K0 | Local checks pass | Both arm64-v8a/x86_64 NDK builds accepted the supplied APIs. Core: 142 tests, 0 failed checks. Android debug/release/lint/JVM checks pass; 179 JVM tests, 0 failures/errors. Claude CI 37603033259 failed only the old PlaybackHeadClock fixture; Codex corrected the ambiguous jump, retaining wrap/reset assertions. No JNI fixes needed. |
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
- Follow-up `live-controls-build.log` (3m47s): debug/release/lint and 179 JVM tests pass. Core repeated:
  `live-controls-core.log`: 142 tests, 0 failed checks. Two new regression tests pin static mode/rate/
  quality while live controls change and require mixed-setting clips to be invalidated.
- Python screenshot/control assertions: 11 pass; shell syntax and diff whitespace pass.
- `AudioQualityLab` exercises actual Fast/Detailed JNI, aligned identity and exact extra N frames
  at 44.1/48/96k, five-field diagnostics and the 19-rule JSON. It runs in existing quality checks,
  including production UI checks, and separately in the screenshot suite. Device execution is
  pending CI; do not call it passed without its READY marker.
- Separate **host** JNI execution against the actual compiled Kotlin NativeEngine and unmodified
  JNI/core passes 44.1/48/88.2/96/176.4/192k constructor/delay/identity calls: aligned max error 0;
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
Independent capture-admission probes remain safe 48k before main negotiation, and the system-effect
band-capacity probe creates its own 48k test session; neither is live-format or source-rate evidence.
The existing blind UI compares original versus the combined tuned render, not a selectable
Fast-versus-Detailed or single-feature comparison. Isolated feature qualification still needs
matched paired renders of the same excerpt with all other settings fixed; aggregate preference
votes cannot establish which feature helped. Recorded excerpts intentionally retain their applied
capture settings, so changing a saved mode afterward is not a valid mode-comparison procedure.

## CI checkpoint on the implementation commit

- Push run [37607981934](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37607981934)
  and PR run [37607988956](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37607988956)
  both passed core Release, generated policy parity, ASan/UBSan, TSan and Android build/lint/unit,
  preview install sanity, production APK/AAB and release-verifier positive/negative fixtures.
- API 29/30/33/35/36 compatibility smoke matrix passed in the PR run. Full API 33/34 suites and
  the new width/font screenshots are still pending at this checkpoint.
- Downloaded the **exact CI preview**, artifact `11476570771`, from the push run. APK SHA-256:
  `ada286a20f5a51c2a16c00499932dfc65a09b59630447f9b92e19056a8a15d26`.
  APK v2 signature verifies with the existing **preview** certificate `fd7955d1…`, not the owner
  production certificate. It is a test preview, not a new public release or an in-place update
  over an owner-signed beta.

When a reviewed preview is available, request the owner's Share diagnostic reports for TECNO
LH7n/API34 and LG V60/API33 with Spotify, Amazon Music and YouTube Music active, specifying output
route; sustained Fast/Detailed playback, underruns/battery observations; and matched blind votes
for Backing/Binaural, Resolve and optional unmasking. No such evidence exists for this implementation.

### K6 qualification procedure still to implement/run

1. Add an explicit comparison kind to `BlindRenderer`/`BlindListening`: combined chain (existing),
   Fast versus Detailed, Resolve off versus the selected effective level, and unmasking off versus
   on (explicit experimental opt-in only). Freeze the excerpt, sample rate, EQ/Auto plan, quality,
   dither and every unrelated control once for both branches. Preserve capture metadata; override
   only the parameter under test in the offline render, never the saved live settings. Pad and
   align each branch by its own `nativeLatency`; match within 0.1 dB without makeup boost. Hash
   both full configurations and comparison kind into the vote record, and reveal the correct
   identities rather than calling every baseline “original.” Test that only the intended parameter
   differs. Aggregate tuned-versus-original votes are not substitutes for these comparisons.
2. On each phone, run sustained Fast and Detailed sessions with the same route/volume/player,
   including screen-off/background playback and route changes. Collect beginning/end reports,
   applied epoch/rate/latency, underrun deltas, recovery messages, battery percentage/time and
   charging/thermal conditions. `Stats.dspPercent` is native processing time divided by audio
   duration, **not total app/system CPU**; obtain CPU traces if available, without adding forbidden
   permissions. Derive any CPU-percentage fallback threshold from these measurements. The current
   deadline-plus-underrun fallback is an objective safety trigger, not phone calibration.
3. Listen blindly on the same fixed route to diverse music, bass attack/body/sustain, coherent
   panned material and already-wide recordings. Keep raw votes and no-preference results. Check
   true-peak/headroom and feature bypass/counterexamples separately; preference does not establish
   technical transparency, native source rate, DAC rate, or general player/OEM compatibility.
