# Codex Kotlin integration status — 7 October 2026

## Owner dropout/spatial follow-up — current work

See [spatial continuity follow-up](SPATIAL_CONTINUITY_FOLLOWUP.md) for changes, conditional
native authorization, cue bounds and the TECNO × IM4/Realme Air 8 × Spotify/Amazon/YT Music
phone matrix. This entry supersedes K2's immediate deadline-triggered restart description.
Existing mode preferences are retained; Auto is an explicit experimental choice.

Local verification on this follow-up:

- Release core: 151 tests, zero failed checks; generated 19-rule matrix parity unchanged.
- Android debug/release assembly, lintDebug and all 184 JVM tests pass. Final incremental
  verification after the settled-path optimization passes in 5m33s; the initial clean build
  took 16m25s. Both arm64-v8a/x86_64 JNI libraries compile.
- ThreadSanitizer: four parameter/publication tests pass, including concurrent spatial mode
  and load-limit setters. The full local ASan/UBSan suite passes 151 tests; after the final
  settled-path optimization, 13 spatial/publication and one allocation check pass again.
  Hosted source `fdce86a355ce8417d8775003e1c39198c740e0c1` also passes all 151 Release,
  151 ASan/UBSan and four ThreadSanitizer tests in run `37650746940`.
- Actual compiled Kotlin NativeEngine with host JNI: 44.1/48/96k mode/load calls keep latency
  fixed, preserve the center and reach exactly zero Detailed mix under load. This is host
  execution, not Android playback or proof of player compatibility.
- Eleven Python screenshot/control assertion tests pass. Release-preview signature verification,
  forbidden-capability check and ZIP/ELF 16 KB alignment checks pass (four native libraries).
- Host spatial fixture: 32 seconds of audio takes 0.803 s in Detailed and 0.718 s in aligned
  Fast. This single fixture is not a phone CPU measurement or an Auto worst-case benchmark.

No phone is attached and local KVM is unavailable. Hosted API33/API34 checks, screenshot
inspection and phone listening remain separate verification. No owner key was accessed,
no public release/site update or version bump was made. A preview uses the existing preview
signer and cannot update an owner-signed public installation in place.

The follow-up status label reads the applied mode rather than the fixed-delay capability:
it correctly reports Auto and temporary/live Fast in a Detailed-capable session. The actual
Detailed blend percentage remains independently visible. The exact CI preview from run
`37650746940`, artifact `11496940170`, has APK SHA-256
`ae80da079013ea8231dd1ca325a7fe01fce5b73a290fe5db206ebbb645a51ba6`; its signature,
forbidden-capability policy and four native libraries' 16 KB page alignment are verified.
That artifact predates this status-label correction; final hosted UI checks use the next source.
The correction passes the full local Android command in 3m06s and the 151-test Release core suite.

Codex branch: `claude/codex-audio-crackling-amplifier-gkj007`.
Claude branch merged without conflicts: `ccr-2e937472-6z53b0`, source `b3022a9863621b5d79cf3342c9ded89e156be08b`.
Merge commit: `2f04da5`. Follow-up source `62c43cf` merged without conflicts as `a682deb`.
Claude's branch was not rewritten. No PR, release or version change was made.
Implementation commit: `1c437cf7a90f6c13ed13dfbfda5cb9a7db7b1609`.
This record includes a tested follow-up: live protection/unmasking metadata follows applied controls,
clips cancel if those controls change mid-recording, and UID reopens retain them without applying
pending format changes or undoing a Fast/48k safety fallback. This fix is pushed as `2f27c71`;
its CI runs are superseded by the subsequent layout/harness follow-up.
Core, JNI/CMake, AGENTS, LICENSE, privacy docs, Claude STATUS and generated POLICY_RULES were not edited by Codex.

## Implementation

