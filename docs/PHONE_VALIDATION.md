For version 0.3.1: in Presets, use **Reset all sound to Flat** to clear headphone
correction and every tuner before comparing. Start with Hi-Fi → **System effects
only** (recommended). Complete Music detection if the player does not appear.
Saved settings survive updates, so an existing installation may still have Auto
or dither selected. Compare the same song at matched loudness, then try one EQ
change. In system effects, choose **Detailed · 80 ms** for bass resolution;
Balanced requests 40 ms and Fast requests 10 ms with less resolution/delay. None is total
Bluetooth latency. Capture quality controls do not change the system-effects engine.

# Svan phone validation

Device: TECNO LH7n, Android 14. Current report: Engine B sounds delayed or doubled
with YouTube Music over Bluetooth; other players and outputs have not been tested.
This is a real listening report, not an emulator result. The changes below need retesting.

## Install

Download `Svan-preview` from the branch's latest successful CI run and unzip
`Svan-preview.apk` on the phone. It is a minified preview with arm64 and x86_64,
not a store release. Each environment's debug signing key may differ. If Android
reports an incompatible signature, uninstall the older preview before installing;
this deletes its saved settings and presets. Do not uninstall without saving any
settings you need. No ADB commands are required for the listening checklist.

## YouTube Music / Bluetooth first

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
   app stays out of capture so it cannot be duplicated.
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
