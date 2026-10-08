# Combined owner APK verification — 0.5.7 / code 14

This is a direct owner delivery on `ccr-c220a1e1-hikg7s`, with no PR, other-branch push or
published release. It combines every feature from the supplied spatial-test APK, audio-only
Recording mode, and player-connection recovery plus an optional shared-output experiment.
No Android permission is added. Commercial Apple/Amazon and physical-phone checks remain
pending in [PHONE_VALIDATION.md](PHONE_VALIDATION.md).

## Exact baseline and tested payload

The owner's `Svan-0.5.6-spatial-test.apk` has SHA-256
`d3d77a576bbfb6bf1e0d76204eed1c0e1f6f8bb5989ffa7711549ca2344a20cf`.
Its 73 ZIP payload entries all match the production fixture in CI 37655986331 at source
`6300e8278987c99038e136594e7f9fb7170f8c77`. That entire source branch was merged, preserving
Fast/Detailed/experimental Auto, Backing/Binaural, Bass Resolve, unmasking, capture-rate
negotiation/provenance, rule registry, protection, calibration/blind listening, presets,
manual/Svaresa curves, fine controls and existing detection setup/fallbacks.

The final implementation source is `25f2e1dbc748f82277e621f7521294492dce3c29`.
Its [CI 37702568531](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37702568531)
production artifact is `11518072664` (`Svan-production-test-build`). The exact production APK
was privately re-signed, preserving all 73 ZIP entries byte for byte. All four native library
payloads also match the owner's uploaded spatial-test APK byte for byte; core/JNI DSP has
not been changed by the recording/detection integration. Production scripted-command extras
are disabled.

## Private signing and delivery

`Svan-0.5.7-owner-signed.apk`, package `app.svan`, version 0.5.7 / code 14:

- APK SHA-256: `f32ea935247764fa899e177d418e321a80e8abc633f2f7abfab8685a8aed8081`.
- Signer certificate SHA-256: `9cb9daca3b49fbdd17683d45dfb069fa9c6d05e3733934795f92546fef696b0f`.
- Size: 5,601,519 bytes.
- Original private keystore alias certificate matches the uploaded APK and `release-cert.sha256`.
- APK v2/v3 signature, package/version, existing permission allowlist and four-library 16 KB
  ELF/ZIP alignment verification pass. Signing preserves every tested production payload entry.
- Private key and recovery credentials stay outside Git/CI and are not part of the delivery.

This identity and increasing version code permit an update over the supplied spatial-test
APK; actual owner-phone installation/settings retention remains a phone check. The APK and
public checksum/payload provenance are supplied directly to the owner, without creating a
GitHub release or changing Play signing.

## Verification

Local debug/release assembly, lint and all 211 JVM tests pass, including 17 ProofRecorder tests,
two multi-rate AAC-input conversion tests and seven lifecycle/policy tests. All 151 native
tests and 17 Python UI/control/host-meter assertions pass. CI release native and ASan/UBSan suites each
pass 151 tests; the threaded TSan subset passes four tests. Compatibility checks on API 29,
30, 33, 35 and 36 pass. CI Android build/lint/JVM, compiled permission policy,
preview/production APK and AAB alignment, positive release-verifier and negative certificate/
permission tests pass.

Both focused API 33/34 detection jobs pass all twelve checks with DUMP revoked and no shell
report access. Their actual host output at 1 kHz is -47.5725 dBFS flat and -53.5690 dBFS cut:
the measured difference is -5.9965 dB, for both per-player and unannounced shared-output EQ.
The cut persists after pause/resume and lost-effect recovery, is unchanged when an announced
source joins shared output, and returns to the flat level after shared output stops. Four
session replacements leave exactly one active route each; an unannounced replacement has no
cached attachment. Standard EQ-panel connection, unrelated CLOSE rejection, Engine B exclusion
and the output-change handler pass. Effect-panel/Done and shared-output screenshots were
reviewed on both APIs; the EQ curve and controls fit at 320x640.

These are emulated digital-output measurements through QEMU's built-in WAV driver, not a
physical output route or speaker measurement. Six host-meter tests cover numerical RMS,
channel inversion, live PCM16 frames with unfinished WAV sizes and rejected invalid captures.
The initial pre-insert Visualizer measurement was unsuitable for global effects; unsuccessful
PulseAudio initialization was also rejected rather than treated as a result. The final meter
reads newly appended host-output frames after Android effects and fails on missing/short/silent
data. Every original gate remains intact. A prior candidate's API 35 job failed downloading its
emulator before installing Svan; this candidate's API 35 smoke check passes.

CI 37702568531 finishes successfully with all eleven jobs passing. The complete API 33/34
artifacts are `11519678730` and `11520241275`; job logs and result files were inspected.
Each API passes 41 routing, 13 detection, nine workspace, eight precision, four quality,
four continuity, three source-filter, eight onboarding, three fallback, six detection-setup,
16 screenshot-interaction, seven audio-quality, four production, six recording and twelve
basic-detection checks, plus release smoke. No result file contains a FAIL. Every original
gate remains intact.

The full API 33 detection fixture measures -51.5824 dBFS flat and -57.6101 dBFS cut
(-6.0277 dB); API 34 measures -47.5725 and -53.5690 dBFS (-5.9965 dB). Both demonstrate
the same per-player/shared-output response, recovery and restoration. These full-suite
measurements are separate from the focused-job measurements above.

Recording checks verify nonempty MediaStore exports, readable AAC-LC 48 kHz stereo packets,
sample-exact sync trim, separate manual/settled segments, cue exclusion from plain Engine B
WAV and countdown frame-zero sync. The measured 2 kHz cue/source ratio is -131.56 dB
(API 33) / -130.75 dB (API 34) in plain output, versus -24.42 dB in the alignment copy.
This is a digital-file check, not evidence of physical speaker/headphone isolation.

Both APIs' final screenshots were reviewed: fixed recording clock and manual segment on
Hi-Fi/EQ, Sync/Mark/Stop controls, countdown, white flash, settings/RMS chart, standard
EQ panel/Done, shared-output warnings, boot and system glyph. Sound-screen samples at
320/411 dp with 1.5 font scale were reviewed alongside the passing scroll/layout assertions;
long content remains scrollable. Gold/charcoal/white branding is preserved. Physical camera
readability and acoustic timing remain pending.

Synthetic tests cannot establish Apple/Amazon commercial-player reliability, offload/HD paths,
TECNO/LG headphone routing, acoustic sync latency, VN import or LG Quad DAC behavior. The new
connections improve recovery where an actual session is available. Android does not expose a
new hidden session ID through anonymous callbacks. Shared-output EQ is explicit, off by default,
exclusive with Engine B/per-session EQ and requires audible verification on each owner route.
