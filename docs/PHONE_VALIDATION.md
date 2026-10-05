For version 0.3.1: in Presets, use **Reset all sound to Flat** to clear headphone
correction and every tuner before comparing. Start with Hi-Fi → **System effects
only** (recommended). Complete Music detection if the player does not appear.
Saved settings survive updates, so an existing installation may still have Auto
or dither selected. Compare the same song at matched loudness, then try one EQ
change. In system effects, choose **Detailed · 80 ms** for bass resolution;
Balanced requests 40 ms and Fast requests 10 ms with less resolution/delay. None is total
Bluetooth latency. Capture quality controls do not change the system-effects engine.

# Svan phone validation

Device reports from the owner, TECNO LH7n (Android 14): Engine B sounded delayed
or doubled with YouTube Music over Bluetooth; Apple Music was detected earlier
with Fosi Audio IM4 earbuds but is now missing over a wired connection; Neutron
has not appeared; no player session was shown while using Realme Buds Air 8, with
LHDC both enabled and disabled. These are real listening observations. No audio
service dump or device logs are available yet, so their causes remain unknown.

The music player owns the playback session; IM4 and Realme Buds Air 8 are output
routes. A route change should not normally create or remove the player's session,
but player-specific direct/offload output or an OEM audio-service report may
change what Svan can observe or process. The diagnostics below are intended to
separate “Android reported no player track,” “track found but no attachable
session,” and “session found but not routed by Svan.”

## 0.5 first: send the diagnostic report

Open Hi-Fi → Music detection. The top card now states, in words, whether Svan sees your player and why not
(permission, background service stopped by Android, player on a direct/offload output, audio reports unreadable).
With the player ACTIVE, tap **Share diagnostic report** and send it. It contains app names, session numbers, Android's
audio tables and Svan's log, no audio and no account data. Do this once per problem app (Neutron, Apple Music,
YouTube Music) and once on each route (speaker, wired, Bluetooth). For Neutron also note its output setting
(standard Android output vs hi-res/bit-perfect/exclusive/USB direct).

**Neutron tip (unconfirmed on this phone):** if Neutron is not detected or not processed, check Neutron's
Settings > Audio Hardware > **DSP Effect (Device)**. Another system equalizer's supported-player notes say Neutron
only opens its audio session to Android effects with that option on. Test once with it off and once with it on,
and send the diagnostic report for each, so we know whether this is the cause on the TECNO.

## Isolate delay from doubled playback

Use the same short, familiar passage and keep phone/headphone volume fixed. In Presets, use
**Reset all sound to Flat**. Do not run Engine A and Engine B together during this comparison.

1. Stop both Svan engines and play the passage. Note the normal Bluetooth timing and whether the
   original itself has an echo.
2. Start **System effects only** and repeat. Note whether playback remains single and in sync.
3. Stop System effects. Start the **Audiophile engine** and confirm YouTube Music is shown under that
   engine with non-silent input/output peaks. Note whether you hear one copy, an echo/two copies, or a
   single copy that is only delayed.
4. Stop Engine B before switching route. Repeat its comparison on the phone speaker or a wired output
   if one is available. This separates a Bluetooth-specific problem from the capture/replay path.
5. Save the player/engine row, route, quality mode, output queue, peaks, underruns, and what you heard.
   The queue is not total Bluetooth latency. Do not change EQ or oversampling while isolating routing.

If System effects is single and Engine B is doubled, keep using System effects for that player while
the capture/replay path is investigated. If both Svan modes double the signal, stop Svan and report
that baseline before making further changes.

## Install

Download `Svan-preview` from the branch's latest successful CI run and unzip
`Svan-preview.apk` on the phone. It is a minified preview with arm64 and x86_64,
not a store release. Current previews use the same preview signing key and should
update in place. If Android reports an incompatible signature, it may be an older
preview signed with the earlier per-runner key; uninstalling that build deletes
saved settings and presets, so export or note anything you need first. No ADB
commands are required for the listening checklist.

## Check player detection on wired and Bluetooth routes

Use one music app that is actively playing; the earbud name is not expected to
appear as an app row.

1. Install the latest `Svan-preview`, open Hi-Fi → Music detection, keep a song
   playing, and tap Refresh music detection. Read the last-scan summary: Android
   track count, usable media-session count, and any entry that lacked an app or
   session ID.
2. Repeat with Apple Music on a wired output and on the Realme Buds Air 8 (if
   available). Then repeat with Neutron on each route. Keep app, track, Android
   settings, and Svan engine mode constant while changing only the output route.
3. For Neutron, test both its normal Android output and any exclusive/USB/direct
   or bit-perfect option separately. Those are different player paths; record
   the exact option. Do not assume LHDC is the cause if LHDC on/off gives the
   same result.
4. For each attempt, note the detected app row and engine, last-scan counts,
   route type (wired / Bluetooth / phone speaker), codec if the phone reports it,
   and whether playback continued normally. The expandable local audio details
   may help diagnose a parser miss; they are not uploaded by Svan.

