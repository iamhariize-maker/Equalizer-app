# Svaramanas research and technical direction

Research snapshot: **4 October 2026**  
Repository state reviewed: `ccr-208702a3-2mju42`, commit `b0527c3`  
Purpose: turn Svan's sound-intelligence vision into an evidence-led build order. This is an engineering
review, not evidence that Svan sounds better. No new quality claim should ship from these findings alone.

## Decision

**Resolve the reported playback delay/echo before adding adaptive EQ or an ML model.** The TECNO LH7n
report is real-device evidence; the current CI tone test cannot reproduce Bluetooth buffering, OEM routing,
or an audible second copy. More processing before proving the output path would make that diagnosis harder.

The strongest long-term design is a private, local **control loop around the existing measured DSP**:

```text
route + source evidence → bounded policy → existing DSP → level/clip/routing checks → blind listening
```

Use ML only as a slow controller for uncertain source labels or intent. Keep audio samples in the
existing C++ DSP chain. The research did not identify a ready-made model that can reliably tell, in a
mixed music stream, that a chosen instrument is masked and should be raised. An event classifier is not
that model.

## Ranked build order

| Priority | Increment | User value | Risk / gate |
|---|---|---:|---|
| P0 | Reproduce and remove the YT Music/Bluetooth delay or doubled path. Compare Svan stopped, Engine A only, and Engine B only on the TECNO; retain route and signal-level logs. | Very high | Current real-device defect. Do not tune the sound until the path is understood. |
| P1 | Test Pilot and evidence-backed player/route profiles. Report the engine actually selected, captured input energy, output energy, fallback reason, and queued audio. | High | Medium. A profile is specific to app version, Android build, route, and observed playback; it is not a permanent package-name fact. |
| P2 | Add local, randomized, level-matched blind A/B. Keep outcomes on device and separate preference from simple detectability. | High | Low technical risk; listener time is required before quality claims. |
| P3 | Prototype a small, optional dynamic suppressor with deterministic rules. Start with persistent narrow excesses and reductions only; test musical false positives and transient behavior. | Potentially high | High. Must pass signal tests and listening before default-on. |
| P4 | Personalization: exact headphone measurement when available, user-confirmed hearing profile, then conservative volume/noise adaptation. | Medium | High for uncalibrated hardware; user control and route-specific validation required. |
| P5 | Small on-device controller only if it beats the deterministic policy in held-out listening. | Medium | Medium/high. No network inference, no raw-audio model path, measured phone CPU and battery first. |
| P6 | Track memory only as an explicit opt-in, off by default, with local deletion and no content upload. | Low/medium | High privacy and Play review cost. Replay behavior is not proof of a favourite. |

## 1. Android can provide a best-effort global EQ, not universal adaptive access

Android's `AudioPlaybackCapture` was added in Android 10. Capture requires `RECORD_AUDIO`, a user-approved
MediaProjection session, compatible media/game/unknown audio usage, the same Android user profile, and a
source capture policy that permits capture by other apps. The source can disable capture. Android 14+
requires a fresh user grant per projection session and a declared `mediaProjection` foreground-service
type. This is why a static list of streaming-brand claims is not reliable.

Engine B can analyze only the samples Android actually gives it. Engine A's `DynamicsProcessing` can apply
session effects without seeing the source samples. Therefore:

- Say **adaptive source analysis is available on capturable playback**; use static route/profile/taste
  rules when the source cannot be captured.
- Keep capture fail-open: if the source is muted but captured audio is silent or the path becomes invalid,
  return the source to Engine A. Never leave a player silent and never output both the original and a
  delayed processed copy.
- Probe currently playing apps before opening Engine B's long-lived `AudioRecord`. Some Android audio
  stacks reject a second simultaneous playback-capture recorder. If a new, untested app appears after
  Engine B starts, leave it audible on Engine A and ask the user to restart capture with it already
  playing before attempting Engine B. Do not silence it while an unsupported parallel probe runs.
