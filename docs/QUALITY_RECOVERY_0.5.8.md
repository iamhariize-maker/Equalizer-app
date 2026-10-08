# Quality and hidden-player recovery — 0.5.8 / code 15

## Owner evidence and scope

On 8 October 2026 the owner reports on LG LM-V600 / Android 13 that Amazon Music
still has no usable music-session connection. Shared-output EQ gives little useful change.
They confirm Amazon's own EQ option does not open Svan on this installation.
They also report foggier, overprocessed playback with EQ/tuners enabled, which improves when
those controls are off while Engine B remains running. This is listening evidence, not a
measured cause or proof that every capture/output route is transparent.

The supplied 0.5.7 report has an Apple Music session routed to Engine B, nonzero capture,
48 kHz client rates and zero reported output underruns. Its anonymous public playback count
cannot identify Amazon. A remembered Apple session cannot identify whatever is playing now.
Its NO_PERMISSION headline describes unavailable enhanced reports but obscures the known
basic route. No tuner/EQ snapshot was included, so the settings causing the complaint are
not known. Original source/DAC rates remain unknown.

## Strategy and implementation boundaries

1. Establish a flat reference at the actual engine configuration. Check delayed null,
   response, channel leakage, silence, transient behavior and block-size independence;
   retain the existing oversampling, protection, spatial and recording regressions.
2. Test dormant-state resumption and live knob/mode transitions. Filters that were suspended
   must not replay old music when re-enabled. Clear only suspended histories; preserve
   active shared paths and the existing crossfades/fixed latency.
3. Coordinate overlapping corrections. A manual Smooth choice and automatic upper-band
   correction must not independently spend their full attenuation on the same mid signal.
   Keep manual intent, low-band correction and side correction; prove both combined response
   and continuous transitions before shipping a budget change. The implementation shares the
   stronger of manual and automatic attenuation at the two upper automatic lane centres,
   3 kHz and 6.5 kHz. It uses the actual, sample-aligned manual de-harsh coefficient and its
   complex filter response; it does not switch detector basis when the knob crosses zero.
   This is a lane-centre contract, not a universal broadband attenuation limit.
4. Expose current effective bands, tuner amounts, smart layer and spatial blend in the local
   report. This distinguishes requested settings from route evidence; it does not certify
   the Android mixer, headphones, original master or acoustic result. Delayed automatic
   headphone downloads also recheck the live request, device and unchanged tuning identity
   on the UI thread, so an old result cannot replace a manual choice made during the download.
5. Compare the actual troublesome passages with the existing level-matched blind renderer
   and sample-aligned dry/processed Recording mode exports. Test one tuner at a time, then
   combinations. Do not tune a global tonal target from a song's codec or advertised HD label.

Smoothing changes transitions. It cannot make a large settled EQ boost, mid/side rebalancing
or dynamic cut neutral. Width/focus controls intentionally change imaging; a stationary image
is not guaranteed to stay in its original position when those controls are raised. This work
preserves the controls and their explicit manual ranges instead of claiming that every amount
can preserve the original recording unchanged.

## Amazon and native processing

The supported permission-free identity inputs remain actual player announcements and the
standard Android effect-panel contract. If Amazon exposes an Equalizer option, opening Svan
there may provide a session ID; support must be checked in the installed player version.
Package visibility, a selected app name or anonymous activity is not a session ID. The owner
has checked Amazon's EQ command and it does not hand off to Svan on this installation;
this command is therefore not a demonstrated solution for their Amazon playback.

Engine B needs capturable audio and a separately controllable original source. Without that
original source mute, replay doubles the music. A shared-output mute also reaches Svan and
other sounds on the same output. MEDIA-only playback capture can exclude ordinary notification
usages from capture, but cannot make a whole-output mute notification-safe. Therefore this
update does not send an unidentified shared output through native replay or claim Amazon HD
is fixed. No notification listener, accessibility, SMS, storage or new privileged permission
is introduced, and no source session is guessed or brute-forced.

An explicit shared-output recovery action can stop Hi-Fi before trying the existing system
effect, including when a remembered Apple connection would prevent automatic fallback. The
capture output must close before source mutes are released. Cancellation, service stop and
physical device changes cancel the pending handoff; virtual remote-submix events still
trigger discovery but cannot cancel a handoff caused by closing capture itself. This remains
an experimental system-EQ option, not native DSP or a commercial-player compatibility claim.

## Single-take recording additions

The branch's updated recording brief also requests Before/After buttons and an offline A/B
timeline. They mark committed frames without changing live listening. The timeline starts
at the first press; an optional sync variant starts at the first sync, with Before used before
any choice. Five-millisecond equal-power source fades and smoothed gain changes happen only
in finishing. RMS matching defaults on for this timeline, independently of the existing
default-off full processed matched export. Plain WAVs, sync cue, M4A and settings charts remain.
See [RECORDING_MODE.md](RECORDING_MODE.md).

## Verification

