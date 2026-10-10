> **Removed 7 October 2026:** optional player recognition (notification access) is gone. Play Protect
> flagged it as a financial-fraud risk in sideloaded builds. Svan declares no notification listener.

# Svan Privacy Policy (beta)

Svan processes audio locally on your Android device. The app does not upload,
store as audio files, or send captured playback to a server. Engine B uses
Android's playback-capture permission and a foreground notification; Android
asks for permission for each new capture session. Stop it from Hi-Fi or the
system capture controls. Svan does not intentionally capture microphone input;
RECORD_AUDIO is Android's required permission for playback capture and the audio
measurement probes.

When you search or select headphone tuning, Svan downloads AutoEq measurement,
index and target files from GitHub. Those requests expose ordinary network
metadata (including your IP address) to GitHub and its delivery infrastructure.
Imported curve files are read only when you choose them through Android's file
picker. EQ settings, presets, compatibility verdicts, routing preferences and
cached tuning data are stored in the app's local storage.

Diagnostic logs can include player package names, session identifiers and
measured levels. The app does not automatically send these logs. Share logs or
screenshots only if you choose to do so. There is no advertising or analytics SDK
in the current dependency list. Uninstalling the app removes its local data.

Optional enhanced detection uses the Shizuku API after user approval. A read-only
UserService runs as Android shell and reads only the fixed `audio` and
`media.audio_flinger` reports. It accepts no arbitrary shell commands, paths,
target packages or permission grants. Reads have byte/time limits; audio reports
stay local. Only if the user taps "Keep enhanced detection without Shizuku" does Svan,
while Shizuku is connected, run the one package-manager command `grant app.svan
android.permission.DUMP` (the same as `adb shell pm grant`), for itself and that one
permission only, so Shizuku can be uninstalled. Svan still reads only the two fixed
audio reports. Uninstalling Svan removes the grant. Existing/manual DUMP grants remain
supported. Shell detection needs Shizuku running and may need
restarting after a phone reboot. Stopping Shizuku leaves basic player-session
broadcast detection active. Setup links open Shizuku's official website in the
user's browser. The Shizuku API is MIT licensed; its notice is included in assets.

Notification-access player recognition has been removed. This build declares no
notification listener and requests no access to other apps' notifications, SMS or
accessibility services. POST_NOTIFICATIONS allows Svan to show its own notifications.
Session broadcasts and the fixed audio reports described above provide detection.
Recognition does not prove that an output path accepts effects or playback capture.

This describes the 0.5.6 implementation. A store release still needs
an owner-approved policy URL/contact and Play Console disclosures matching the
final package, including its foreground-service and MediaProjection uses.

## Headphone calibration and settings export

Imported calibration curves and their hashes are stored privately for re-tuning. Published AutoEq
data are fetched using the existing network path; preferences are never sent. Svan cannot record,
clip or export captured playback, and captured audio is never saved. The Lab can save a generated
20-second measurement WAV that contains no music; release checks use synthetic test signals only.

Settings export writes EQ, presets, Svaresa/audio preferences and active calibration curves only
to a file you choose. The file may identify your headphone. It contains no audio, Android
permissions or signing keys. Restore validates it locally without uploading it.

## Optional floating controls and diagnostic detail

SYSTEM_ALERT_WINDOW is used only for the optional Svaramanas bubble. It is not needed for
in-app controls and is not enabled automatically. You can disable the bubble or revoke overlay
access. Diagnostic reports can additionally include phone/build details and output-device names
and addresses; review and redact a report before sharing it. Posting a report in a public GitHub
issue makes it visible to others. Files exported outside Svan are not deleted by uninstalling it.

## Publication and privacy contact

TODO (owner): supply the public developer identity, monitored privacy contact or inquiry mechanism,
effective publication date, and retention/deletion handling for reports voluntarily sent to support.
Do not publish this as a completed Play policy until those fields are resolved. The generated
`privacy.html` is suitable for a static Pages deployment but Pages has not been enabled by this work.
The current app still needs a visible policy text/link before Play submission. No new data flow or
permission is introduced by this documentation.