| Step | State | Evidence and limits |
|---|---|---|
| K0 | Local checks pass | Both arm64-v8a/x86_64 NDK builds accepted the supplied APIs, including the follow-up gated planner API. Core: 147 tests, 0 failed checks. Android debug/release/lint/JVM checks pass; 179 JVM tests, 0 failures/errors. Claude CI 37603033259 failed only the old PlaybackHeadClock fixture; Codex corrected the ambiguous jump, retaining wrap/reset assertions. No JNI fixes needed. |
| K1 | API33/34 full matrix passes; images reviewed | Backing/Binaural first row; Resolve/Auto switch retained. Bass dials size from actual card width. Per-player text distinguishes native controls, system approximations, off and unavailable. Inspection found mid-word wrapping of Instruments and clipped dock/tab labels at font 1.5; weighted label areas, a content-sized dock and explicit tab ellipses fix these. Matrix targets 320/360/411 dp × font 1.0/1.5 and saves XML/PNG. Large-font content needs scrolling; not every control fits simultaneously. |
| K2 | Wired; phone tuning pending | Fast default/migration; Detailed flag through JNI; total native latency reported/aligned. Recorded clips retain applied settings, including Fast fallback. A physical missed deadline plus underrun, or existing exhausted-buffer recovery, retries Fast/48k once while preserving saved choices. No invented phone DSP% threshold. |
| K3 | Wired; phone/clock qualification pending | Safe 48k default, serial RatePolicy candidates, strict client-format validation and safe fallback. Router ownership/mutes surround all epochs. Source changes reset native state at a faded boundary and invalidate clips. Frames, DSP, clips, WAV, renderer and probes use actual rates. RateFacts separate client/device formats; source/DAC unknown. Oversampling preserves internal family targets; phone performance unmeasured. |
| K4 | Read-only UI/JVM parity wired | Compiled JSON supplies “How Svaresa decides”: inputs, bounds, reasons, coordination, rollback. JVM tests compare context/Resolve bounds to generated matrix. SvaresaBrain behavior unchanged. Claude's core now admits planner evidence; Kotlin still uses the legacy API's fresh/same-epoch defaults. Genuine feature-age/epoch provenance and live skip reporting remain pending; the screen is not a live decision audit. |
| K5 | Opt-in experiment wired | Off default, never enabled by Auto; synthetic-only/misclassification copy; <=2dB combined; setter/diagnostics. Diagnostics sampled after process on capture thread and published as immutable UI snapshots. Music validation pending. |
| K6 | No phones available | `adb devices -l`: none attached; no /dev/kvm locally. No TECNO/LG CPU, battery, underrun or blind preference results. Owner's Amazon HD/Spotify lossless success retained without inferring source/DAC format. |

## Verification

- Website request: concise preview notes added to `docs/index.html` using existing classes;
  no design, screenshot, download, app-version or release changes. Unique IDs and local anchors
  checked. Live Pages currently publishes `ccr-f859b567-dgrdoj:/docs`, not the Codex branch.
  A push here does not publish the site. The owner's renewed instruction to upload the signed
  app and notes authorizes a narrow website-docs-only publication on its existing Pages branch;
  no app-branch merge, default-branch change, privacy edit or website redesign is included.
- After merging `62c43cf`, `claude-followup-build.log` passes the full Android command (46s),
  including both NDK ABIs; `claude-followup-core.log` passes 147 core tests with 0 failed checks.
  Generated policy matrix parity is clean. The earlier host JNI result below predates this merge.
- Final silent-centre copy/website-notes follow-up: `website-notes-build.log` passes the full
  Android command in 3m35s; 179 JVM tests have zero failures/errors. `website-notes-core.log`:
  147 tests, 0 failed checks; 11 Python tests pass and diff whitespace is clean. Hosted CI for
  this newest combined tree now passes, as recorded in the final checkpoint below; earlier runs
  alone would not certify a later core merge.

## Final source and owner signing checkpoint

Source `281e4d89f3428968ac4f4df2bb03657890a1c832`: push CI `37615943549` and PR CI
`37615950311` both pass all nine jobs. Every current PR check is SUCCESS; PR #6 remains open.
Final artifacts API33 `11481369252` / API34 `11481514315` have 41 routing, 13 detection,
4 production and 7 added native/matrix passes per API, with no failures. Native fixtures report
aligned max error 0, 19 rules and five diagnostics at 44.1/48/96k. The fixed layout commit also
passed all jobs in `37614319083`; actual normal/large-font PNGs were reviewed at all target widths,
including first-row knobs, Resolve/Auto and the rules page. No screenshots were added to the site.

The original Drive backup was recovered after the gate passed. Its actual keystore certificate
matches `release-cert.sha256`. Exact production artifact `11479767100` was re-signed without a
rebuild. `verify_release.sh` and the compiled forbidden-capability check pass. All 73 ZIP entry
contents and four native hashes are identical. Public payload/checksum records are under
`docs/releases/v0.5.6-beta.2*`. Local signing archive, keystore and recovery password files were
deleted immediately after signing/checks; no private material was uploaded to GitHub or CI.
The new prerelease retains app version 0.5.6/code 13 to preserve the tested APK; beta 1 is untouched.
Hosted release verification and live Pages publication must be confirmed separately.

