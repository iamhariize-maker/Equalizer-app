# Native appearance design QA

Status: **blocked pending rendered-screen review**. This is an in-progress QA
record, not a delivery pass.

Reference set: the owner's current Sound/Hi-Fi screenshots, the original
response-driven desert design, and the approved direction to implement all
three explorations as separate themes while keeping Original as the default.
The preparation lookbook records Midnight Raga, Sandstone Atelier and Indigo
Loom. New decorative source images are recorded in `docs/appearance-artwork.json`.

The native application retains its real data, controls, mark and audio behavior.
Concept headphones, curves and route statuses are not copied as fabricated app
state. Decorative references are adapted to the existing Android flows.

## Verification already completed

- Uploaded APK bytecode/resources match the source baseline; native section
  differences are limited to build IDs. See `docs/appearance-baseline.json`.
- All 248 native palette contrast pairs pass the stated 4.5:1 text / 3:1 control
  limits; see `docs/appearance-contrast.json`.
- Existing native audio tests: 179 tests, zero failed checks.
- Existing Android JVM tests: 267 tests, no failures, errors or skips.
- Existing screenshot/control tooling tests: 17 tests passed.

## Required before delivery

Capture the actual native app at 390 × 844 logical pixels (780 × 1688 physical,
320 dpi), normal text and 200% text. Compare full screens and focused crops with
the reference set, using equivalent app state and system-inset handling.
Review all five tabs, Svaresa, Appearance, large-text navigation, chart labels,
selection states, and light-theme system bars. Exercise cold-launch persistence
and verify theme switching leaves saved sound unchanged.

The `appearance-ui` CI job captures debug and the exact production artifact;
the inherited API 33/34 routing and production gates remain required. Any
P0/P1/P2 layout, contrast, state or usability finding must be fixed and recaptured
before replacing this blocked status with a passed review.

## Findings from the first native render

At 390 × 844 logical pixels, the production-independent debug capture of all
five Original tabs retained the existing navigation, desert response view and
real app state. The first Svaresa capture exposed a long panel with its Done
and compare controls below the fold. The correction bounds the panel and
scrolls its body above a pinned, wrapping footer. Recapture is still required.
The response chart now places both extreme dB labels inside its drawing
bounds; its coordinate mapping and gesture behavior are unchanged.

The baseline API 34 workspace artifact also exposed an existing asynchronous
test race: the restored report was captured at 04:27:15.738, three milliseconds
before the controller logged its resting state. The fixture now gives that
transition the same two-second settling interval as activation. All original
protection assertions remain intact, and no controller behavior was changed.

## Full four-theme debug review

The 26 native captures from CI 37893893342 confirm all five tabs and pinned
Svaresa actions in all four themes, unchanged saved sound, and Sandstone
restoration on cold launch. The selector incorrectly required a classic
RadioButton class; Compose exposes a checked, checkable View row. The
assertion now checks that actual semantic state and its Sandstone child text.

Focused chart review found the instructional text colliding with the upper
dB label. It now sits below the chart, outside measured data and gestures.
Preset import tiles now use a short caption, retaining the full filename in
the full-width instructions; all three actions align to the tallest tile.
These fixes still require production and large-text recapture.

The API 36 compatibility attempt failed before boot when SDK Manager received
an invalid emulator ZIP. No APK install or smoke test ran in that attempt.
It needs a fresh infrastructure attempt rather than an application change.

The next run pins emulator build 16428233 (37.2.12), whose direct Google
archive returns HTTP 200 with application/zip and the expected package size.
The local installed binary confirms this build/version. This changes test
infrastructure only; all test assertions and API matrices remain required.

## Production and large-text review

CI 37897366134 captured 68 native debug/production screens and passed all
74 automated appearance checks. Production confirms the aligned import
actions and chart instructions outside the plot. All themes, persistence,
saved sound and 200% navigation/Done/compare checks pass. Manual review
found two remaining text-scale issues: the 10k label extends outside the
chart at 200%, and the dock's Open label crowds its wrapping subtitle.
The label now clamps to the canvas bounds; the large-text dock uses one
complete tap/hold instruction without a separate trailing Open label.
Normal text retains Open with an explicit gap. Final recapture is required.
