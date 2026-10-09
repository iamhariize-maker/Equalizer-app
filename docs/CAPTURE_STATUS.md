# Per-app capture status (Hi-Fi → "Which apps can use the audiophile engine")

Capture is each app's choice and Android's, not Svan's. The card shows where every player Svan can see stands, from
evidence only: it never guesses from an app's name, and it never treats silence as a block.

| Standing | Evidence behind it |
| --- | --- |
| Full audiophile engine, active now | the router is carrying the app's audio on Engine B |
| Full audiophile engine available | capture was heard, before and after muting, on this installed version |
| System effects only: capture is blocked | the installed manifest forbids capture, **or** the audio server's own flags for the stream / UID policy forbid it (needs Enhanced detection to be seen live) |
| Checking / Not confirmed yet | a check is running, or checks heard nothing. Explicitly "not proof": silence can have other causes |
| System effects (your choice) | you set the app to System effects |
| Not checked yet | nothing observed for this version |

Rules the code keeps (`CaptureStatusRules`, unit-tested):

* A sighting of a block outranks an older success: an update can change an app's mind. Both are stored per installed
  version, so an app update clears them.
* A block sighting is display evidence only (`CaptureCompat.noteOptOut`, kept in its own preferences file). Routing
  never reads it; it re-checks live, and a later heard stream removes the sighting.
* Sightings come from: the router reaching a direct opt-out reason, the audio server's stream flags seen in a scan,
  and the Full diagnostic reading the policy.

Blocked apps are listed first because those are the ones that surprise people. Equalizer curve and dynamics still apply
to them through system effects.