Publication blocker: draft `v0.5.6-beta.2`, release ID `405749853`, was created, but the APK
asset upload failed with HTTP 401 at uploads.github.com, including one normal retry after a
GitHub connector read. api.github.com repository/release reads and writes work, so this is
specifically the release-upload authentication path; do not describe it as failed CI or signing.
The draft has zero assets and no published tag. No site-branch write was attempted; live Pages
still links the older 0.5.5 beta. Prepared site downloads remain the already-published beta 1 until
the new file is hosted and verified. No owner credentials are needed again: restore GitHub upload
authentication through the workspace/product settings, never paste a token into chat or Git.
After upload, attach the recorded checksum, publish the prerelease, require release-verify success,
then switch the site download links/copy to beta 2 and deploy only the prepared website files.
- Core Release tests: `/workspace/scratch/release-build-environment/core-build/eqcore_tests`;
  `/workspace/scratch/audio-quality-design/k0-core.log`: 142 tests, 0 failed checks.
- Android logs under `/workspace/scratch/audio-quality-design/`: `kotlin-integration-build.log`
  (4m08s), `final-integration-build.log` (3m22s), `current-tree-build.log` (3m28s),
  and final `prepush-build.log` (3m18s, BUILD SUCCESSFUL; 177 JVM tests).
- Follow-up `live-controls-build.log` (3m47s): debug/release/lint and 179 JVM tests pass. Core repeated:
  `live-controls-core.log`: 142 tests, 0 failed checks. Two new regression tests pin static mode/rate/
  quality while live controls change and require mixed-setting clips to be invalidated.
- Layout/harness follow-up `current-layout-build.log` (3m19s): full Android command passes
  on the exact current sources; `layout-followup-core.log`: 142 tests, 0 failed checks;
  11 Python assertions and shell syntax pass. New hosted screenshots are pending.
- Python screenshot/control assertions: 11 pass; shell syntax and diff whitespace pass.
- `AudioQualityLab` exercises actual Fast/Detailed JNI, aligned identity and exact extra N frames
  at 44.1/48/96k, five-field diagnostics and the 19-rule JSON. It runs in existing quality checks,
  including production UI checks, and separately in the screenshot suite. Final API33/34 CI
  artifacts contain its READY marker and zero aligned error; phone execution remains pending.
- Separate **host** JNI execution against the actual compiled Kotlin NativeEngine and unmodified
  JNI/core passes 44.1/48/88.2/96/176.4/192k constructor/delay/identity calls: aligned max error 0;
  diagnostics length 5; rule count 19. This is not Android execution. Harness and host shared library
  are in `/workspace/scratch/audio-quality-design/HostNativeSmoke.java` and `libeqjni.so`.
- UI matrix is invoked from `screens.sh`. Failure enters `interaction.txt`, rejected by the
  existing CI results gate. Workflow/core steps and established check counts are untouched.

## Remaining evidence and requests

Claude answered the four original requests in `REQUESTS_FROM_CLAUDE.md` at `62c43cf`:
Fast intentionally retains static response when mid/centre is silent (now explicit in UI), unmask
getters publish atomics, planner admission records gate outcomes, and high-rate analysis decimates
with anti-alias filtering to preserve window duration. Core regression tests cover these contracts.
The analyzer remains coarse below ~80 Hz; 44.1/48k sparse fixtures differ by up to 4.5 dB there.
Do not infer fine bass-note precision or full-band high-rate analysis from rate-family parity.
Kotlin gated-planner integration is still pending: publish the epoch and monotonic observation time
of each genuinely new feature snapshot, pass that provenance rather than timestamping a stale read,
and display actual skip outcomes. Confidence must have an explicit validity contract, not an invented
probability. Keep legacy behavior until this is tested. High-rate route changes require a
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
- API 29/30/33/35/36 compatibility smoke matrix passed in the PR run. The full
  API34 suite passed in both runs: routing 41 (including the established routing checks), detection 13, workspace 9, precision 8,
  quality 4, continuity 4, production 4, source filter 3, onboarding 8, fallback 3, and setup
  interaction 16 checks. The new native/matrix suite adds seven passes. Both API33 runs passed
  the native lab (aligned max error 0; rules 19; diagnostics 5) and both 320dp font cases, then
  the emulator went offline; overall CI is **not green**. Production on API33 was not reached.
  Artifacts reviewed: API34 `11478565100` and API33 `11477294725` from the PR run; PNGs inspected
  for 320dp normal/large fonts, 360/411dp large fonts, Resolve/Auto, first-row knobs and rules.
- Harness follow-up: one fixed 720x1600 framebuffer, density 360/320/280 dpi. Logical widths
  are exactly 320/360 and 411.43 dp (rounded target 411). Avoids repeated physical resizes suspected
  in the API33 disconnects; causality/recovery are not yet proven. Bounds checks use physical
  pixels, every six-case assertion remains mandatory, and failure capture survives restore errors.
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
