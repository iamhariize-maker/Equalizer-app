# Onboarding verification record

This work changes onboarding, detection UX and status reporting after `a603146`, on
`ccr-f859b567-dgrdoj`. It does not publish a release, enable Pages or submit to Play.
The original owner-signed APK predates the new flow and remains unchanged.

## Compared builds

- Baseline: `3008eb57912540183219b6a908190e82556def50`, whose app/audio source is still
  `a603146`. [Baseline CI](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37414104556).
- Onboarding: `49f33488efb7d3a432dad426d91fde99821f8825`.
  [Onboarding CI](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37414766431).
- Follow-up changes add engine/Android-version fields to the local summary, use a friendly
  label for unresolved identities, clarify one compatibility row and retain failure screenshots.
  They do not change audio algorithms or measured-check criteria. Final branch CI is linked in
  the delivery report; this record's screenshots and paired measurements identify their own builds.

## Native payloads and source boundary

All four native files in the two CI production-test APKs are byte-identical. These hashes
are **individual native-library hashes**, not APK hashes or signing certificates:

| Native file | SHA-256, identical before/after |
| --- | --- |
| `lib/arm64-v8a/libeqjni.so` | `17fafee7f334d10d3f9fcfc5eaec40d0e9bc5b6b74d3e778f6d023fb75d6b9d5` |
| `lib/x86_64/libeqjni.so` | `f665b645dbeda1dc0c59cbb8bf26169eef7a345da0980ec8edd4bfa40a559307` |
| `lib/arm64-v8a/libc++_shared.so` | `92ee8da641b969254cc8723f64aa115d36c8ffa87115e38bc9cb89e3eefee839` |
| `lib/x86_64/libc++_shared.so` | `128bb6e63a08bbd21fba7128223fb6e1016afd76dab9cbc09d9aaf4b0400f4e2` |

`git diff a603146 -- core android/app/src/main/cpp` is empty. The JNI/audio engines,
SessionRouter, playback discovery, fixed Shizuku grant, repository/model processing code,
manifest and version/signing configuration also remain unchanged. The 39 measured audio
checks are byte-for-byte unchanged; `e2e.sh` adds a separate UI-test prelude.

The existing bootstrap enabled automatic tonal settings. Genuinely fresh installs now use
the existing Flat reset, with 0 dB preamp and System effects only. Saved settings, updates
and restores are preserved. This is a requested default correction, not a claim that initial
settings were already identical. Paired audio measurements use the same explicit test reset.

## Checks and screenshots

Local final checks pass: 98 core tests, 132 JVM tests, `assembleDebug`, `:app:assembleRelease`,
`lintDebug`, Bash syntax, ShellCheck for new scripts and actionlint. The verifier accepts a
real CI production-test fixture and rejects both a wrong certificate and an extra permission.
The owner APK verifies against the public owner certificate on SDK 36.0 and 36.1.

Both compared CI runs passed all nine jobs: core/sanitizers, Android build/lint/unit verification,
API 29/30/33/35/36 compatibility smoke tests and full API 33/34 suites. Each full onboarding suite
passed the unchanged 39 audio, 10 detection, nine workspace, eight controls, four quality and four
production checks, plus eight first-run/prompt checks and five live wizard checks. No failure was
present in their result artifacts.

| API | Unchanged audio checks | Paired median levels | Largest median difference |
| --- | --- | --- | --- |
| 33 | 39 pass before and after | 34 | 0.0 dB |
| 34 | 39 pass before and after | 34 | 0.0 dB |

All reported median levels are identical **at 0.1 dB precision**. Two min/max endpoints vary by
0.1 dB (API 33's quiet-listening bass maximum and API 34's Engine B minimum). Do not call the raw
samples, timestamps or logs identical. [Paired values](verification/onboarding-audio.json) retain
every measured median and identify the compared commits; criteria/tolerances were not changed.

Inspected native 320 × 640 captures include live first run, anonymous unreachable/System-effects
status and real grant/finish, plus all 15 wizard/status fixtures. Live success retained the warning
for USB on/Developer options unknown. Fixture review found stale scroll/clipped headings and
inaccurate error/progress button labels and a clipped API 33 footer; the follow-up resets/clips the
panel viewport, handles full-screen insets explicitly and uses matching presentation labels.
UI assertions now check all fixture banners, visible status headings and footers,
Retry and disabled progress controls. Final CI must exercise those fixes and its images must be
inspected too. Representative provenance-labelled captures are in [SETUP.md](SETUP.md).

## Limits

CI uses synthetic players and disposable signing identities. It does not establish commercial-player,
Bluetooth, payment-app, TECNO/LG or Android 11–15 OEM compatibility. Real wireless pairing,
settings shortcuts/access, background survival, same-key update/grant retention and listening
feedback still need phone checks. UI fixtures are labelled and do not prove real grants or
debugging-off transport. See [setup](SETUP.md) and [phone validation](PHONE_VALIDATION.md).

Release-event checksum upload remains unexecuted until the owner publishes an authorized release
with the workflow available on the default branch. No owner key is present in CI or this repository.
