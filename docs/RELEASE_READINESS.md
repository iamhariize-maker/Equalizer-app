# Svan 0.5.6 public-beta release readiness

The release target is an owner-signed **GitHub prerelease**: package `app.svan`,
versionName `0.5.6`, versionCode `13`, minimum API 29, target API 36.
`0.5.6-continuity-preview` uses the shared preview signer and enables preview automation.
Re-signing that preview cannot turn it into the tested production-mode APK.

Original Svan code is **All rights reserved**; third-party notices remain applicable.
See [the release prompt](CODEX_RELEASE_PROMPT.md), [private signing](RELEASE_SIGNING.md)
and [the investigation](RELEASE_INVESTIGATION_0.5.6.md).

Current status: [source 64b5ae0 passes every required CI job](CI_VERIFICATION_0.5.6.md).
The original owner key has now been recovered and matched privately. The signed candidate
passes release verification and retains every tested ZIP payload entry.
[v0.5.6-beta.1 is published](https://github.com/iamhariize-maker/Equalizer-app/releases/tag/v0.5.6-beta.1).
Fresh staging-head CI and both hosted-release verification runs pass; the public download
matches the signed candidate byte for byte. See [the beta notes](releases/v0.5.6-beta.md)
and [publication evidence](CI_VERIFICATION_0.5.6.md#published-public-beta).

## Required before publication

- All CI jobs on the release source pass, including API 33/34 emulator jobs, the five
  compatibility jobs and 13 detection checks per full emulator. Read logs and screenshots.
- Use the exact `Svan-production-test-build` APK exercised by the green run.
  Production mode rejects preview automation commands. Do not rebuild the delivery payload.
- Recover the original owner key privately and match [release-cert.sha256](release-cert.sha256).
  Never generate a replacement, put owner credentials in Git/CI or publish a CI test signer.
- Verify one signer, package/version, compiled permissions, forbidden-capability policy,
  16 KB ELF/ZIP alignment and unchanged application payload. Record hash and provenance.
- Publish as a prerelease, require `release-verify` to pass, and withdraw or return to draft
  if verification fails. The owner merges PR #6; release work does not merge it.

## Phone evidence and limits

The owner's photo shows enhanced detection already enabled, Spotify routed through the
Audiophile engine and live capture peaks. That supports that phone/playback session.
The owner confirms BHIM and GPay work on that phone with the current preview after
uninstalling Shizuku, while Developer options remain enabled. Turning Developer options
off was not needed for those two apps in that test.
It does not replace the fresh grant, no-Shizuku routing or measured-response checks.
The supplied preview did not match six inspected recent CI preview artifacts.

Broader player/OEM/output compatibility, update/grant retention, Bluetooth, other payment-app
and phone combinations, final signed-beta behavior, and listening preference need separate tests.
Export settings before updating. An owner-signed 0.5.5 to 0.5.6 update should work;
verify it on a phone before promising it. Preview-to-owner signer migration requires
export/uninstall/install/restore. Never turn off Play Protect to install.

## Separate Play submission work

This beta is not a Play submission. Play signing-identity selection, public privacy
contact/policy completion, an in-app privacy entry, Data Safety answers and foreground-service
demos remain open in [the Play drafts](play/LISTING.md). Emulator checks do not establish
Play approval, real-phone compatibility or malware certification.
