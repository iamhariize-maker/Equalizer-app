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
It sends one fixed `grant` request to Android's package service through the
authorized Shizuku binder to grant only Svan the Android DUMP permission, for the
app's Android user. It accepts no arbitrary shell commands, targets or permissions,
and starts no separate privileged helper process. A temporary private file holds
only the grant's result/error text and is deleted after the request returns.
This lets Svan read Android's
local audio-session report to find playing apps; the report is not uploaded.
Shizuku and wireless debugging can be stopped after the grant. Setup links open
Shizuku's official website in the user's browser. The Shizuku API is MIT licensed;
its notice is included in the APK's assets/licenses directory.

This describes the current preview implementation. A store release still needs
an owner-approved policy URL/contact and Play Console disclosures matching the
final package, including its foreground-service and MediaProjection uses.

Svan 0.5.2 removes player recognition and notification-listener access entirely.
There is no notification-listener service, settings link or notification-reading
permission. POST_NOTIFICATIONS is retained only to show Svan's own foreground
service/status notifications; it does not allow reading other apps' notifications.
Music discovery uses Android audio reports, playback callbacks and player
session broadcasts. The working Shizuku detection grant remains available.

## Optional blind listening and calibration

An explicit eight-second tap uses only already-authorized capture, before DSP; blocked apps remain
blocked. Captured/WAV excerpts stay in memory and are discarded when the dialog closes. They are
not uploaded or saved as recordings. Only local votes, measured level match, timestamp, headphone
name and a frozen-configuration hash are saved; Clear results deletes them. Imported calibration
curves and their hashes are stored privately for re-tuning. Published AutoEq data are fetched using
the existing network path; listening recordings and preferences are never sent. No new permission
or notification access is introduced.

Settings export writes EQ, presets, Svaresa/audio preferences and active calibration curves only
to a file you choose. The file may identify your headphone. It contains no recordings, listening
votes, Android permissions or signing keys. Restore validates it locally without uploading it.
