# Svan preview privacy notice

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

Optional enhanced detection uses the Shizuku API after explicit user approval.
Its short-lived setup service runs a fixed command to grant Svan the Android DUMP
permission. It accepts no arbitrary shell commands. This lets Svan read Android's
local audio-session report to find playing apps; the report is not uploaded.
Shizuku and wireless debugging can be stopped after the grant. Setup links open
Shizuku's official website in the user's browser. The Shizuku API is MIT licensed;
its notice is included in the APK's assets/licenses directory.

This describes the current preview implementation. A store release still needs
an owner-approved policy URL/contact and Play Console disclosures matching the
final package, including its foreground-service and MediaProjection uses.
