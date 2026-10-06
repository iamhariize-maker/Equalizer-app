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
stay local. Svan does not grant DUMP to itself. Existing/manual DUMP grants remain
supported for compatibility. Shell detection needs Shizuku running and may need
restarting after a phone reboot. Stopping Shizuku leaves basic player-session
broadcast detection active. Setup links open Shizuku's official website in the
user's browser. The Shizuku API is MIT licensed; its notice is included in assets.

Player recognition is a separate optional fallback, enabled only through Android's
notification-access settings. Android's grant can expose notifications; Svan's
listener does not read notification contents. It only queries MediaSessionManager
for music app package names and playback state, without song titles, messages,
notification text or track history. These values stay in memory, are cleared when
the listener disconnects, and are neither saved nor uploaded. Revoke access through
Hi-Fi → Music detection → Manage player recognition. Recognizing an app does not
supply an audio-session ID or prove that effects/capture can process it.
POST_NOTIFICATIONS allows Svan to show its own notifications and is separate from
this optional notification-access grant. No SMS or accessibility access is used.

This describes the current preview implementation. A store release still needs
an owner-approved policy URL/contact and Play Console disclosures matching the
final package, including its foreground-service and MediaProjection uses.

## Optional blind listening and calibration

An explicit eight-second tap uses only already-authorized capture, before DSP; blocked apps remain
blocked. Captured/WAV excerpts stay in memory and are discarded when the dialog closes. They are
not uploaded or saved as recordings. Only local votes, measured level match, timestamp, headphone
name and a frozen-configuration hash are saved; Clear results deletes them. Imported calibration
curves and their hashes are stored privately for re-tuning. Published AutoEq data are fetched using
the existing network path; listening recordings and preferences are never sent. The listening/calibration feature adds no permissions.

Settings export writes EQ, presets, Svaresa/audio preferences and active calibration curves only
to a file you choose. The file may identify your headphone. It contains no recordings, listening
votes, Android permissions or signing keys. Restore validates it locally without uploading it.

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
