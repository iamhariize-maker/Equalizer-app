# Release readiness

The current APK is a debug-key-signed, R8-minified preview. Do not submit it to a
store as a production release. The owner must retain a signing keystore and its
passwords securely; neither keys nor passwords belong in this repository.

Build a production-signed package with JDK 17 and the SDK/NDK in AGENTS.md:

```
SVAN_KEYSTORE=/secure/path/svan.jks \
SVAN_STORE_PASSWORD=<secret> SVAN_KEY_ALIAS=<alias> SVAN_KEY_PASSWORD=<secret> \
./gradlew -PsvanProduction=true :app:assembleRelease :app:bundleRelease
```

The production flag refuses to build if required signing inputs are missing.
Without that flag, assembleRelease remains a clearly labelled preview signed
with the environment's debug key. Store uploads and signing-key creation have
not been performed. Production key ownership and app licence are owner decisions.

Before release:

- Retest arm64 on the TECNO LH7n, especially YouTube Music over Bluetooth and
  30-minute screen-off playback; follow PHONE_VALIDATION.md.
- Check app detection without ADB. DUMP is an optional development enhancement;
  users with non-broadcasting players may have no processable sessions.
- Confirm targetSdk 35 foreground-service declarations with Play policy: Engine A
  keeps system audio effects active under mediaPlayback; Engine B uses
  mediaProjection and a fresh user grant. Record the permission/stop flow and
  justify each use in Play Console. Eligibility is not guaranteed by emulator CI.
- Publish an owner-approved privacy-policy URL/contact, then complete Data Safety
  and foreground-service declarations from the actual implementation.
- Decide the licence for original code. Third-party MIT/OFL attributions remain.
- Review dependency upgrades as a separate compatibility change with full CI;
  no bulk upgrade is included in the routing/audio correctness fix.
- Confirm backup behavior across OEMs. Manifest allowBackup=false is intentional;
  saved app settings are not a promised backup/export mechanism.
