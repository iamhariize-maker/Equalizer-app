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

This describes the current preview implementation. A store release still needs
an owner-approved policy URL/contact and Play Console disclosures matching the
final package, including its foreground-service and MediaProjection uses.
