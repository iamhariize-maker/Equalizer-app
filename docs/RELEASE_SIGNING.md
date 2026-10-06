# Private signing and the Play route

The owner has never uploaded Svan to Play Console. A new RSA-4096 PKCS12 upload key was
created privately on 5 October 2026. The key and its passwords are outside the repository,
never CI inputs, and must be downloaded and backed up by the owner. The public preview key
is not a production identity. A brand certificate label does not certify a legal identity.

On 6 October 2026 the owner recovered the original backup and authorized private signing and
GitHub publication of the tested `fbc2ff6` production payload. No new key was generated.
The backup was confirmed restricted to the owner and used outside Git/CI; no recovery credentials
are release assets. The resulting APK retains the certificate below. See the
[beta notes](releases/v0.5.5-beta.md) for its actual file hash and payload verification.

This release compiles/targets API 36 using AGP 8.9.2 and Gradle 8.11.1, retaining minimum API 29.
Google's [current target requirement](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
requires API 36 for new mobile-app submissions from 31 August 2026. CI adds Android 16/API 36
compatibility to the existing API 29/30/33/35 matrix and full Android 13/14 production UI/audio tests.
The owner specifically targets an LG V60 on Android 13; the actual LG Quad DAC and OEM service
behavior must still be checked on that phone. No automatic quality-mode escalation is added.

Build with JDK 17 and the Android SDK/NDK described in AGENTS.md:

```sh
# Supply these privately through your build environment; do not commit their values.
# SVAN_KEYSTORE, SVAN_STORE_PASSWORD, SVAN_KEY_ALIAS, SVAN_KEY_PASSWORD
cd android
./gradlew -PsvanProduction=true :app:assembleRelease :app:bundleRelease
```

Production builds use version name `0.5.5` and disable the exported Activity's scripted test
commands. The launcher remains exported so Android can open the app normally. No permission
or notification listener is added. CI exercises the production R8 APK with a disposable
test key: launch, rejected command extras, settings migration UI, offline quality probes,
and blind listening. That test key is not used for owner downloads.

For artifact delivery, the exact tested production APK may be re-signed with the owner's
private key, preserving every ZIP entry. The tested AAB is similarly signed after removing
only its disposable JAR signature. Verify signatures, package/version, permission gate,
16 KB ZIP and native ELF load-segment alignment, and equal payload digests before delivery. Owner passwords must be passed
through environment variables, never command arguments or logs.

## Moving from previews

Install the latest **preview** over the previous preview first. In Presets, choose **Export
settings** and save the JSON outside Svan. It includes manual EQ, Svaresa choices, audio
settings, user presets, and the active private calibration curves. It excludes recordings,
listening votes, Android permissions, Shizuku grants, and signing credentials. Restore checks
the complete file and calibration hashes before changing settings.

Android cannot update the shared-preview certificate to the private certificate. After
checking that the backup exists, uninstall the preview, install the private-signed APK, then
choose **Restore settings**. Android capture consent, background preferences and the optional
music-detection setup may need to be granted again. Never disable Play Protect to install.

## Owner decision required: Play signing identity

**Undecided. The owner must select A or B before the initial upload/distribution.** This preparation
does not enroll the app, import a key, change signing configuration or submit a bundle.

| Option | Consequences | Owner action |
| --- | --- | --- |
| A — Google-generated app signing key | The existing owner-signed sideload APK and Play APK have different certificates. Switching from this direct build to Play requires settings export/uninstall/install/restore and grants again. | Keep the existing key as the upload key; let Google manage the store key. For future direct distribution under the Play identity, download a Play-signed universal APK from Console instead of publishing an upload-key-signed APK. |
| B — import the owner's existing key when setting up the first upload | Play and privately signed GitHub APKs can share the current signing identity. Same package, compatible signing history and increasing versionCode are also needed; key rotation/Android-version differences and actual cross-channel updates require testing. | Privately follow Console's PEPK key-transfer instructions, supplying the existing private key (not just its certificate). Compare the Play app-signing certificate with docs/release-cert.sha256. Register a separate upload key if desired/recommended and keep both backups outside Git/CI. |

The owner APK uses `CN=Svan Upload Key`, RSA-4096, certificate valid 2026-10-05 through 2054-02-20,
and APK v2 signing only. Its certificate SHA-256 is
`9cb9daca3b49fbdd17683d45dfb069fa9c6d05e3733934795f92546fef696b0f`.
The label "Upload Key" does not assign its future Play role. Use [release-cert.sha256](release-cert.sha256)
and [verify_release.sh](../android/scripts/verify_release.sh) to identify the direct APK.

[Current Google guidance](https://support.google.com/googleplay/android-developer/answer/9842756?hl=en-EN)
also permits changing the initial default before open-testing/production rollout. Decide during first
upload setup rather than relying on a later change after testers have installed another identity.

## First Play submission after that decision

Create the Play Console app using package `app.svan` (availability must be confirmed in the
console). Configure Play App Signing according to the owner's A/B choice above.
Upload the appropriately upload-signed AAB. Keep the encrypted
keystore and recovery credentials in two secure locations. The public PEM is safe to share
with Google when a certificate is requested.

With Option A, Google's store signing certificate differs from the current direct APK, requiring
another settings migration. Option B still needs real update verification. Future Play updates use the store identity and
increasing version codes. Signing alone does not mean approval: the privacy policy, Data
safety answers, foreground-service declarations, MediaProjection explanation,
and required testing still need completion before publishing. This work does not publish the
app or claim to resolve an earlier Play Protect finding.

NDK 27 builds enable `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES`. Release gates check every native
ELF load segment in APK/AAB as well as uncompressed APK ZIP offsets. ZIP alignment alone is
insufficient for the Play 16 KB page-size requirement. This also supports existing 4 KB systems.
