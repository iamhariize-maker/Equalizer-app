# 0.5.6 public-beta investigation

The owner authorized investigation and fixes after supplying a working-phone photograph
and preview APK. Work remains on PR #6's branch.

## Supplied phone APK

`Svan-preview.apk` is package `app.svan`, versionName `0.5.6-continuity-preview`,
versionCode 13, with the shared preview signer. Its SHA-256 is
`c1ade065d5a3846383dd35c62ec26ea6d7c26b9fd3c9406d98f77bb4d24e4d14`.
The compiled capability policy and four-library 16 KB ELF/ZIP checks pass.
Its bytes and ZIP payload differ from six inspected recent CI preview artifacts, so
its exact source/run is not established. This does not negate the owner's successful
phone observations; it is why release signing must use the separately tested production
artifact rather than this preview. The final release's source, hash and signer require
their own provenance record.

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
The owner confirms working playback and successful BHIM/GPay use on the current phone/preview
after uninstalling Shizuku, with Developer options still enabled. The owner did not need
to disable Developer options for those two payment apps.
That does not establish all payment-app/phone combinations or the final signed beta.

Local validation: all 105 core tests, 158 JVM tests and nine Python tests pass.
Debug/release preview assembly and lintDebug pass. The built preview has a valid v2 signature,
passes the compiled forbidden-capability policy and passes all four native ELF and APK ZIP
16 KB alignment checks. The detection script passes Bash syntax checks.

CI for the corrected source is pending when this investigation record is first committed.
Do not sign until both emulator suites and every other required CI job pass.

## First corrected CI run: 915dbf4

[Source run 37569860623](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37569860623)
passed core, Android and all five compatibility jobs. Both full emulator artifacts prove
13/13 detection checks: the real keep-enhanced button granted app DUMP, Shizuku was
uninstalled, and the hidden player measured exactly -6.3 dB on APIs 33 and 34.
Routing, workspace, control, continuity, quality and production-mode checks also pass.

Both full jobs still fail one onboarding assertion: capture peaks were below the fold.
The longer, corrected grant explanation pushes the routed-status card down; the first
hierarchy contains the routed player but clips its readings. Each immediately subsequent
`connected-audiophile.png` shows the real peaks after a single scroll (-12.0 dBFS input,
-24.0 dBFS output). Continuous logs also retain live capture measurements. This establishes
a viewport error in the assertion, not missing telemetry. The driver now scrolls while
seeking the actual peaks label, keeps the assertion and saves its hierarchy/screenshot.
The next complete green run is still required; nothing has been owner-signed or published.

Public documentation was also corrected: the privacy notice/static copy and README no
longer describe the removed notification-recognition feature, older fallback notes are
marked historical, and migration/phone evidence describes 0.5.6 and BHIM/GPay accurately.

## Final corrected source: 64b5ae0

Both full runs are green: source push 37573374711 and PR 37573378002, nine jobs each.
Final artifacts and live screenshots confirm all suites, including 13 detection and
41 routing checks per API, real DUMP grants, -6.3 dB after Shizuku removal and visible
capture peaks. [The final CI record](CI_VERIFICATION_0.5.6.md) identifies the exact production
artifact and payload retained for signing. No key was found through connected Drive metadata
searches; owner signing/publication remain blocked on the original backup's location.

The owner subsequently supplied the backup's Drive link. Its certificate matched the
original before signing, and the exact CI production APK was re-signed without rebuilding.
All 73 entry payloads remain identical. Local release/capability verification passes.
The private backup/keystore were deleted immediately; credentials stayed in memory only.
See releases/v0.5.6-beta-payload.json.

The upload workaround added a narrowly scoped staging workflow, followed by fresh green
push/PR CI on `e24e4f0` (37580842075 / 37580846530). Staging 37580842157 checks the original
owner signer and all 73 payload entries against fresh CI, then attaches only public assets.
The beta is published as `v0.5.6-beta.1`; hosted verification 37585033909 and 37585033892
passes both jobs each. A public download matches the signed candidate byte for byte.
PR #6 remains unmerged and the default branch unchanged. Remaining phone tests are listed
in [the final evidence record](CI_VERIFICATION_0.5.6.md#published-public-beta).
