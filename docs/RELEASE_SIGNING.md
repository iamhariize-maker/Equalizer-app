# Private signing and the Play route

The owner has never uploaded Svan to Play Console. A new RSA-4096 PKCS12 upload key was
created privately on 5 October 2026. The key and its passwords are outside the repository,
never CI inputs, and must be downloaded and backed up by the owner. The public preview key
is not a production identity. A brand certificate label does not certify a legal identity.

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

## First Play submission

Create the Play Console app using package `app.svan` (availability must be confirmed in the
console). Enroll in Play App Signing and let Google generate/protect the app signing key.
Upload the private-signed AAB; this local key becomes the upload key. Keep its encrypted
keystore and recovery credentials in two secure locations. The public PEM is safe to share
with Google when a certificate is requested.

Google's store signing certificate will differ from the upload-signed sideload APK, requiring
another settings migration for that APK. Future Play updates use the same store identity and
increasing version codes. Signing alone does not mean approval: the privacy policy, Data
safety answers, foreground-service declarations, MediaProjection explanation, licence choice,
and required testing still need completion before publishing. This work does not publish the
app or claim to resolve an earlier Play Protect finding.

NDK 27 builds enable `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES`. Release gates check every native
ELF load segment in APK/AAB as well as uncompressed APK ZIP offsets. ZIP alignment alone is
insufficient for the Play 16 KB page-size requirement. This also supports existing 4 KB systems.
