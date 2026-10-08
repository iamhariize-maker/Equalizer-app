# Recording-mode verification — 2026-10-07

Verified implementation: `bbbb790ddb8add968d31e6ee51779d1bb86ea500`, on
`ccr-c220a1e1-hikg7s`. [CI 37678599236](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37678599236)
completed successfully in all nine jobs: core, Android, full emulator suites on API 33/34,
and compatibility smoke on API 29/30/33/35/36. This document records those downloaded logs
and artifacts; subsequent documentation-only changes do not change the tested app source.

## Builds and numerical tests

Local `assembleDebug :app:assembleRelease lintDebug testDebugUnitTest` passes, with 150 JVM
tests and zero failures, including 16 ProofRecorder tests. Lint reports zero errors and
42 warnings. The compiled permission allowlist and four native-library 16 KB alignment
checks pass. CI also passes its preview/production build and release-verifier positive
and negative cases. No manifest, permission, native DSP or signing configuration changes
were made. `offerDry`, `commitWet` and ring `copyIn` match the prior implementation textually.

Native tests: 98 locally; CI reports 98 release, 98 ASan/UBSan and one threaded TSan test,
all with zero failed checks. The new recorder tests exercise exact sync positions including
uncommitted dry input, exact 16/24-bit tail payloads and WAV headers, first signal at 1.5 s,
PCM conversion/clamping, TPDF statistics, per-segment matching within 0.05 dB, cue isolation,
silence and peak-limited matching, late/duplicate/manual markers, countdown at frame zero,
optional-export absence and the segment cap with independent syncs. The original six tests
remain green. The synthetic test producer is paced to avoid overflowing the bounded ring
on a loaded CI host; it still checks the full expected duration and zero dropped frames.

## Emulator results

Every row below has zero FAIL lines in both downloaded result artifacts.

| Result set | API 33 PASS | API 34 PASS |
|---|---:|---:|
| Existing audio routing (`e2e_results.txt`) | 39 | 39 |
| New recording (`recording/results.txt`) | 6 | 6 |
| Detection (`detection/detection.txt`) | 10 | 10 |
| Release quality (`quality/results.txt`) | 4 | 4 |
| Precision controls (`precision/results.txt`) | 8 | 8 |
| EQ workspace (`workspace/results.txt`) | 9 | 9 |
| Production commands (`production/results.txt`) | 4 | 4 |
| Onboarding (`onboarding/results.txt`) | 8 | 8 |
| Detection/onboarding (`detection/onboarding.txt`) | 6 | 6 |
| Screenshot interactions (`screens/interaction.txt`) | 16 | 16 |

`recording_mode.sh` runs the capturable testsource's 1 kHz tone through Engine B, uses actual
UI Sync/Mark now/Stop and changes EQ. It verifies all ten requested files in MediaStore,
nonzero length and byte identity with the finished private files. AAC MediaExtractor evidence
identifies AAC-LC (object type 2), 48 kHz stereo, positive duration and readable encoded packets.
The codec is configured for 256 kbps; this is a requested rate, not a measured average bitrate.
Trimmed WAV payloads equal the full WAV tails at the reported first sync frame exactly.
Manual_demo and a settled EQ curve change produce separate measured segments. The second
countdown recording reports `syncFrames: [0]` and `syncSeconds: [0]` on both APIs.

| First recording measurement | API 33 | API 34 |
|---|---:|---:|
| First sync frame | 204544 | 219648 |
| First sync seconds | 4.261333333333333 | 4.576 |
| Full duration seconds | 21.936 | 23.514666666666667 |
| Dropped frames | 0 | 0 |
| Export bits | 16 | 16 |
| First signal seconds | 0 | 0 |
| Plain tail: 2 kHz / 1 kHz ratio, dB | -132.56 | -118.92 |
| Cue copy: 2 kHz / 1 kHz ratio, dB | -24.42 | -24.42 |

The harmonic ratios are calculated over up to one second of each trimmed file in this
synthetic tone test, using the script's integer-cycle Fourier sums. They demonstrate that
generated clicks are in the disposable alignment copy and absent from the plain test
recording. They are not noise-floor, acoustic latency, headphone routing or sound-quality
measurements. JVM tests additionally check cue isolation and untouched plain payloads.

## Screenshot review and remaining phone work

Downloaded `e2e-results-api33` and `e2e-results-api34` recording screenshots were viewed,
including originals at phone resolution: large fixed clock, gold segment label, Sync/Mark
now/Stop, manual label dialog, countdown, and the same panel while EQ is visible. Controls
and clock fit; long automatic labels truncate within their fixed space and remain complete
in the report. The saved settings-effects chart includes processed-minus-dry RMS values.
API 34's `recording/sync-flash-attempt.png` captures the white flash; API 33's capture occurs
after it, so the latter does not establish visual flash behavior. Reviewed ordinary boot,
EQ and Android splash images show the one-line boot name, EQ's 9 and gold स्व icon.

These emulator images do not establish readability in the LG camera video. The TECNO/LG
checks in [PHONE_VALIDATION.md](PHONE_VALIDATION.md) remain pending: physical readability,
flash/click/camera timing, speaker-only cue with headphones, live A/B correspondence and
actual VN 16/24-bit WAV/M4A import. No speaker latency or one-video-frame agreement is claimed.
CI preview APKs use a different signing identity from the owner beta; use an original-key
owner-signed update for those checks, following [RELEASE_SIGNING.md](RELEASE_SIGNING.md).
