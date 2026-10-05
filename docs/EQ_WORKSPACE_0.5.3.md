# Extended EQ and Svaresa — 0.5.3

The owner found Extended EQ impractical and asked for Svaresa to control it by default, deeply,
with audio quality ahead of bass/vocal/treble taste. The owner reports the previous 0.5.2 build
was successful with IM4 listening; that is useful feedback, not a blind comparison of this build.

## What changes

- First launch after upgrade/fresh install selects Svaresa EQ and enables Auto master once.
  Later explicit manual overrides survive restart. Manual EQ, its layout/count and preamp are retained.
- Auto master enabled from its panel also takes EQ ownership. Its band layer replaces the manual
  bands instead of stacking with them. Headphone tuning and bass/vocal/instrument controls are
  independent. Applying a preset or choosing Your EQ disables the master and restores manual EQ.
- The graph shows the complete **system EQ band response**, including headphone/bass and system
  vocal approximations, excluding preamp. Nodes/faders show the selected editable layer. Engine B's
  mid/side processing is additional and cannot be represented by a single scalar EQ line.
- Parametric automation exposes actual frequencies, types, Q and gains. Svaresa owns frequency/Q;
  vertical node drags and gain entry add bounded ±3 dB preferences to the automatic target.
  Preferences follow filter identity and are separated by graphic layout. Auto adaptation continues.
- Graphic automation fits the target response to 10/15/31/64 filters, then actually **runs those
  filters**. It does not merely sample the target at slider centres. End filters are shelves to preserve
  low/high tails; interiors use spacing-derived Q. Fit error is disclosed before preference/guard steps.
- A summed-response guard caps the assistant band's positive peak at 6 dB on a 512-point log grid
  with 0.02 dB margin. It uniformly scales positive gains and preserves cuts. Adjacent overlap remains
  intentional; this prevents unwanted excess emphasis, not every possible tonal preference conflict.
- After fitting, preferences and slew, level trim is recomputed from the **applied** cascade and static
  mid/side response. Estimated reference-spectrum matching remains labelled when live audio is absent.
- Saving a preset while automation owns EQ saves its current EQ bands and trim, rather than the hidden manual curve. Separate stereo/dynamic processors are not part of an EQ-only preset.
- Manual EQ is fully scrollable. Graphic faders have 56 dp touch width, progress accessibility, numeric
  entry and reset. Parametric gains have fine steps/reset. Undo groups continuous edits and preserves
  live controller state. Graphic→parametric conversion preserves filters exactly; conversion to graphic
  fits the response, reports RMS/max error and permits undo. Extreme/narrow filters cannot always fit.
- Existing saved all-bell graphic layouts retain their filter types. Explicit fitting/conversion opts into
  shelf endpoints. No new permission, notification listener or metadata collection.

## Measured native regressions

All errors use the real biquad response; the 240-point fitting grid spans 20 Hz–20 kHz at 48 kHz.
A test target combines +6 dB low shelf at 110 Hz, −3 dB at 300 Hz, −2 dB at 3.5 kHz,
and +2 dB high shelf at 7.5 kHz:

| Graphic bands | RMS error | Maximum error |
|---|---:|---:|
| 10 | 0.534 dB | 1.355 dB |
| 15 | 0.382 dB | 1.089 dB |
| 31 | 0.285 dB | 0.977 dB |
| 64 | 0.264 dB | 1.041 dB |

More bands do not guarantee lower worst-case error. Matching a sparse 31-band graphic cascade back
to its layout gives 0.0001 dB RMS and 0.0004 dB maximum error in the checked case.
Full-chain measured loudness differences after fitting/trim: −0.061/−0.044/−0.063 dB for
10/31/64 bands in the checked warm/vocal/strings synthetic stereo case. These are not universal
matching bounds; auto headroom, dynamics and manual layers can change final loudness.

Additional tests check neutral layouts, coarse-layout failure reporting, overlapping boosts capped
by summed response (cuts preserved), and predictive headroom for multiple overlapping bells.
Headroom does not subtract required attenuation twice when preamp already supplies it.

## Android proof and remaining limits

Nine JVM regressions cover ownership, bypass not waking a hidden manual boost, restoring manual
state, separate automatic/manual layouts, offset identities and finite bounds, preserved independent
layers, shelf endpoint compatibility and EQ-off behavior.

`eq_workspace.sh` measures final output for manual gain, automatic ownership replacing that gain,
fitted graphic filters, personal gain versus native response prediction, persisted preferences after
restart, and manual restoration. CI must pass all six alongside existing audio/detection checks.
The debug harness reads full reports from app-private storage with `run-as`; logcat truncates
31/64-band JSON reports. This requires no additional permission.
R8 release smoke exercises both new JNI functions and automated layouts. Screenshots cover the
real auto/manual EQ controls on the small emulator; GUIDED/SVARESA captures explicitly set mode.

Engine A still cannot hear the music; Auto master there uses output/volume/night context. Capturable
Engine B permits measured music corrections. Frequency/Q choices are the bounded rule planner,
not learned instrument separation or an unconstrained optimizer. Protection covers known EQ gain;
reactive gain protection remains distinct and is not a true-peak limiter. Manual settings can override
headroom/protection. The guard caps the assistant EQ, not the separate headphone/tuner layers.
Phone touch ergonomics, IM4 preference/fatigue, commercial-streaming behavior and battery cost of
this build need owner listening. Keep Play Protect enabled; no acceptance claim is made.