- Treat capture compatibility as an observation with expiry, not a universal app property. Include app
  version, Android API/device, route, date, observed input/output energy, and engine in a Test Pilot result.
- Record whether a test was measured, user-reported, or unknown. Normalization, player EQ, exclusive
  output, and codec should be `user-confirmed` or `unknown` unless the app exposes an actual signal.
- Do not request `QUERY_ALL_PACKAGES` just to enumerate every installed player. Android filters app
  visibility by default and Google Play restricts broad inventory access. Use declared queries for known
  media apps and current playback/session observations; let the user select a player when needed.

The owner-reported **echo-like** effect and simple delay are different failure modes. A single delayed
copy can be latency; two copies can mean the source was not muted, routing overlapped, or a transition
briefly replayed audio. The phone test should compare one engine at a time and capture the Svan routing
row plus source/output peaks. The displayed AudioTrack queue is only one part of total capture-to-ear
latency, especially on Bluetooth. AAudio's low-latency mode can reduce buffers for an app-owned stream;
it does not prove that a playback-capture → DSP → Bluetooth route is low-latency. Measure before
migrating APIs or promising video sync.

### New player and route evidence from the TECNO

On 5 October 2026 the owner reported: Apple Music had worked earlier with Fosi Audio IM4, but was no
longer detected on a wired connection; Neutron had not been detected; no music session appeared while
using Realme Buds Air 8, regardless of LHDC setting. This is **user-reported, not independently
reproduced**. The earbuds are output routes; the player app creates the audio session. A route or codec
name cannot be used to infer that the player session exists, that Engine B can capture it, or that
Engine A's session effect is attached.

The current detector reads the audio-service dump because Android does not offer a normal third-party
API for enumerating arbitrary players' session IDs. `dumpsys` line formatting is an implementation
detail rather than a compatibility guarantee. A missing app row therefore needs three separate facts:
number of playback-configuration entries, number with both a package UID and a nonzero session ID, and
which of those Svan routed. The UI now exposes those counts and an optional local sample of the lines,
so a parser miss can be distinguished from an absent or sessionless track. The parser accepts alternate
field separators and numeric usage/flag values seen across system dumps. Package visibility is explicit
for common streaming players (including Apple Music, Amazon Music, Spotify, Tidal, Qobuz and SoundCloud)
and local players (including Neutron, Poweramp, ONKYO HF Player, HiBy, FiiO Music, USB Audio Player Pro,
VLC, foobar2000, AIMP and Musicolet). This improves UID-to-name resolution only. Every app/route/mode
still needs its own observation; broad `QUERY_ALL_PACKAGES` access remains inappropriate.

Android's playback-capture contract depends on source usage, app capture policy, profile and projection
grant—not the name of an earbud or advertised Bluetooth codec. Neutron's direct/USB/bit-perfect settings
are a hypothesis to test separately, not a diagnosis from the missing row. Record the active player,
output route and player output mode while comparing wired and Bluetooth. If no attachable session exists,
Svan cannot attach a per-session system effect; if a session exists, the capture probe must still establish
whether Engine B receives non-silent samples.

