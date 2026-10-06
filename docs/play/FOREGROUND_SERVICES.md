# Foreground-service declarations draft — not submitted

The current manifest declares `mediaPlayback` on SystemEqService and `mediaProjection` on
CaptureService ([manifest:104](../../android/app/src/main/AndroidManifest.xml#L104),
[manifest:133](../../android/app/src/main/AndroidManifest.xml#L133)).
No FGS type/permission is changed here. [Play guidance](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)
requires functionality, interruption/defer impact and a demonstration link for each declared type.
TODO owner: record accessible real-build demos and enter the actual links in Console.

## mediaPlayback — system effects

Draft functionality: the user starts the background system equalizer in Hi-Fi. Svan maintains
Android effects on eligible music-player sessions while the user listens outside Svan. It is an
equalizer for other players, not a claim that Svan provides its own streaming catalogue.

Flow: **Hi-Fi → Start system equalizer**, play an eligible source, inspect its session/engine,
leave Svan and show **Svan equalizer active**. Stop with **Stop all processing** in Hi-Fi or the
notification's **Stop** action. Evidence:
[AudiophileScreen.kt:84](../../android/app/src/main/java/app/svan/ui/AudiophileScreen.kt#L84),
[SystemEqService.kt:64](../../android/app/src/main/java/app/svan/SystemEqService.kt#L64),
[SystemEqService.kt:139](../../android/app/src/main/java/app/svan/SystemEqService.kt#L139).

Draft impact: deferral leaves the selected EQ inactive when listening begins; interruption ends
processing and changes the selected listening experience. No OEM screen-off survival guarantee.
Demo: show deliberate start, real eligible player/session, modest EQ change, notification,
background use and Stop. Capture actual results rather than equating a running service with
processed music.

Policy risk: system-effect maintenance on another player's stream is not identical to a player
continuing its own playback. Explain the real task and have Play review the selected use case;
matching a manifest type is not approval. Do not switch to a different type just to evade review.

## mediaProjection — optional Audiophile engine

Draft functionality: after user initiation and Android capture consent, Svan captures eligible
selected playback, processes it locally and replays it. Sources that opt out are not bypassed.

Flow: **Hi-Fi → Start audiophile engine**, grant RECORD_AUDIO/notifications as required, accept
Android's MediaProjection prompt, show non-silent input/output and a routed player. The service
has a **Svan capture engine running** notification. Stop with **Stop audiophile engine** in Hi-Fi
or Android's system capture controls; projection revocation stops the service. The current capture
notification has **no dedicated Stop action**—do not describe one in the video.
Evidence: [MainActivity.kt:282](../../android/app/src/main/java/app/svan/MainActivity.kt#L282),
[MainActivity.kt:294](../../android/app/src/main/java/app/svan/MainActivity.kt#L294),
[CaptureService.kt:65](../../android/app/src/main/java/app/svan/CaptureService.kt#L65),
[CaptureService.kt:90](../../android/app/src/main/java/app/svan/CaptureService.kt#L90),
[CaptureService.kt:336](../../android/app/src/main/java/app/svan/CaptureService.kt#L336),
[AudiophileScreen.kt:120](../../android/app/src/main/java/app/svan/ui/AudiophileScreen.kt#L120).

Draft impact: deferral prevents capture-based processing starting. Interruption stops that engine;
demonstrate source continuity and stopped-state behavior rather than promise seamless fallback
on every phone. Demo: show explanation, Android permissions/consent, eligible player/signal,
background notification/system indicator, in-app Stop and system revocation. Show denied consent
does not start capture. No commercial-player compatibility or latency guarantee is claimed.

Policy risk: justify audio-only playback capture under MediaProjection and demonstrate explicit
user consent/stop controls. Confirm prominent in-app disclosure meets policy expectations; the
privacy HTML does not add an in-app entry. Bluetooth delay/echo and OEM behavior remain phone tests.