The visibility list now covers common streaming players (YouTube Music, YouTube, Spotify, Amazon Music,
Apple Music, Tidal, Deezer, Qobuz, SoundCloud, Pandora) and offline players (Poweramp, Neutron, ONKYO
HF Player, HiBy, FiiO Music, USB Audio Player Pro, VLC, foobar2000, AIMP, Musicolet, Pulsar and Plex).
That lets Svan resolve app names and UIDs when Android reports them; it is not a certification or promise
that every output mode exposes a session or permits capture. Test only the apps installed on the phone,
and compare a player's ordinary Android output separately from any exclusive/direct/bit-perfect mode.

If Android sees a usable media session, check Apps & engines to see whether Svan
attached Engine A or Engine B. If it sees an entry but no usable session ID, the
player may be using an output path Svan cannot attach to. If no track is reported,
capture the expanded local audio details while music is still playing. These
outcomes require different fixes.

## YouTube Music / Bluetooth echo first

The 0.1 preview was installed and tested: screenshots show zero detected apps,
so none of its EQ changes reached YouTube Music. Version 0.2 repairs the runtime
broadcast listener and adds a phone-only enhanced-detection setup. Wi-Fi is
available on the user's TECNO; other players have not yet been tested.

Before listening in 0.2:

1. Open Hi-Fi → Music detection. Some players connect through the new runtime
   receiver. Enable enhanced detection if YouTube Music remains missing.
2. Install Shizuku from the linked official download page. With Wi-Fi connected,
   follow Shizuku's instructions to enable Developer options / Wireless debugging,
   pair using the displayed code and start Shizuku. No PC or root is required on
   this Android 14 phone. Pairing/start requires the user's Android UI actions.
3. Return to Svan and tap Enable music detection. Approve Svan in Shizuku's dialog.
   Svan's single-purpose setup service grants only its own DUMP permission.
4. After Enhanced detection enabled appears, Shizuku and wireless debugging can
   be stopped. The grant persists until revoked or Svan is uninstalled. An update
   signed with the same key preserves it; a different preview key may require a
   reinstall and therefore setup again.
5. Keep a song playing and check the YouTube Music row. A running capture service
   without a connected player is not working EQ. On Engine B, input/output peaks
   near −120 dBFS mean silence; the output-queue reading alone never proves music
   is being processed. Report the app row, signal peaks and any detection error.

Use a familiar song at a comfortable, fixed phone volume. Keep headphone tuning,
bass, vocal and orchestral controls off initially, with a flat EQ and 0 dB preamp.

1. Hi-Fi: start the system equalizer. Play YouTube Music, return to Hi-Fi and
   check Apps & engines. If it is missing, Svan has not detected its session;
   do not assume the EQ is processing it. Apps without session broadcasts need
   enhanced detection through the phone-only setup above (or an ADB DUMP grant).
2. Start the audiophile engine. The player must appear as **Audiophile engine**,
   rather than just the service saying it is running. Listen for an echo, a
   pause when switching, crackle and persistent delay. A blocked or undetected
   app stays out of capture so it cannot be duplicated. Start the engine while
   YouTube Music is already playing: the compatibility check runs before Engine
   B opens its recorder. If you switch to a player not yet checked while the
   engine is active, Svan keeps it audible on System effects until Engine B is
   restarted with that player playing.
3. Note quality, output queue/buffer, DSP load and underruns. These are local
   diagnostics; they do not measure end-to-end Bluetooth latency. Compare
   Efficient and High quality, then Audiophile. Keep Extreme for a later test.
4. Choose System effects for YouTube Music. This stops capture; restart it if
   desired. Compare sound while the app's status says system effects. Record
   whether the echo disappears and whether timing improves.
5. Test a clear +6 dB bell around 1 kHz with low source volume. Compare bypass,
   headroom on and headroom off. With headroom on, boosts can reduce overall
   level; the applied preamp readout explains this. Headroom off permits actual
   boost, but gain protection may still reduce hot signals to prevent overload.
6. Restore a conservative curve. Run for 30 minutes with Svan backgrounded and
   the screen off. Note battery before/after, quality, stops, dropouts, and any
   Bluetooth reconnection problems. Repeat one mode at a time.

If available, repeat with the phone speaker or wired headphones to isolate
Bluetooth transport delay. Never infer phone stability or sound quality from the
emulator's synthetic tone tests. Send the Hi-Fi routing/diagnostic screenshot,
EQ screenshot, player name, quality, and what you heard.

## Listening iteration

Evaluate one tuner at a time on familiar bass transients, dry vocals, centred
solo instruments, wide stereo material and mono material. Mid/side processing
cannot identify voices or individual instruments. Orchestral controls have no
processing path on Engine A. Keep timestamps and level-matched comparisons before
changing thresholds or advertising perceptual benefits.
