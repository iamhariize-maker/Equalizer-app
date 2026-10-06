# Compatibility evidence

The app reads the bundled [compatibility.tsv](../android/app/src/main/assets/compatibility.tsv).
Rows record observations, not a promise that a player works on every phone or route.
Synthetic AudioTrack fixtures are not commercial players. New onboarding, Android 11–15 OEM
behavior and commercial-player/output combinations remain real-phone checks.

| Player | Works without setup | Music detection | How verified | Limits |
| --- | --- | --- | --- | --- |
| CI capturable tone (session broadcast) | Yes (synthetic) | Not needed (synthetic) | CI fake player | AudioTrack fixture; API 33/34 CI. No commercial-player claim. |
| CI capturable tone (no broadcast) | No (synthetic) | Yes (synthetic) | CI fake player | Fixed DUMP grant and measured routing in detection_release.sh on API 33/34. |
| CI capture-blocked tone | System effects (synthetic) | Not needed with broadcast | CI fake player | Engine B capture is blocked; existing routing tests exercise Engine A fallback. |
| YouTube Music | Unverified | Unverified | Owner phone | Earlier TECNO Android 14 report: capture delayed/doubled over Bluetooth. New onboarding and 0.5.5 routes unverified. |
| Apple Music | Unverified | Unverified | Owner phone | Earlier TECNO report: once detected on IM4, later missing on a wired route. Not a compatibility guarantee. |
| Spotify | Unverified | Unverified | Unverified | No real-device/player validation for this beta. |
| Amazon Music | Unverified | Unverified | Unverified | No real-device/player validation for this beta. |
| Neutron | Unverified | Unverified | Owner phone | Earlier TECNO report: no session detected. Direct/output modes and this beta unverified. |

Synthetic evidence: [39 routing checks](../android/scripts/e2e.sh),
[real grant and 10 audio detection checks](../android/scripts/detection_release.sh).
Phone observations: [PHONE_VALIDATION.md](PHONE_VALIDATION.md).
Update the bundled file and this table together; include the build, phone, route and evidence
before promoting an unverified entry. An app package in the manifest is not validation.
