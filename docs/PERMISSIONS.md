> **Removed 7 October 2026:** optional player recognition (notification access) is gone. Play Protect
> flagged it as a financial-fraud risk in sideloaded builds. Svan declares no notification listener.

# Svan permissions after 0.5.2

Notification-listener access, SMS access, accessibility services, device-admin
services and installing other packages are absent. Player recognition using
notifications was removed by the owner's explicit request. The compiled release
manifest is checked in CI, not just the source manifest.

| Permission/capability | Current purpose and limit |
| --- | --- |
| MODIFY_AUDIO_SETTINGS | Attach Android effects to real audio sessions. |
| DUMP | Optional Shizuku-authorized enhanced discovery. Android grants broad report access; Svan's calls read only audio and media.audio_flinger. It is retained because the user reports working music detection. |
| Shizuku API/provider | One fixed DUMP grant to Svan after explicit authorization. No arbitrary command or privileged helper launch. The provider is protected by Android's INTERACT_ACROSS_USERS_FULL permission. |
| RECORD_AUDIO / MediaProjection | Playback capture and measurement probes, with Android consent. RECORD_AUDIO is broad; the implementation uses playback capture rather than microphone input. |
| Foreground-service permissions | Keep playback/system effects or authorized capture running visibly. |
| POST_NOTIFICATIONS | Display Svan's own service notifications. Does not permit reading other apps' notifications. |
| SYSTEM_ALERT_WINDOW | Optional Svaramanas floating controls. A broad special permission; not needed for core EQ and not enabled automatically. |
| INTERNET | On-demand AutoEq measurements/index/targets from GitHub via HTTPS. No analytics or report/audio upload code found in the reviewed source. |
| Scoped package visibility | Resolve music-player names/UIDs. No QUERY_ALL_PACKAGES. |

This is a source/manifest review, not an independent security audit, Google approval
or assurance about all future builds. The shared public preview signing key is
for development updates only and provides no exclusive publisher identity. Use a
private production key and Play signing for a store release.

Google documents [sensitive-permission installation blocking](https://developers.google.com/android/play-protect/warning-dev-guidance)
for internet-sideloaded apps in supported markets. Notification-listener access
in 0.5.1 matches one of the listed criteria and is a likely explanation for the
reported financial-fraud warning. The warning's exact text and the replacement's
Play Protect verdict on the TECNO remain unverified. Keep protection enabled;
if blocked, stop installing and investigate/appeal through Google's developer flow.
