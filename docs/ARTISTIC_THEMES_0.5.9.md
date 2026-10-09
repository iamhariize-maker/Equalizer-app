# Svan 0.5.9 artistic themes

The original charcoal/gold desert design remains the default. Midnight Raga,
Sandstone Atelier and Indigo Loom add separate, restrained Indian-inspired
landscapes, fine prints, typography and semantic colour palettes across the
five sections and Svaresa. The palette action in every screen header opens
Appearance; theme choice persists independently of sound settings.

The release preserves the owner's supplied routing-fix source baseline.
The Hi-Fi screen brings engine, music detection and per-app routing ahead of
advanced details while continuing to report measured connection/capture state.
EQ gestures, tenth-dB steps, precision controls, source ownership, production
automation rejection, export/restore and DSP behaviour remain covered by the
inherited checks. Larger text scrolls above pinned actions; the Svaresa footer
and section navigation remain reachable at 200% text.

The final release comes from the exact production artifact in CI run
[37906917761](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37906917761),
source `ba279d026cc14fbaee6bc3f26f0202d3d6f8d86e`. All 86 ZIP entries retain
their tested digests after private signing with the existing owner key.
APK v2 signature, certificate identity, production package/version, permission
allowlist and 16 KB alignment all pass. No owner signing material entered
source, CI or the delivery files; temporary private files were removed.

Validation: native review of 68 appearance captures, 74 automated appearance
checks, 248 contrast pairs, 267 Android JVM tests, 179 native tests, 17 tooling
tests and the full Android 14 audio/production suite's 125 reported checks.
See [design QA](../design-qa.md) and the
[public release evidence](appearance-release-0.5.9.json).

The complete CI matrix is not green. Android 13 basic detection and a frozen
payload smoke retry passed; its full suite did not finish. API 29/30/35 smoke
passed. Android 16 smoke remains unsuccessful after simulator connection loss.
This release does not establish physical-phone capture permission or Bluetooth
routing for Spotify, Amazon Music or YouTube Music. Those remain device/player
qualification work, not a claim made by these themes.

The uploaded preview has a different certificate. Export settings from Presets
outside the app before uninstalling it, then install the owner-signed APK and
restore settings. Existing owner-signed installations can update normally.

Work stayed on `codex/svan-artistic-themes`; no Claude branch or main was edited.
Artwork provenance and retained font notices are in
[appearance-artwork.json](appearance-artwork.json).
