# Player connections and shared-output experiment — 0.5.7 / code 14

This combines the exact owner-uploaded 0.5.6 spatial baseline with audio-only Recording mode on `ccr-c220a1e1-hikg7s`. No permission is added.
The original owner certificate remains the release identity; its private key is used only
outside Git/CI. No further native DSP edits are made. Recording taps remain allocation-free and wait-free; dry precedes DSP, wet follows the final output fade.

## Connections without setup

The foreground service still receives player session announcements and playback/output events.
A manifest receiver additionally handles explicitly addressed announcements and lets players
discover Svan through the standard receiver contract. Duplicate announcements do not recreate a
healthy effect. `EffectControlActivity` implements Android's standard EQ-panel intent: a player
can supply its real session ID and open Svan's EQ screen. It accepts only the standard contract,
never scripted Svan commands. A stopped equalizer is not silently re-enabled. No guessed session,
notification access, accessibility access, broad package permission or audio-focus request is used.

Package/session/UID checks reject unusable announcements and Svan's own session. CLOSE for a
different package cannot remove the current connection. Real CLOSE releases the effect and any
source mute immediately. Recently closed connections are retained as bounded metadata: at most
32 closed entries, expiring after two minutes. They are diagnostic hints, never authority to
attach effects, identify anonymous playback or admit capture. Fresh announcements create a new
generation; numeric ID reuse by another UID retires the old owner first. Android's broadcast
contract does not include a generation, so a delayed CLOSE from the same package and same ID
cannot always be distinguished from a real close without further evidence.

Ordinary pause does not release a known effect. Effect enable/control callbacks request worker
recovery only while the handle is still current. Lost-effect rebuilding is bounded to three
attempts, with increasing delays, until a fresh connection/output event re-arms it; unsupported
attachments retain the existing capped retry. A failed report remains unknown, never absence.
All discovery, history and effect work runs on control/worker threads, not the DSP audio thread.

Without DUMP, Android's public playback callback is anonymous. If a player releases its track and
creates a replacement without announcing its ID, recent history cannot reveal that new ID.
Apple/Amazon phone reliability is not claimed from synthetic tests. Optional existing detection
setup remains in troubleshooting for inaccessible sessions. Main status describes connections
rather than basic/enhanced quality tiers.

The spatial baseline's automatic whole-phone fallback remains available with its existing setting and fade/hold policy. The explicit option below suspends that automatic handle; the two cannot stack. This preserves the owner's existing feature while exposing a controllable route experiment.

## Shared-output EQ — explicit experiment

In Hi-Fi, choose **Try shared-output EQ**. Default is off, and service shutdown resets it off.
This attempts DynamicsProcessing on Android's shared output mix (session 0). It can cover music
without a per-player ID when that music actually passes through the supported mix. Android
deprecates global insert effects; OEM, Bluetooth/USB and direct/offload behavior need phone tests.
Successful construction/control is reported only as **attached**, never proof of a player's path.

Enabling it releases Svan's per-player effects. New announced sessions are labelled SHARED_OUTPUT
and receive no second EQ. There is no global mute. Capture admission excludes these routes, and
Engine B startup is blocked in the activity, service and router. Turn it off before Engine B or
Recording mode. Failure/control loss restores per-player connections; a reported device connection/removal
stops the experiment and requires explicitly enabling it again. Selecting between already-connected
outputs is not reliably observable through the permission-free callback; stop and re-test manually
after that change. Use **Stop shared-output EQ** to restore the per-player path.

The option may affect other sounds sharing that mix, and cannot isolate an app or identify an
anonymous player. Per-app overrides are not enforced during shared-output use. Recording mode
still records Engine B only; this option does not make protected streams capturable.

Detection grants locate attachment points; they do not increase audio bandwidth or processing
precision. System effects use Android's band-based DynamicsProcessing. Engine B uses Svan's full
C++ chain only when permitted audio is received and the source can be safely muted. Existing
headroom/protection and user settings apply to either system-effect path.

## Verification

Seven new JVM tests cover history retention/expiry, generations, unrelated closes, ID/UID reuse,
announcement validation and exclusive capture policy. Combined local total: 211 JVM tests pass,
including 17 recorder tests, two multi-rate export tests and explicit Apple/Amazon media-policy
checks. All 151 native tests and 11 Python UI/control assertions pass. Full assembly/lint passes;
the final rerun and CI evidence are recorded in HANDOFF.md.
`basic_detection.sh` adds 12 independent CI checks with DUMP revoked. It exercises explicitly
addressed and general announcements, pause/resume, lost-effect repair, four session replacements,
missing announcements, the EQ-panel contract and a wrong-package CLOSE. It measures a known
1 kHz EQ cut downstream using the existing output-mix Visualizer meter, including on a source
that never announces a session, and checks no stacked effects, capture blocking, restoration
and the output-change handler. Test instrumentation reads source IDs only to exercise the public
panel contract; Svan does not receive them in the unannounced/shared-output cases.

The spatial baseline's 41 audio and 13 detection, four quality, nine workspace, eight control, onboarding,
production and six recording checks remain required. CI results and reviewed images must be
recorded in HANDOFF.md after the final implementation run. Emulator measurements do not verify
TECNO/LG commercial players or offloaded/headphone paths.

References: [Android AudioEffect](https://developer.android.com/reference/android/media/audiofx/AudioEffect),
[playback capture rules](https://developer.android.com/media/platform/av-capture),
[Android 14 anonymization](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/media/java/android/media/AudioPlaybackConfiguration.java).
