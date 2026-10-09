# Native appearance design QA

Status: **passed** for the native appearance implementation at
`9334cedab365e2fec589f3b31e3726312dcaf51a`. Independent audio and Android
compatibility release gates are recorded separately in the release evidence.

## References and comparison conditions

The references are the owner's Sound and Hi-Fi screenshots, the original
response-driven night desert, and the preparation lookbook's Midnight Raga,
Sandstone Atelier and Indigo Loom directions. The owner requested all three
as separate themes, retaining Original as the default. The brand line follows
the owner's spelling, **Svanam Shreshtam**.

Native captures use 390 × 844 logical pixels, 780 × 1688 physical pixels at
320 dpi, with normal text and 200% text. Full screens and focused chart/dock
crops were inspected. Android status bars are retained and accounted for in
the comparison. App state is an empty headphone selection, Flat/manual EQ
and no connected music source. Concept headphones, curves and route statuses
are not fabricated as application data.

Original retains its code-drawn desert and gold hierarchy; Raga adds a quiet
miniature landscape and botanical brass; Sandstone uses parchment, ink, etched
dunes and floral print; Indigo uses woven contours and a geometric border.
Generated decorative pixels remain unchanged and are cropped at draw time.
Provenance and font licences are in `docs/appearance-artwork.json`.

## Final evidence

- CI run: https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37901352746
- Appearance job: `113728176215`, **success**.
- Capture artifact: `11603517122`, `appearance-ui-api34`.
- 68 native captures: 34 debug and 34 exact-production captures.
- 74 automated checks: 39 debug and 35 production checks, zero failures.
- Tested production APK SHA-256:
  `b7037f79be5465a875becffdb9ed976c276c04858f4e0b1c479a2886b749c5c7`.
- Capture root: `/workspace/svan-ui-device/verified-appearance-9334/`.
- Reviewed production contact sheets: `review/sound.jpg`, `eq.jpg`,
  `presets.jpg`, `hi-fi.jpg`, `lab.jpg`, `svaresa.jpg`, `large-0.jpg`
  and `large-1.jpg`.
- Focused final crops: `review/chart-200.png` and `review/dock-200.png`.

## Results

| Area | Evidence and result |
| --- | --- |
| Five tabs in four themes | All 20 production tab screens reviewed; type, spacing, artwork, selected navigation and real state agree with each theme. |
| Svaresa | Four production theme screens reviewed; Done/compare remain pinned above system navigation. |
| Appearance | All four selections exercised; checked Compose semantics and Sandstone cold-launch persistence pass. Original is the default. |
| Saved sound | Debug assertions compare saved audio state before and after every theme change; it remains identical. |
| 200% text | All five sections remain reachable; Appearance and Sections scroll; Done and compare remain visible. |
| Chart labels | Extreme dB labels and the final 10k label remain inside the plot. Guidance is below the plot, clear of data and gestures. |
| Presets | Equal-height normal import/paste/save tiles; single-column large-text actions; full filename in full-width guidance. |
| Dock | Normal text has a gap before Open; large text has a complete tap/hold instruction without crowding. |
| Light-theme chrome | Sandstone uses dark system-bar icons and visible selected/control states. |
| Contrast | All 248 palette pairs pass 4.5:1 text / 3:1 controls in `docs/appearance-contrast.json`. |
| Decorative restraint | Fine prints at deliberate transitions; actual graph and control content stays unobscured. |

## Findings closed by final recapture

Earlier native review found a below-fold Svaresa footer, chart guidance over
the upper axis label, uneven preset action tiles, a clipped 10k label at 200%,
and crowding in the large-text dock. Each was fixed; final production captures
verify the resulting layout. The harness checks Compose's checked/checkable
selection row instead of requiring a classic RadioButton widget class.

No unresolved P0, P1 or P2 design finding remains in the reviewed states.
The Lab probe row intentionally scrolls horizontally; other long content and
large-text theme lists scroll above pinned actions.

This appearance pass does not establish physical-phone capture permission or
Bluetooth routing for Spotify, Amazon Music or YouTube Music. Those require
the device/player qualification described in the existing audio documentation.
