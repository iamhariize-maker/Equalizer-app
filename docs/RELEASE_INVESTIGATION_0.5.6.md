# 0.5.6 public-beta investigation

The owner authorized investigation and fixes after supplying a working-phone photograph
and preview APK. Work remains on PR #6's branch.

## CI evidence

At `10ff806a374e7939d9fc61b9abb020a78cd2f62a`, core, Android and compatibility passed.
Both detection suites recorded 11 PASS and 2 FAIL: the keep-enhanced button was missing
or its grant failed, then hidden-player routing failed after Shizuku removal.

- [API 33 job](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37563552979/job/112608376377)
- [API 34 job](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37563552979/job/112608376423)

API 34's `status-audiophile-live.xml` shows the keep-enhanced button at
`[56,310][264,358]`. The subsequent capture-status screenshot deliberately scrolls down.
Selecting the already-selected Hi-Fi tab preserves that offset. The grant check then
swipes upward through the page, moving farther down rather than returning to the top.
Its failure screenshot/hierarchy show the bottom of Hi-Fi; there is no `dump grant:`
attempt and app DUMP remains false. This establishes a navigation problem before the
grant was exercised. It does not prove that the grant itself works.

## Corrections

The driver returns to the visible music-detection section before seeking the real button,
saves the pre-tap hierarchy/screenshot, and distinguishes a missing control from a failed grant.
The selector rejects plain headings, disabled parents and offscreen labels; it accepts
Compose's already-selected tab, which intentionally has no click action.
Five Python regression tests cover these cases. CI runs them alongside the four setup tests.

The real button remains the only trigger. CI still requires `DUMP: granted=true`,
uninstalls Shizuku, discovers the hidden player and measures -6.3 ±1 dB.
All 13 detection and existing routing/workspace/control/quality/production checks remain.
No permission is pre-granted, and no DSP, routing threshold, permission, signing configuration
or versionCode change is needed for this initial correction.

The help card removes the unverified payment-app guarantee and explains grant revocation.
The owner confirms both working playback and working payment apps on the current phone/preview.
That does not establish all payment-app/phone combinations or the final signed beta.

Local validation: all 105 core tests, 158 JVM tests and nine Python tests pass.
Debug/release preview assembly and lintDebug pass. The built preview has a valid v2 signature,
passes the compiled forbidden-capability policy and passes all four native ELF and APK ZIP
16 KB alignment checks. The detection script passes Bash syntax checks.

CI for the corrected source is pending when this investigation record is first committed.
Do not sign until both emulator suites and every other required CI job pass.
