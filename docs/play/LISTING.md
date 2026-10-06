# Play listing draft — not submitted

## App name

Svan

## Short description

Android equalizer with presets, headphone profiles and optional audio processing.

## Full description

Svan lets you tune eligible Android music playback with graphic and parametric EQ, saved presets
and headphone correction profiles. Start with system effects and check the Hi-Fi screen to see
which playing apps have usable sessions.

The optional Audiophile engine captures eligible playback with Android's consent, processes it
locally and plays the processed output. It provides oversampled EQ, reconstructed-peak protection
and selective dynamic EQ. Svaresa controls automatic EQ, with a separate manual workspace.
The local blind-listening tool compares an original excerpt and a native-engine render at matched
measured loudness. Those renders may differ from Android system effects.

Headphone profiles use AutoEq data downloaded from GitHub when requested. Imported curves and
settings export/restore are available through Android's file picker. Audio is processed locally;
GitHub receives network metadata for profile downloads. Read the privacy policy for details.

Available processing varies by phone, player and output route. Capture-blocked sources and
exclusive/direct/bit-perfect outputs limit processing. The capture engine can add latency, and
Bluetooth echo/delay and screen-off behavior require further real-device validation. Svan does
not promise universal compatibility, bit-perfect capture or a listener preference.

Android 10 or newer; current builds include ARM64 and x86_64. This beta is free and has no paid
unlock. Svan's original code is All rights reserved; third-party licences remain applicable.

## Evidence and owner fields

- Features and limitations: [0.5.5 lab](../QUALITY_LAB_0.5.5.md),
  [EQ workspace](../EQ_WORKSPACE_0.5.3.md), [phone validation](../PHONE_VALIDATION.md).
- Package/version/SDK/ABIs: [build.gradle.kts](../../android/app/build.gradle.kts#L20).
- Owner-provided original APK and identity: [beta notes](../releases/v0.5.5-beta.md).
- TODO owner: developer/support contact, public privacy URL, audience/content-rating answers,
  final screenshots and required videos, and signing Option A or B. No listing or release is
  submitted here. Confirm final Console limits and disclosures before pasting text.
- Original-code licence: owner chose [All rights reserved](../../LICENSE); no licence TODO remains.
