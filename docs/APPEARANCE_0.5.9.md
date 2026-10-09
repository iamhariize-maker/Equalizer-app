# Svan appearance, 0.5.9 / code 16

This design starts from `4ec51ee68f415617d454a51a75ce0cc98f5e3ebb`, the
0.5.8 routing repair with nine tuning-signature slots. Work is isolated from the
concurrent Claude branch. No native DSP, playback discovery, session ownership,
capture policy, or music-routing code is changed.

## Four complete themes

The palette button in the maker's-mark header opens Appearance. Selection applies
immediately, persists across launches, and defaults to Svan Original for missing
or unknown IDs. Storage lives in `svan_appearance`, separate from sound settings.

| Theme | Treatment |
| --- | --- |
| Svan Original | Existing warm charcoal, metallic gold, serif display and original night-desert drawing, with restrained sand-gold botanical prints. |
| Midnight Raga | Charcoal olive, quiet brass, a painted night landscape and fine botanical friezes. |
| Sandstone Atelier | Parchment and ink, warm etched dunes, clay and umber botanical prints, dark Android system-bar icons. |
| Indigo Loom | Deep indigo, sand and ivory, woven geometric prints and calm contour dunes. |

The original mark remains intact. The owner-specified spelling is **Svanam
Shreshtam**. The EQ exten9ed identity, spoken label “EQ Extended”, and existing
3–6–9 introduction timings remain.

## Functional artwork

Landscape artwork is decorative. EQ curves, axes, band handles, measurements,
presets, route status and Svaresa controls continue to use live app data and code.
New prints occupy dividers and clear spaces rather than sitting behind body text.
The original landscape keeps its response-driven ridge. Additional themes render
their artwork beneath the same native response graph.

Hi-Fi opens with the actual engine state and app connections. Detection setup and
background-service details follow those primary controls; live telemetry remains
available through Playback details.

The five tabs, tuning signatures, dialogs and Svaresa share semantic colors.
Additional themes use Cormorant Garamond for display and Noto Sans for controls,
with licensed fonts packaged locally. Controls wrap, chart ticks scale, and a
sections menu replaces crowded navigation labels at large text sizes. A screen
reader action provides the same compare toggle as the dock's hold gesture.

## Build and delivery

The production variant disables scripted Activity extras as before. The final
owner certificate is applied privately to the tested production artifact;
credentials are excluded from this repository, CI, documentation and assets.
The source and design validation are tracked separately from private signing.

Theme validation is recorded in the project-root `design-qa.md`. Automated and
emulator results are recorded in the release verification notes. Emulator
fixtures do not establish Spotify, Amazon Music or YouTube Music compatibility
on a physical phone or a Bluetooth route.