Local native Release and ASan/UBSan suites pass all 164 tests. ThreadSanitizer passes all
five parameter-publication tests, including two concurrent tuner writers. Android debug/release
build, lint and all 230 JVM tests pass; 17 Python checks pass. Final emulator CI and original-key
production verification pass as documented below. No existing CI gate is removed;
the DUMP-free integration suite grows from twelve to fourteen checks. Recording checks grow from six to seven, retaining their
original assertions and exercising the actual Before/After buttons and MediaStore files.
The eight A/B helper tests cover exact saved PCM outside fades at 44.1/48/96/192 kHz in
16/24-bit, rapid/duplicate switches, crop positions, +6 dB matching within 0.05 dB,
smoothed gain boundaries, TPDF and peak-cap reporting. An opposed 1 kHz tone at -6 dBFS
passes a maximum sample-step bound of 0.085 at 48 kHz during its switch. This controlled
bound is not a universal inaudibility claim. Two recorder integration checks verify committed
frame positions, plain-file preservation, default matching and sync variants.

Measured synthetic regressions: suspended Fast/Detailed histories previously replayed a
2.227e-3 peak at 48 kHz after two seconds of silence; the repaired complete-group test stays
silent. At 3 kHz, Smooth 0.25 plus automatic amount 1 previously cut the mid tone by
3.840 dB, versus 2.340 dB from Smooth alone; the coordinated result is 2.340 dB. At 6.5 kHz
the corresponding combined cut is 1.500 dB, preserving the stronger correction. These are
controlled tone tests, not a diagnosis of the owner's music. Sample-alignment error is zero
at 44.1/48/96 kHz; tested knob transitions are identical at block sizes 1/127/1024. The
EXTREME flat-reference delayed-null maximum error is at most 2.24e-7 across those rates;
this does not establish Android/DAC or acoustic transparency. Audio processing still passes
the zero-allocation regression.
Owner listening, Amazon session/route support and physical recording checks remain open in
[PHONE_VALIDATION.md](PHONE_VALIDATION.md).

Initial CI 37743497503 passed build/native/sanitizer and all five compatibility jobs. Its
focused basic suites preserved all twelve earlier assertions and passed the new truthful-route
assertion, but failed the new handoff setup: capture was requested before the real source
route attached, and the production guard correctly blocked startup. The fixture now awaits
the announced route, grants only the existing capture notification permission, and requires
a confirmed Engine B route before handoff. The capture guard and fourteen assertions remain.
Source-filter runs also retain each poll and pre-clear player logs so a future failure can
be diagnosed. The final CI and signing evidence below supersede this initial candidate.

## Final CI and owner-signed APK — 8 October 2026

[CI 37745825272](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37745825272)
at `35c9bd7e1630f5cca7814784a79e00221260e481` passes all eleven jobs: native,
Android build/lint/unit tests, both dedicated DUMP-free suites, both full emulator suites,
and release compatibility smoke on APIs 29/30/33/35/36. Native sanitizer and publication
checks pass. All completed job logs were reviewed; neither full emulator log contains a
reported FAIL. The final Android production fixture is used by both production-mode suites.

Both API 33/34 full artifacts retain these PASS counts:

| Gate | PASS on each API |
|---|---:|
| Routing | 41 |
| Detection / detection onboarding | 13 / 6 |
| Workspace / precision controls | 9 / 8 |
| Quality / continuity | 4 / 4 |
| Source filtering | 3 |
| Onboarding / fallbacks | 8 / 3 |
| Screen interactions / audio-quality layouts | 16 / 7 |
| Recording / DUMP-free detection | 7 / 14 |
| Production / release smoke | 4 / 1 |

Artifacts `e2e-results-api33` (11537752284) and `e2e-results-api34` (11537473332)
were downloaded and reviewed. The frame-clock/Before/After/Sync/Mark/Stop panels fit the
small emulator screen, remain present over EQ, and use the existing gold/white/charcoal
palette. Countdown, manual segment, settings charts, effect-panel/shared-output screens,
boot and representative tuner/large-font layouts were inspected. Screenshots do not prove
physical camera readability, speaker route or flash/click timing. The flash-attempt capture
is not a measurement of cue latency.

Both recording fixtures report 48 kHz, 16-bit TPDF exports and zero dropped frames. Their
exact first syncs are frame 208896 (API 33) and 216064 (API 34); Before/After presses and
timeline crops use full recording frame positions. Both nonempty A/B files have correct
48 kHz stereo PCM headers and lengths. Matching defaults on and reports five-millisecond
fades plus per-range gains. These are fixture observations, not player or phone guarantees.
The dedicated DUMP-free checks also measure the requested -6 dB system EQ cut as -5.9965 dB
for their host 1 kHz tone, including after the explicit capture-stop handoff. This does not
establish the owner's Amazon shared-output route.

The private deliverable is `Svan-0.5.8-owner-signed.apk`, version 0.5.8/code 15,
5,621,999 bytes. It comes from production artifact 11535749036 in that same final run.
After re-signing, all 73 ZIP payload entries match the tested production fixture byte for
byte. The original owner certificate SHA-256 is
`9cb9daca3b49fbdd17683d45dfb069fa9c6d05e3733934795f92546fef696b0f`.
Release verification passes package/version, single signer, permission allowlist and
16 KB ELF/ZIP alignment for all four native libraries. APK SHA-256:
`7ca911f596d2028818c1a4cd3604dd736061b40233badbc1912c82637d2a8a45`.
Key/recovery material remains outside Git and CI; no public release or PR is created.

Unlike the unchanged native payload in 0.5.7, this version deliberately changes native DSP
to repair the tested defects. Existing spatial and other feature gates remain. Install over
the current original-key Svan without uninstalling; owner-phone checks, subjective quality
and Amazon's hidden-session native support remain open in PHONE_VALIDATION.md.