References: [Android playback capture](https://developer.android.com/media/platform/av-capture) and
[AudioPlaybackConfiguration](https://developer.android.com/reference/android/media/AudioPlaybackConfiguration).

Android 14's USB mixer-attribute support is useful for apps that own their output stream. It does not
give Svan a general bit-perfect tap into another player's audio. Any active EQ changes the samples, and
an exclusive/direct player path may bypass system effects. Do not promise both bit-perfect output and
global equalization on that path.

### Test Pilot evidence record

Keep the first report routing-only. An export can contain:

- Svan build, Android API/device, tested player package/version, route type, and observation time;
- selected engine, whether the player session was detected, capture probe result, source input energy,
  Svan output energy, one-copy/double-copy verdict, fallback reason, output queue, underruns, and errors;
- a clear `measured / user-confirmed / unknown` label for normalization, own EQ, and exclusive output.

Do not include login/account data, track title, artist, listening history, or raw audio. Redact package
names in a shareable report unless the user chooses to include them. Cache capture verdicts only for the
app version and route that were tested; re-probe after changes.

## 2. Dynamic EQ and masking: useful theory, limited inference from a stereo mix

Bark/ERB bands and psychoacoustic spreading functions model auditory filters and masking. These are
valuable in perceptual coding, where the algorithm asks whether coding noise is likely audible under a
known signal. A mixed music capture does not reveal which sound source caused a local spectral peak or
which requested instrument is hidden by it. A loud guitar transient can look like a peak; a naturally
dark master can look like missing treble. Neither finding alone justifies a corrective EQ move.

In particular, **a low audibility margin is not a reason to boost blindly**. A broad boost can raise the
masker and consume headroom; EQ cannot recreate a vocal or cymbal component absent from the mix. A
future controller should require evidence that the chosen target is present, avoid synthesis/boosting
when that evidence is weak, and consider a small reduction to a persistent competing excess only when
the listening test supports it.

The existing `SourceAnalyzer` publishes slow, long-term balance and 30 third-octave levels from Engine B.
That is useful for source description and policy, but it is not a fast masking detector. If P0–P2 pass,
add a separate short-window control feature path. Keep it allocation-free; publish bounded targets to the
audio engine without taking a blocking lock in `Engine::process`. Sweep attack/release, hysteresis,
window and hop sizes on synthetic tests rather than treating a chosen timing as a psychoacoustic fact.

Suggested test-only suppressor protocol:

1. Synthesize masker/target pairs, stationary and transient, with known center frequency, bandwidth and
   level. Measure attenuation at the excess and change to nearby target bands, true peak, loudness,
   release recovery, and bypass identity through the actual `Engine`.
2. Include music-like multisine, mono/stereo, naturally tilted, low-pass, limited, and heavily distorted
   test material. Require no action when the detector has low confidence or when the mix is already
   within its own broad tilt.
3. Start optional and reduction-only. Preserve the current gain/Q/emphasis/headroom limits, add explicit
   slew and panic bypass, and log a short reason code. Do not expose “resonance removed” unless it was
   measured against a known target.
4. Run randomized, hidden, level-matched listening before making it default or describing it as clearer.

This is an original DSP direction; commercial processors' behavior can be studied, but do not copy their
code, presets, model weights, or undocumented internals. Keep the repo's existing no-GPL-copy rule.

### Bass controls: small starting moves, preference still unverified

The existing bass control combines a static low shelf with a transient attack/sustain shaper; positive
character also reduces a fixed 250 Hz band. That creates a sensible route to a more articulated bass
*shape*, but a synthetic kick response cannot establish that a listener hears it as better bass. The
`Clean impact` starter uses a +1.5 dB, 75 Hz low shelf and a −0.8 dB, 250 Hz peak. A new `Full impact`
option moves that profile to +2.5 dB at 68 Hz with a stronger punch setting and a small 250 Hz cut.
Other bass preset starting gains are capped at +2.5 dB, and the UI recommends
one tick at a time, same-passage and loudness-matched comparisons. Larger manual changes remain
available; this is not a claim that any preset is preferred. The owner should compare it at matched
loudness on both known earbud routes, then retain or revise it based on listening.

## 3. Clarity metrics: describe the signal; do not manufacture a clarity score

- **BS.1770 / EBU Tech 3341 loudness** and true-peak checks are appropriate for level control and the
  “never win by being louder” guardrail. They do not score clarity.
- **PEAQ / ITU-R BS.1387** is an objective perceptual audio-quality model intended for reference/test
  comparisons such as codec impairment. It is not an absolute “how good is this EQ?” score, and a
  suitable clean reference is required. Use it only as an offline research feature if its scope fits the
  material; do not use it as Svaramanas' main reward.
- **STOI/ESTOI and SII** concern speech intelligibility under specified speech/noise conditions. They
  can help evaluate a vocal-only speech test with a known reference; they do not rate full-mix music,
  tone, or audiophile quality.
- **Specific loudness, sharpness, roughness, spectral centroid, spectral flatness, and crest factor** are
  useful descriptive or trigger features. None is a general listener preference metric. A higher centroid
  can mean a brighter mix, not clearer detail.

For runtime, show narrow facts with their scope: “source is mono-like,” “energy above this range is low,”
“this correction is active,” or “captured samples are silent.” Avoid a single proprietary-sounding
“clarity score.” The current synthetic tests prove analyzer response to generated signals; only listening
can establish whether people prefer a correction.

### Listening protocol

Use the existing actual-engine loudness test as the engineering gate, and build the blind A/B tool before
any public quality language. For each comparison, randomize order, hide processing labels, play the same
excerpt through the same player/device/route, match loudness through the real chain (retain the current
≤0.5 dB limit; target about 0.1 dB in the test harness), and save response locally. Pairwise preference
answers “which did you prefer?”; ABX answers only whether the versions can be distinguished. Use
ITU-R BS.1116 for small impairment tests and BS.1534/MUSHRA-style methods where intermediate quality
comparison fits. Test several tracks and genres, include unchanged/sham comparisons, and report listener
count, route, and uncertainty.

## 4. Personalization and hardware-aware listening

### Headphones and hearing

AutoEq is MIT-licensed, but the current public AutoEq index contains no exact entry for the owner-provided
names **Fosi Audio IM4** or **Realme Buds Air 8** (checked against `results/INDEX.md` on 2026-10-04).
Do not choose a nearby product's curve by fuzzy name. Keep exact-name matching, show the measurement
source/rig, let the owner import a curve, and offer a generic taste profile only when no measurement is
available. A Bluetooth device name is not a measurement of the current headphone response; fit, tips,
seal, unit variation, codec and firmware can all change what reaches the ear.

ISO 226 contours describe normal equal-loudness levels under defined listening conditions; they are not
an individual's audiogram and do not calibrate the phone/headphone SPL. A future hearing check should
be a user-started, conservative preference/threshold exercise with frequency, route and device metadata;
it must not diagnose hearing or claim clinical correction. A phone volume percentage cannot be safely
translated into dB SPL across arbitrary headphones. Apply any compensation only with a clear cap, easy
off switch, explicit user preference, and route-specific listening evidence.

### Ambient noise

SII is a speech-intelligibility construct, not a music-detail metric. The phone microphone is not located
at the eardrum and is not calibrated across device models or microphone orientations. Do not auto-raise
overall volume from raw microphone level. If a later speech-priority option uses the microphone, make it
user-started, separately permissioned, foreground-visible, local-only and limited to a conservative
speech-band change; disclose the microphone use and store no ambient recording.

### Track memory

Reading active media sessions requires an enabled notification listener (or privileged access).
Titles/artist/duration and seek behavior are sensitive. “Most replayed” can be a seek error, a chorus, or
a buffering retry; label it as an inference. Keep this off by default and ask for specific opt-in before
collecting any playback position or metadata. Prefer a per-install keyed hash over a plain salted hash;
keep all data local, let users inspect/edit it, and provide one-step wipe. Do not keep playback history
to make an audio-quality claim.

## 5. Lossy streams, services, and codec claims

The playback-capture interface gives Svan PCM samples, not the original compressed bitstream. A high
frequency cutoff is a **bandwidth observation**, not a reliable MP3/AAC/Opus fingerprint: the mix itself,
mastering, transcode history, sample rate, and playback resampler can all make similar spectra. Keep the
existing cutoff estimate descriptive and confidence-gated; never state “this is 128 kb/s MP3” from a
spectral ceiling.

Current official consumer settings retrieved on 2026-10-04 illustrate why a static service table goes
stale:

- YouTube Music lists upper bounds of 48, 128 or 256 kb/s AAC/Opus by quality setting; “Normal” is the
  default unless changed. This is not the Bluetooth output bitrate.
- Spotify's current support page lists lossless up to 24-bit/44.1 kHz FLAC for supported accounts and
  devices, alongside lower lossy settings.
- Apple Music documents lossless up to 24-bit/192 kHz, and explicitly says Bluetooth connections are
  not lossless.
- An Amazon Music rate was not independently verified from an accessible current first-party page in
  this research pass. Keep it `unknown` until checked against the user's exact plan/app/device.

Even a lossless service source becomes a route-specific Android output stream; Bluetooth and system
processing may resample or encode it. Svan should report only the active route/engine and facts it can
observe. No bandwidth-extension model can restore information that is absent. Any optional
reconstruction should be a separate, reversible effect, tested against known full-band references and
blind listeners, and described as generated texture—not restored original detail.

## 6. On-device frameworks and model recommendation

**Framework:** Google LiteRT is the strongest first fit if (and only if) an evaluated controller is
justified: Android support, quantized deployment and platform acceleration are available. Keep inference
in a Kotlin worker/control loop or other non-audio thread, with CPU fallback. Do not add LiteRT to the APK
for the current deterministic policy alone.

**Candidate baseline:** YAMNet is a useful published sound-event classifier (521 AudioSet labels,
MobileNetV1 depthwise-separable architecture). It can test broad event labels such as singing/instruments,
but it is not a music source separator and cannot tell that a vocal is masked by a guitar. Treat it as an
offline semantic baseline/possible teacher, not as the production “AI clarity” model. Verify model/data
redistribution rights before bundling weights.

**If rules lose in blind tests:** train or distill a compact, int8 temporal classifier/controller for a
specific task such as `vocal_present` or `priority_source_confidence`. Use short log-frequency features
from captured Engine B audio; infer every roughly 50–200 ms; output confidence and a small parameter
target set, never audio samples. Keep all predictions inside the existing `Guardrails`. Compare it to the
rule baseline on songs/artists held out from training (window-level random splits leak song identity).
Require improved listener preference or a clearly measured classification benefit, plus an arm64 TECNO
latency/battery check. A model output must carry a reason/confidence so low confidence means “do less.”

Do not run a large generative audio restorer, speech denoiser, or source-separation model in the live
music path. Their latency/CPU and music artifacts conflict with the current low-latency, no-harm
architecture. Speech-only enhancement models should not be repurposed as music clarity models without
separate evaluation.

## 7. Platform, privacy, battery, and release work

- **Playback grant/foreground service:** explain why playback capture is needed before asking. Android
  14+ MediaProjection requires a fresh consent token for each session and the `mediaProjection` FGS
  declaration. Keep a visible stop control and make Engine A / the app's normal controls available when
  consent is denied.
- **Overlay:** `SYSTEM_ALERT_WINDOW` is special Settings access and must remain optional. Keep in-app,
  Quick Settings and notification controls complete when overlay is denied. Review the exact current
  Play policy/disclosure before listing.
- **Track memory:** adding session-history access introduces notification-listener user approval and
  separate disclosure. It is not needed for the EQ core; do not couple it to first-run setup.
- **Package visibility:** use narrow `<queries>` already present in the manifest. Avoid a broad installed-
  app inventory permission unless Play explicitly approves the user-facing core-function justification.
- **OEM:** Android emulators cannot certify TECNO/ColorOS/MIUI background behavior. Keep OEM tips as
  device-specific, optional deep links; never tell users to disable protections until a repro shows it is
  needed. Validate 30-minute screen-off playback and battery on the actual phone.
- **CPU/latency:** benchmark the existing 1×/2×/4×/8× modes on arm64 and Bluetooth/wired routes. Keep
  “output queue” separate from end-to-end delay. Avoid changing to AAudio or exclusive modes without a
  before/after acoustic measurement on the target phone.

## 8. Concrete acceptance gates

1. **Routing:** current CI has the existing synthetic checks for one processed copy, capture-blocked
   fallback, T20 fail-open and Svaramanas analysis. All must pass. On TECNO, repeat with YT Music over
   Bluetooth and, separately, speaker/wired output: no echo, identified player, non-silent input/output,
   stable screen-off session, and a recorded subjective latency report. CI is not a substitute.
2. **Any new DSP:** synthetic tests measure target/notch response, neighboring-band damage, clipping, true
   peak, loudness match through `Engine`, slew, bypass identity, and recovery. Tolerance and all gains are
   explicit. Audio-thread callback remains allocation-free and wait-free.
3. **Quality:** paired blind listening at matched level comes before “clearer”, “more detail”, “AI clarity”
   or “sounds better” copy. Store results locally and state the listener/device/route limits.
4. **Model:** benchmark p50/p95 inference, CPU and battery on the TECNO; compare against deterministic
   policy on held-out songs; fail to policy-only on errors or low confidence. No network request or
   audio upload.
5. **Privacy/Play:** permission rationale, overlay fallback, track-memory opt-in, deletion, policy URL,
   Data Safety and FGS declaration all match shipped behavior before store submission.

## Sources

Primary platform, standards, model and service sources reviewed:

1. [Android: Capture video and audio playback](https://developer.android.com/media/platform/av-capture) — capture permission, same-profile, usage and source-policy requirements.
2. [Android: Media projection](https://developer.android.com/media/grow/media-projection) — consent and session-token behavior.
3. [Android 14: Foreground service types are required](https://developer.android.com/about/versions/14/changes/fgs-types-required).
4. [Android: AAudio](https://developer.android.com/ndk/guides/audio/aaudio/aaudio) — low-latency mode, buffers and stream sharing.
5. [Android 14: feature/API overview](https://developer.android.com/about/versions/14/features#usb-lossless-audio) — USB lossless/mixer-attribute support.
6. [Android: Package visibility](https://developer.android.com/training/package-visibility) and [Google Play: `QUERY_ALL_PACKAGES`](https://support.google.com/googleplay/android-developer/answer/10158779?hl=en).
7. [Android: `MediaSessionManager`](https://developer.android.com/reference/android/media/session/MediaSessionManager) and [`NotificationListenerService`](https://developer.android.com/reference/android/service/notification/NotificationListenerService).
8. [ITU-R BS.1770-5](https://www.itu.int/rec/R-REC-BS.1770-5-202311-I/en) and [EBU Tech 3341](https://tech.ebu.ch/docs/tech/tech3341.pdf) — programme loudness/true peak and EBU meter behavior.
9. [ITU-R BS.1387-2 (PEAQ)](https://www.itu.int/rec/R-REC-BS.1387-2-200109-I/en); Thiede et al., [PEAQ: The ITU Standard for Objective Measurement of Perceived Audio Quality](https://www.ee.columbia.edu/~dpwe/papers/Thiede00-PEAQ.pdf).
10. [ITU-R BS.1116-3](https://www.itu.int/rec/R-REC-BS.1116-3-201502-I/en) and [ITU-R BS.1534-3](https://www.itu.int/rec/R-REC-BS.1534-3-201510-I/en) — controlled small-impairment and intermediate-quality listening methods.
11. ISO, [ISO 226:2023 — Normal equal-loudness-level contours](https://www.iso.org/standard/83117.html).
12. Taal et al., [Short-time objective intelligibility measure for time-frequency weighted noisy speech](https://doi.org/10.1109/TASL.2011.2114881), IEEE TASLP, 2011.
13. Google AI Edge, [LiteRT](https://ai.google.dev/edge/litert) and [LiteRT for Android](https://ai.google.dev/edge/litert/android).
14. TensorFlow Hub, [YAMNet sound classification](https://www.tensorflow.org/hub/tutorials/yamnet) — AudioSet 521-class model and architecture.
15. AutoEq [repository](https://github.com/jaakkopasanen/AutoEq), [current result index](https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/INDEX.md) and [MIT license](https://github.com/jaakkopasanen/AutoEq/blob/master/LICENSE).
16. Official service pages: [YouTube Music quality settings](https://support.google.com/youtubemusic/answer/9076559?hl=en), [Spotify audio quality](https://support.spotify.com/us/article/audio-quality/), and [Apple Music lossless](https://support.apple.com/en-us/118295).
