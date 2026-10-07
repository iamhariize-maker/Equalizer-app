> **Removed 7 October 2026:** optional player recognition (notification access) is gone. Play Protect
> flagged it as a financial-fraud risk in sideloaded builds. Svan declares no notification listener.

## Whole-phone EQ for hidden players (replaces player recognition, 7 October 2026)

The problem player recognition aimed at: with Shizuku blocked by the OEM and a player that never
announces its audio session (YouTube Music is a common case), Svan sees only Android's anonymised
playback count, so the player gets no EQ. Recognition could only name it; it never processed it, and
its notification listener triggered Play Protect's fraud alarm. Android gives normal apps no package
or session for other apps' playback (AudioPlaybackConfiguration is anonymised), so naming is not
possible without a privileged permission.

`MixFallback` instead applies the user's curve to the output mix (session 0, Wavelet's "legacy" idea)
while all of these hold for 3 s: the setting is on (default), the EQ is on, capture is not running,
Android reports music active and an anonymous player, and no Engine A/B route is playing. It switches
off at once when any player is routed, so no source is equalised twice; capture start also turns it
off immediately. Trade-offs shown in Hi-Fi → Music detection: notification sounds share the EQ while
it is active, and some phones allow output-mix effects only on the primary output (speaker). A
refusal is retried after a minute. No new permission. The output-mix chain carries only master
volume (unity on phones), so the stream-volume trap of per-session effects does not apply.
CI verifies it structurally (active session-0 DynamicsProcessing in the audio-server report while a
hidden player plays; off after the player is routed) because the test meter sits first in that chain.

# Optional music detection, 0.5.6 follow-up

Svan keeps its selected EQ, quality, headroom and capture settings across every detection fallback.
Discovery does not change the DSP or prove that Android can apply effects to a player.

- Basic detection uses player audio-effect session broadcasts and playback/route callbacks. It needs
  neither Shizuku nor notification access. A real session is still required to connect EQ.
- Enhanced detection binds a Shizuku UserService running as shell. It reads only `audio` and
  `media.audio_flinger` through Binder pipes, without `pm grant`, arbitrary commands or file paths.
  Either report can establish capability. The app independently parses/merges both reports using its
  existing ledger and verification rules. Time/byte limits and a single in-flight shell read also cover
  Binder IPC. A binding/probe timeout surfaces OEM help and returns to basic detection.
- Existing/manual DUMP grants still work, including after Shizuku stops. Normal setup never requests
  that grant. Shell mode requires Shizuku running; restart it after reboot. Turning debugging off can
  stop Shizuku on some phones; the setup UI explains the active mode rather than promising persistence.
- Player recognition is separately optional. Android's notification-access grant can expose
  notifications; Svan only queries MediaSessionManager for package/playback state. It never reads
  notification contents, song metadata, messages or track history. Values stay in memory and clear on
  disconnect. Utility packages stay excluded. Recognition rows merge by package with real discoveries;
  they cannot supply an audio-session ID, select an engine, mute an app or unlock capture.

Hi-Fi → Music detection offers **Continue with basic detection**, optional **Use player recognition**,
and Shizuku setup. Step 5 is optional; failures show an unavailable state instead of remaining pending.
OnePlus/OPPO/Realme, Xiaomi/Redmi/POCO and Vivo/iQOO get specific Developer-option guidance, a Settings
shortcut and retry. The computer grant command is tucked behind an optional advanced disclosure;
it is never required or run by Svan. Keep Play Protect enabled.

## Verification

JVM regressions cover blocked shell calls, single in-flight reads, recovery, recognized-but-unconnected
players, paused recognition, preserving existing routes and OEM guidance. Four additional CI emulator
checks exercise a real MediaSession with notification access, naming without routing, session-broadcast
pairing, listener revocation and continued system EQ. Existing release detection and all audio-output
thresholds remain intact; the release setup test now proves app DUMP stays ungranted, and helper-stop
coverage measures the independent broadcast path before restoring shell reports for capture checks.

Local verification: 103 core tests, 148 JVM tests and four screenshot-assertion Python tests pass.
Combined with the continuity fixes and four additional UID-policy regressions, all 152 JVM tests
and the debug/release/lint/test-source builds pass locally. The combined device rerun is pending;
see CONTINUITY_0.5.6.md for the initial capture-close and restart-volume findings and correction.
Android debug/release assembly, lintDebug and both test-source builds pass. The preview verifies
with v2 signing, one protected optional listener, no forbidden capabilities and four 16 KB-aligned
native libraries. R8 retains the privileged helper class/constructor. API 33/34 emulator validation
is pending on the pushed commit; its logs and screenshots must be reviewed before distributing it.
No OxygenOS/ColorOS, HyperOS,
Funtouch, TECNO or LG compatibility is established by synthetic tests. On those phones, check:
start an already-playing streaming app; finish or skip optional setup; switch songs/players/outputs;
stop Shizuku; revoke recognition; reboot; and confirm the visible engine, unduplicated audio and
unchanged sound settings. A player that withholds its session may still be recognized but unprocessed.
