# Fine control in the 0.5.3 preview

Owner request: controls should be smooth, easy to move precisely and feel like small mechanical
steps. No vibration or click sounds are used for these controls.

The in-app master shortcut has its own dock above navigation. The old floating badge intercepted
right-hand slider taps. Tap-to-open and hold-to-compare remain available without covering controls.
The optional bubble over other apps is unchanged.

Knobs accept clockwise/counterclockwise motion around the rim and relative sideways dragging
through the centre. Turning needs 540° for the full range; sideways travel needs 320 dp. Starting
a vertical gesture in the centre scrolls the page. Amount moves in 0.1 dB ticks, depth in 1 Hz ticks,
and normalized feel/vocal/space controls in 1% ticks. Double-tap resets; tapping a readout opens
numeric entry, with percentages displayed as 0–100 rather than 0–1.

Graphic faders move relatively in 0.1 dB ticks over 480 dp for the full ±12 dB range. Grabbing away
from the thumb no longer jumps the gain to that position. Single taps above/below it move one tick;
double-tap resets. Targets are 56 dp wide and thumbs are larger. Horizontal sliders also use
relative dragging and small tap nudges, with a 56 dp touch height. Pulling away from the track
while dragging slows adjustment further. Existing logarithmic frequency/Q mapping is preserved.
EQ nodes use relative movement and freeze the graph scale during a drag.

Sub-tick travel is accumulated, a small deadband prevents boundary jitter, and excess motion at a
limit is discarded so reversing responds immediately. Pointers use a damped spring without
overshoot. Crossing touch slop starts the knob gesture without advancing several ticks at once.
Local preview is immediate; audio edits are coalesced to one per display frame and
the last pending edit is flushed on release/cancellation. This is touch smoothing, not a new
audio crossfade or proof of click-free coefficient changes on every device.

Eight JVM tests cover residual motion, no initial snapping, jitter, limit reversal, finite input,
angular seam handling, gesture direction, and frame-edit/release behavior. The eight emulator
gesture checks exercise actual fader grabbing/tapping/release, horizontal slider grabbing/tapping,
sideways and rotary knob changes, and vertical centre scrolling. CI also retains the six EQ ownership/output checks, 39 existing
audio/routing checks, ten release detection checks and screenshots.

Emulator tests do not establish the feel on TECNO/IM4. Precise movement and fatigue still need
owner use on the phone. No new permission is introduced.
