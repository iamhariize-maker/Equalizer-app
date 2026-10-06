## GitHub beta onboarding — 2026-10-06

Owner extended release preparation to first-run/detection UX; work stays on
`ccr-f859b567-dgrdoj`. No release/PR/Pages/Play submission is made. Original-code licence
is All rights reserved. The uploaded owner APK at a603146 is unchanged and predates the UX:
new owner signing and accurate asset hash/source notes are required to distribute it.
Version/signing config and manifest remain unchanged; do not publish a CI test signer.

Read OnboardingState.kt/OnboardingAndroid.kt and ui/OnboardingUi.kt for the new UI policy.
First run reaches System effects with no setup gate. Existing bootstrap enabled automatic
Svaresa; only genuinely fresh installs invoke the existing complete Flat reset. Saved sound,
updates and restores are not reset. No DSP/chain/router/grant implementation changes.
DetectionGrantService.kt is absent on this branch; ShizukuDetectionGrant.kt is the existing
fixed own-package DUMP-only binder grant and remains untouched.

Wizard observations/polling are limited to resumed UI. Errors use plain retry messages;
raw grant failures remain in the existing bounded local audio log. Debugging reads never
substitute false for missing/denied keys. Success uses existing gold rather than a new green,
and makes no banking-app promise. Shizuku is stopped only by the user's own action.

The contextual card uses actual activity plus missing session/DUMP evidence. Named dismissals
are per package. Anonymous public configurations cannot identify an app; “A player” and an
anonymous dismissal key are explicit limitations. Reset hidden prompts is always reachable.
Hi-Fi status separates idle/unreachable/routed and unknown observations; service-running is
not success. Copy diagnostic summary is local and limited; detailed sharing stays explicit.
Bundled compatibility rows distinguish synthetic/earlier-owner/unverified evidence.

Release verifier CI initially failed on SDK 36.1's per-scheme certificate labels; commit
3008eb5 accepts those plus SDK 36.0, requires one signer and one consistent certificate.
Both SDKs locally pass the owner APK and wrong-cert/additional-permission rejection tests.
Baseline CI 37414104556 and onboarding CI 37414766431 both passed all nine jobs.
Their logs and API 33/34 result artifacts were inspected: the 39 original audio checks pass
before/after, all 34 median levels per API match at 0.1 dB precision, and native libraries
are byte-identical. Two min/max endpoints vary by 0.1 dB; this is not a bit-perfect sample claim.
Onboarding adds eight real first-run/prompt and five live wizard checks per API. All original
detection/workspace/control/quality/production checks pass. Final local checks are 98 core,
132 JVM, debug/release builds and lint, plus verifier positive/two negative cases.
All wizard/status fixture images and representative live images were viewed at phone size.
Follow-up fixes reset/clip help-panel scrolling, correct full-screen footer insets and fixture error/progress labels;
screens.sh now asserts their presentation. Inspect final branch CI and its images as well.
CI 37418664855 caught remaining dialog footer clipping; setup is now an inset-aware app page,
and UI tests inspect clickable ancestors rather than TextView labels. A capture-start timeout
logged an intermediate conflicting-UID safety stop while two recreated sessions still existed.
The single-source fixture now waits for old-route retirement. SessionRouter/processing stay
unchanged; multi-session capture startup remains a known limitation needing separate work.
Existing 39 measured audio checks are byte-for-byte unchanged, with a separate UX prelude.
See ONBOARDING_VERIFICATION.md for measured values, hashes, run links and limits.
See SETUP.md and PHONE_VALIDATION.md for live versus labelled fixture screenshots and gaps.

## Public beta preparation — 2026-10-06

Owner requested release verification/automation and Play documents only, starting at `a603146`.
Work stays on `ccr-f859b567-dgrdoj`. Beta notes and the bug form are reused from
`ccr-aa3465ac-bgvou9`, without merging that branch or changing app/DSP code, permissions,
signing configuration or version metadata. Owner's licence decision: original Svan code is
**All rights reserved**; third-party notices remain applicable (LICENSE).

New `verify_release.sh` checks public certificate, package/version, exact compiled permission
allowlist and 16 KB ELF/ZIP alignment, then prints the actual file SHA-256. CI exercises a real
disposable-signed production-mode fixture plus wrong-certificate/additional-permission failures;
the owner key is never needed. The owner APK passes local verification with the supplied hash.
Core local baseline: 98 tests, zero failed checks. Android debug/release assembly, lint and
102 JVM tests pass locally. New push CI results are pending at writing;
do not infer a green run from older or cancelled checks.

`release-verify.yml` has a read-only download/verification job and a separate contents-write
checksum attachment job: GitHub cannot attach release assets with a read-only token. No custom
secret, signing or sensitive comment is added. Runtime release upload is unverified until an
owner-created published release exists. The workflow must be present on the default branch;
the older default branch has not been changed. See RELEASING.md for owner setup/rollback.

Play drafts have source-line evidence and explicit unresolved network/support-data handling,
permission/FGS review and real-device gaps. Privacy HTML is prepared, Pages is not enabled,
and contact/retention plus an in-app policy entry remain owner/future implementation work.
The signing A/B choice is explicitly undecided. No release, PR, Pages deployment or Play
submission is created by this task. After each push, inspect CI logs and screenshots.

## Quality engine continuation — 2026-10-05 (0.5.5 preview, code 12)

Built true-peak estimation/lookahead, bounded selective dynamic EQ, measurement-derived headphone
correction with provenance/amount controls, and an attenuation-only blind matched-listening lab.
See [quality lab](QUALITY_LAB_0.5.5.md) for architecture, proof, CPU/latency tradeoffs and limits.
The 0.5.4 exact delivered artifact passed all seven jobs in CI 37347862654; native/JVM 86/94,
39 audio, 9 workspace, 8 controls, 10 release checks. New local/CI results are pending at writing;
deliver 0.5.5 only after its exact release quality checks and screenshots pass.

Owner then requested their digital signature for the Play route and confirmed no existing key or
Play upload. A private RSA-4096 upload key and recovery files were created outside Git/GitHub.
Production mode uses that private identity, version `0.5.5`, code 12, and disables all Activity test
commands. Settings export/restore is added for the unavoidable preview→private signer migration.
CI builds production APK/AAB with a disposable test key and checks public UI/native quality paths;
owner delivery re-signs those exact tested payloads privately. See RELEASE_SIGNING.md. No Play
publication or approval is claimed. The owner must download and securely back up the private key.
The current Play target policy requires API 36 for new submissions; compile/target SDK and build
tools are updated accordingly (AGP 8.9.2, Gradle 8.11.1), with minimum API 29 retained and a fifth
compatibility job on API 36. The owner also prioritises LG V60/Android 13; full e2e coverage now runs
on API 33 and 34. The release verification record must therefore cover nine CI jobs. LG's actual
Quad DAC, playback routes and OEM background behavior remain real-device validation.

## Engine quality continuation — 2026-10-05 (0.5.4 preview, code 11)

Owner requested the current APK first (0.5.3 was delivered), then broader Svaresa engine authority
only where it improves audio quality. Built native EQ crossfades and gain ramps, protection authority
that preserves manual choices, live capture protection updates without rebuilding, and removal of
per-edit limiter-history resets. See [engine quality](ENGINE_QUALITY_0.5.4.md) for measured transition
cases, tests and limits. Native/JVM suites are 86/94; EQ workspace checks grow 6→9, retaining the
eight gesture, 39 audio and ten release checks. CI is pending at writing; inspect it and screenshots.

The delivered 0.5.3 commit `1545af2` passed all seven jobs in
[CI 37340722679](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37340722679), including
all gesture/output checks and API 29/30/33/35 compatibility. Touch screenshots were inspected.
Its 80 native/91 JVM checks and fixed preview signature are verified; TECNO/IM4 listening remains
owner validation. The final two commits changed test visibility only, not app source.

## Extended EQ ownership — 2026-10-05 (0.5.3 preview, code 10)

Owner reports 0.5.2 was successful and requests app-wide refinement, specifically usable Extended EQ
with Svaresa directly controlling its parametric/graphic bands **by default**. They also require careful
handling of overlap for bass, vocal and treble preferences. No permission changes or new analysis tap.

Svaresa now owns the EQ workspace rather than adding its bands to a hidden manual EQ. Manual bands,
mode, graphic count and gains remain separately stored. The upgrade/new-install migration selects
Svaresa once; explicit manual override persists thereafter. Re-enabling Auto master from its panel
reclaims EQ ownership. Applying a preset is an explicit manual override. Guided mode retains its
existing taste behavior, and can use the owned workspace if entered from Auto master.

Live nodes/faders are the actual applied automatic filters. Fixed-centre graphic layouts use a native
response fitter, followed by bounded personal gain preferences, summed-response boost guard and
level matching of the actual slewed curve. A 6 dB positive-response cap reduces only positive
assistant gains; cuts remain intact. User tuning/bass layers remain separate, and predictive headroom
covers their combined system EQ. This is not a guarantee against every transient/intersample overload
or of listener preference. Capture-only analysis and system-effect limits still apply.

Manual EQ: entire page scrolls, faders have 48 dp touch width and accessibility progress semantics,
precise value entry, per-band gain steps, grouped undo and response-preserving layout conversion
(exact graphic→parametric; bounded approximate parametric→graphic with RMS/max error exposed).
Legacy all-bell graphic layouts retain their sound until explicitly converted; new/fitted layouts use
shelf endpoints. No overwritten headphone correction or invented IM4 compensation.

Local/CI verification details and listening limits: [EQ workspace](EQ_WORKSPACE_0.5.3.md).
Native suite grows 73→80, JVM 74→83; existing 39 audio/routing and 10 release detection checks stay,
plus six new measured EQ workspace checks. CI screenshot script explicitly selects GUIDED before
guide captures (fixes the misleading prior filenames), and captures auto/manual band controls.
Final CI and artifact checks are pending at writing; distribute only after all pass and screenshots
are inspected. Preview signing remains unchanged for in-place updates.

# Handoff — Svan (Svanam Shreshtham: Ultimate Sound)

Written at the end of a long Claude Code session so another agent (Codex cloud) can continue.
Repo: `iamhariize-maker/Equalizer-app`, current branch **`ccr-f859b567-dgrdoj`** (not merged; PR #1 is open for it).
Start with `AGENTS.md`. This file has the detail.

## Combined sound refinement — 2026-10-05 (0.5.2 preview)

The owner reports 0.5.1 detection working so far, currently listening with Fosi IM4 and starting to
be impressed. They requested stronger, clearer instrument/vocal/ambience results in both the guide
and Auto master. This build combines the permission removal below with targeted sound refinements.
No new permission is added; working streaming-session discovery is preserved.

Built: total mid + side spectral analysis with allocation-free dual FFTs, appended M/S feature
packing (legacy prefix accepted); accurate static M/S level model sharing live filter coefficients
and complex crossover sum; strength now scales all guide processors; paired presence/masking
profiles; stronger measured Svaresa excess correction with unchanged 4 dB bounds/dead bands.
The controller matches the combined, slewed guide/context bands instead of adding two guesses.
Negative smart trim can reach -18 dB for extreme stereo requests; positive makeup remains capped
at +1.5 dB. Guide-only transient suggestions are restrained (max 0.15); the manual processor is
unchanged. Runtime/UI matching is explicitly estimated when no audio has been captured.

Tests first exposed side-only music being missed, ineffective stereo strength and up to +2.58 dB
unintended full-guide loudness. Native validation now enables every suggested processor. Checked
synthetic cases stay within 0.25 dB; six foreground/masking contrasts are 1.70–3.02 dB. Side-only
mud is reduced 4.03 → 2.96 dB at unchanged loudness. Details, reproducible cases and limits:
[Sound validation](SOUND_VALIDATION_0.5.2.md). The combined local/CI results will accompany the APK.
No IM4-specific measured correction was invented. Engine A still lacks live analysis; blocked streams
receive the selected guide and output/volume/night context, not fabricated mix intelligence. Stronger
contrast is measured; listening quality, commercial streaming/TECNO behavior and Play Protect
classification of this APK still need phone validation. Do not claim unparalleled quality.

## Notification-access removal — 2026-10-05 (0.5.2 preview)

The owner explicitly requested removal of player recognition's setting and
permission. They reported the **latest 0.5.1 APK** was blocked by Play Protect
with a possible security/financial-fraud warning, requiring protection to be
turned off to install. They also report music recognition/detection working in
this version so far; no new DUMP/session report was supplied, so this is listener
feedback, not a complete commercial-player/route validation matrix.

Removed the notification-listener service/manifest declaration, optional card,
consent/settings links, media-session reader, identity/state models, diagnostic
fields, health logic and recognition-only checks. Retained the working direct
Shizuku grant, audio reports, callbacks, broadcasts, parser hardening and silent
capture startup guard. The combined build also refines sound (see below). Synthetic test players still publish media
sessions, but Svan does not read them. The APK permission gate checks the compiled
manifest, including service permissions, to prevent notification/SMS/accessibility
capabilities returning through dependencies.

Google's [developer warning guidance](https://developers.google.com/android/play-protect/warning-dev-guidance)
identifies notification-listener access as a trigger for financial-fraud installation
blocking of internet-sideloaded APKs in supported markets. The 0.5.1 declaration
matches those criteria; this is a **likely explanation**, not proof of the phone's
exact classification. Removing it is not a Play Protect approval or malware
certification. Keep Play Protect enabled. If the replacement is blocked, obtain
the exact message and follow Google's developer investigation/appeal path.
No external scanner or appeal was submitted in this session.

Local debug/release builds, lint and 74 JVM tests passed; the permission-only native suite was
65/65; the combined sound refinement now has 73 tests. The compiled 0.5.2 APK passes the capability gate and the actual 0.5.1 APK
fails it for its listener service/permission. Preview signing matches the installed
0.5.1 certificate. New CI is pending at writing. The prior 0.5.1 exact
release passed [CI 37288622066](https://github.com/iamhariize-maker/Equalizer-app/actions/runs/37288622066):
65 native tests plus sanitizers, 81 JVM tests, 10 release checks, 45 e2e checks,
compat API 29/30/33/35; small-screen screenshots inspected. New e2e retains 39
checks (the six removed checks existed only for the deleted permission feature).
`detection_release.sh` still requires exactly 10 passes, including the real direct
Shizuku grant and measured output after Shizuku stops.

Preview signing is still the public development key for in-place updates; it
cannot establish exclusive publisher identity. A private production/Play signing
key is a separate release requirement; do not distribute it or copy the preview
key into production. No signing rotation was performed in this permission-removal
patch. Remaining permission purposes and limits are in `docs/PERMISSIONS.md`.

## Streaming-first detection continuation — 2026-10-05 (0.5.1 preview)

Owner clarified: Spotify, Amazon Music, YouTube Music and other popular streaming
players are first. Neutron and advanced local/direct-output players come later.
Detection remains the top priority, ahead of new sound-changing features.

**New TECNO evidence:** owner pasted the 0.5.0 report at 2:17:09 PM: DUMP=false,
RECORD_AUDIO=true, service running, no dirty shutdown, battery optimisation not
ignored; public active=2 including capture output; zero discovered sessions/routes,
capture peak=0 and `muted=[]`. This establishes missing discovery permission and
an empty capture allowlist, not a parser, Bluetooth, offload or HiOS-kill cause.
The cached YouTube Music CAPTURABLE verdict does not identify the active player.
Selected, sanitized report evidence is a regression fixture under
`android/app/src/test/resources/detection/`; there are no real raw audio tables yet.

**Follow-up evidence (2:30 PM screenshots / 2:32:09 PM report):** Shizuku 13.5 is
running under adb and shows an authorized application, while Svan displays
"Setup timed out. Open Shizuku and check that it is running, then retry."
DUMP is still false at 486 scans. The previous recommendation to start Shizuku
does not address this failure. It establishes a setup-helper timeout; why the
UserService never completed on HiOS is not proven.

**Implemented:**
- Replaced Shizuku UserService/app_process startup with a fixed, authorized package
  service shell transaction (`grant --user <app's user> app.svan android.permission.DUMP`).
  No new process launch, arbitrary commands, caller-supplied targets or new permission
  scope. Explicit Shizuku authorization remains required. ResultReceiver and actual
  app DUMP permission determine success. A UI deadline and one in-flight request
  prevent repeated stuck requests; late grants are recognized. Removed the unused
  AIDL/UserService/R8 keep rules. Dedicated setup diagnostics retain phase/errors
  separately from noisy capture logs. Release detection test requires the direct
  binder grant and still measures processing of an already-playing source.
- Permission/setup warning while music is playing on Sound/EQ/other tabs; the
  main setup is shown before optional recognition. Setup state refreshes if DUMP
  changes during a running scan. Capture startup is blocked (before consent in
  the activity, checked again in the service) when DUMP is missing and there is
  no real connected source. Broadcast-discovered sources still work without DUMP.
- Optional Notification access → MediaSessionManager player recognition, with
  playback/local-output callbacks triggering recovery scans. Names match by full,
  unambiguous UID only; remote, paused, buffering, failed/revoked sources do not
  supply a local-playing signal. No notification text, track metadata, position,
  audio or transport commands are read/sent. Privacy rationale is in-app and in
  PRIVACY.md. This source recognizes players, **does not invent audio-session IDs**,
  prove processing, permit capture, or replace DUMP for non-broadcasting sources.
- Recognition-driven server scans and explicit missing-player health even if a
  second player is detected; preserves existing audio-report redundancy and
  routing/capture flags. No DSP or sound-policy changes.
- AF oversized integers are rejected row by row instead of losing the entire
  report; sessionless duplicate records retain capture opt-outs; partial server
  reports can add positive evidence but cannot establish absence for eviction.
- Synthetic AOSP-shaped streaming fixtures (clearly marked as synthetic), seeded
  malformed/truncated-report regressions, UID/work-profile/shared-UID cases, and
  real TECNO missing-permission/capture-start regressions. Test-source service now
  publishes a real local media session. Emulator T30 covers both dumps unavailable,
  no invented route, live recovery, stopped player, revocation and optionality.
  T16 retains the acoustic no-duplicate check and checks blocked capture startup.
  `detection_release.sh` remains exactly ten checks.

**Verification so far:** local unchanged native suite: 65 tests, zero failures.
Final direct-grant debug/release builds, lint and 81 JVM tests passed locally
(JDK 17 / SDK 35 / NDK 27.0.12077973). Preview certificate SHA-256 matches the
owner's uploaded 0.5.0 APK, so installation should preserve settings. New
CI/emulator checks are pending at writing;
do not distribute this preview until they pass and screenshots are inspected.

**Still unverified:** commercial streaming apps on the TECNO, DUMP grant success
on this phone (owner has been given the existing phone-only Shizuku steps), media
recognition on HiOS, any sound preference, Bluetooth timing, screen-off survival.
Next required phone evidence: report with **DUMP=true**, with Spotify/Amazon/YT
Music playing, plus route and app name. No PC/commands are required from the owner.

Direct grant protocol references (no GPL source used):
[AOSP BinderProxy.shellCommand](https://github.com/aosp-mirror/platform_frameworks_base/blob/android14-release/core/java/android/os/BinderProxy.java),
[AOSP ShellCallback.writeToParcel](https://github.com/aosp-mirror/platform_frameworks_base/blob/android14-release/core/java/android/os/ShellCallback.java),
[ShizukuBinderWrapper (MIT dependency)](https://github.com/RikkaApps/Shizuku-API/blob/master/api/src/main/java/rikka/shizuku/ShizukuBinderWrapper.java).

## Redundant detection + Svaresa auto master — 2026-10-05 (0.5.0 preview)

Owner reported (0.4.1 APK, TECNO LH7n): detection is unreliable for every player, not only Neutron. Apple Music
was seen once, YouTube Music stopped appearing after switching, Neutron never; a screenshot showed the capture
engine running with "0 apps", −120 dBFS, and YouTube Music listed only from a stale capture verdict.

**Investigation (no device dump exists, so causes are ranked, not proven):**
- The APK is exactly the 0.4.1 source (versionCode 6, DUMP declared). The `dumpsys audio` player format matches
  the android14-release sources (`AudioPlaybackConfiguration.toString`), so the parser is not the main weakness.
- The weakness was architectural: detection depended on ONE report. Players the audio service lists with
  `sessionId:0` (native AAudio/OpenSL players, which is likely Neutron's path) were dropped. A failed/slow/odd
  report meant "nothing is playing". "Direct/offload/bit-perfect" outputs (Neutron hi-res, Apple Music lossless
  over USB) bypass session effects entirely and were reported as "no session". The Hi-Fi list showed remembered
  apps as if detected. A killed foreground service was invisible.
- Svaresa had no effect on system effects because it had no source analysis there (all-zero plan).

**Changes (0.5.0):**
- `AudioFlingerDump`: parses `dumpsys media.audio_flinger` (threads + output devices, track tables incl. pid/session/
  usage, effect chains incl. our DynamicsProcessing, orphan chains, `Global session refs` = session→pid/uid/package).
- `SessionLedger`: fuses player list + audio-server tables. Either alone is enough. Session-0 players are resolved
  through their pid. Output path (mixer/direct/offload/bit-perfect/MMAP) is known per session.
- `EffectVerifier`: proves in the audio server that our effect is bound, suspended, bypassed, waiting or missing;
  missing → automatic re-attach.
- `DetectionMonitor` + health card: per-scan verdict (OK/IDLE/DEGRADED/BLIND/NO_PERMISSION) with a plain reason, using
  the public active-playback count as a blind-spot detector. Shareable diagnostic report (permissions, both raw
  report excerpts, ledger, routes, recent log, session-0 probe) — **ask the owner for it after the next test**.
- Router: per-source absence rules (a failed server read never evicts), bypass-path sessions never muted, self-heal.
- Service: detects "stopped by Android" (dirty-shutdown flag) and shows HiOS/OEM background guidance.
- Manifest visibility for more players (Neutron trial etc.).
- Svaresa: context layer on every engine (ISO 226 quiet-listening lift, speaker protection, night comfort with
  multiband level-evening on system effects, AutoEq headphone recognition), plus a stronger native measured policy
  (wider limits, tilt correction, harshness smoothing). Still ignores guided taste. `docs/SMART.md` updated.

**Verified (CI, API 34 emulator, runs 37267639906 / 37268750857 / 37268938774):** core 65 tests (+ASan/UBSan/TSan);
JVM tests for the AF parser, ledger, verifier, health, ISO 226 anchors (20 Hz 99.85 dB, 100 Hz 64.37 dB at 40 phon),
Svaresa brain, headphone matching. e2e: the app process CAN read `media.audio_flinger` on the stock API 34 image and the
real report parses (`detect: players=ok server=ok … capturable#449:started:BOTH:mixer:PROCESSING`); the player is
found and processed with the player list blinded and with the audio-server report blinded; MBC level-evening was
accepted (no `attach failed`); night comfort stayed bounded (−1.7 dB vs resting); detection_release 10/10; compat
API 29/30/33/35 smoke. Screenshots inspected: the "Svaresa adapts to" section fits the 320 dp screen without clipping.

**Fixed after the first e2e logs (commits d4827e7, bd36085, 546d500):**
- e2e "Svaresa lifted the bass above the resting level" failed on every run (63 Hz: on −48.6, off −48.2 dBFS). The
  test was wrong: auto headroom (default on) pre-attenuates by the curve's largest boost, so a boost never raises the
  absolute level (1 kHz fell 6.0 dB instead). The check now measures the 63 Hz-vs-1 kHz balance (must rise > 2 dB and
  match the predicted change ±1 dB); the reason text says bass rises *against the mids*.
- A just-created effect was judged by a report read 250 ms later → MISSING → the router tore it down again; cards
  flashed SUSPENDED. Only effects settled (1.5 s) before the report are judged now; re-attach on MISSING is capped at
  3 spaced attempts (re-armed only by PROCESSING) so a disagreeing report cannot rebuild the effect every scan.
- The health card showed an idle SystemUI SoundPool as "Active but no session"; released/non-media session-0 records
  are ignored (they could also lend "released" to an app's live session) and only playing ones are shown. The share
  button no longer wraps to three lines; rows/headline use the installed app label.
- Hold-to-compare and the EQ switch re-created every system effect while Svaresa ran (levelling went null); fixed.
- AutoHeadphone now removes its correction when switched off or when headphones are swapped.
- **Neutron (unconfirmed):** a third-party equalizer's supported-player notes say Neutron only exposes its audio
  session to Android effects with Settings > Audio Hardware > **DSP Effect (Device)** on. The health advice now says
  "check DSP Effect (Device)" when Neutron is playing unprocessed, sessionless or on a direct path, and
  PHONE_VALIDATION.md asks the owner to test both settings. Not confirmed on the TECNO.

**CI for 546d500 (run 37271042516): all jobs green** — core, android, compat 29/30/33/35, emulator-e2e 38 PASS / 0 FAIL
(incl. the balance check: measured +5.6 dB vs predicted +5.4 dB), detection_release 10/10, zero `verify:` re-attaches and
no DEGRADED/BLIND flashes in the log. Its Hi-Fi screenshot showed the second health-card button clipped to "Share"; the
follow-up f9ec7a5 slims the button padding; run 37273679682 is green again (38/0, detection 10/10) and its screenshot
shows "Scan now" and "Share report" on one line each.

**Not verified:** anything on the TECNO — whether HiOS lets the app read `media.audio_flinger` (stock API 34 does),
whether Neutron/Apple Music use direct paths, the Neutron DSP setting, battery-killer behaviour, sound preference of any
new curve. The tilt target (−2.5 dB/oct, ±1.5) and the volume→phon mapping (30–80 phon) are first guesses; both are
bounded and switchable. A single-scan BLIND flash can still appear for ~0.5 s when a player starts (seen in CI logs).
Note: PR #1 for this branch exists (opened 05:52 UTC, not by the coding agent), so each push runs CI twice (push + pull_request).
Next: Listen-only capture tap so Svaresa can analyse on system effects (Visualizer is only 8-bit: unsuitable).

## Bluetooth/session recovery — 2026-10-05 (0.4.1 preview)

Owner reports YT Music over Bluetooth was detected once, then disappeared. No new TECNO dump is
available, so a single device root cause is not established. Code review found unbounded dump reads,
dropped scan requests while one was running, no dedicated output-device recovery callbacks, immediate
eviction after one empty snapshot, and no recovery for an existing route whose effect disappeared or
failed to attach.

Changes: bounded audio-report reads (3 seconds / 2 MiB); playback/output/wake triggers with a short
retry sequence and the existing 5-second heartbeat; coalesced scans preserving a follow-up request;
serialized route mutations; at least 3 missing successful snapshots over 10 seconds before eviction;
explicit release still removes immediately; missing activity becomes unknown. System effects are
checked for control/enabled state and recovered with capped retry delays. Broadcast-known sessions
also get effect repair without DUMP. Dump parsing handles wrapped records and numeric states while
excluding timestamped playback history; unknown UIDs stay on Engine A. Per-stream capture flags
are respected without permanently classifying the entire app from one track.

Meaningful regressions cover parser history/wrapping, scan coalescing, disappearance grace and retry
backoff. Emulator T24 recreates a non-broadcasting session three times; T25 drops system effects and
requires automatic recovery on the same session. Fault injection is debug-only. This reproduces
session/effect lifecycle failures, not physical Bluetooth transport. CI is pending for this change.
Local unchanged native suite: 63 passed. Local Gradle could not download its distribution (network
connection refused); Android compilation/lint/unit checks run in CI before delivery. Version code is 6.

Prior build `3badd2f` / run `37255092750` is now fully green, including API 34 e2e and API 29/30/33/35
install smoke. Its final Svaresa screenshots were inspected on 5 October: stacked cards fit both names,
and the contrast/line-wrap fixes are present. This supersedes the older pending notes below. Real TECNO
Bluetooth reconnection, phone background survival and each player's actual output path remain unverified.

## Svaresa mode and visual identity — 2026-10-05 (current continuation)

Adds two plain-language sound modes with distinct gold marks: **Sound guide · Svaramanas** preserves
listener-directed tone and priority controls; **Auto master · Svaresa** uses a deterministic, conservative
automatic policy over measured source corrections only. Mode state is saved. Guided choices are preserved
when switching to Svaresa, and the floating bubble changes to the matching mark. The automatic controller
ignores taste and instrument lifts, caps analyser corrections at 85% strength, keeps the existing codec,
mono, limited-master, headroom and loudness guards, and slews adaptive changes. It does not auto-adjust the
bass transient shaper, vocal tuner or stereo width without validated cues. No new model has been trained;
the code does not claim one. Training needs opt-in, level-matched preference data and held-out listening
that demonstrates an advantage over rules.

The core regression checks that Svaresa produces the same plan as a balanced 85%-strength correction
policy despite arbitrary guided preferences, and that a measured muddy source still receives a bounded
low-mid cut. CI, phone installation and visual review are pending.

## Player discovery and bass tuner — 2026-10-05

The owner reports that Apple Music was detected earlier with Fosi Audio IM4, but is now missing over a
wired connection; Neutron has not appeared; and no player session appeared using Realme Buds Air 8
with LHDC either on or off. This is user-reported; no TECNO dump or logs are available. Treat the buds
as output routes and the player as the source session. The cause could be Android's report format,
session visibility, or an app-specific output path; it is not established.

The source adds explicit package visibility for common streaming/offline players, accepts more
playback-dump field formats, and shows local scan counts plus optional raw playback-config lines in
Hi-Fi → Music detection. This separates no reported track, a track with no usable session, and a parsed
session that has not been routed. Bass starter presets are capped at +2.5 dB. In addition to the +1.5 dB
Clean impact profile, Full impact offers +2.5 dB at 68 Hz with restrained upper-bass shaping. Small-step,
level-matched advice is presented as a gold-accent listening-tip callout. These are starting points,
not proven sound-quality improvements.

Verification for this continuation is pending. The local checkout is based on `b0527c3`; its edits are
being consolidated on the existing branch after `cdd0cfb`, which already passed Android build/lint/unit
and API 29/30/33/35 install checks. Review the new consolidated run and screenshots before delivering
its preview. Physical TECNO and LG V60 checks remain outstanding; do not claim Apple/Neutron or
Realme-route compatibility until the owner tests it.

Further 2026-10-05 polish adds explicit package visibility for common streaming and local players
(YouTube Music/YouTube, Spotify, Amazon Music, Apple Music, Tidal, Deezer, Qobuz, SoundCloud, Pandora,
Poweramp, Neutron, ONKYO HF Player, HiBy, FiiO, USB Audio Player Pro, VLC, foobar2000, AIMP, Musicolet,
Pulsar and Plex). The Hi-Fi screen names examples and states that direct/bit-perfect output can bypass
system effects and capture. These package declarations improve UID/name resolution; they do not prove
session visibility or processing for each app.

Sound polish adds the controlled `Full impact` bass preset (2.5 dB low shelf at 68 Hz plus the existing
punch shaping / upper-bass cut), retains the one-tick listening guidance, and styles that guidance as a
gold-accent callout. Navigation now retains saveable tab state so the user's scroll position and other
saveable screen state survive tab changes. CI and screenshots are required before distributing this
build; the preset's listening preference remains unverified on either phone.

> **Newest brief: `docs/CODEX_SVARAMANAS.md`** (Svaramanas sound intelligence: what's built, what's unverified,
> research agenda, next increments). Paste-in prompt: `docs/CODEX_PROMPT.md`.

## Clarity follow-up — 2026-10-04 (0.3.1 verification pending)

Resumed from Claude's green `e087533` / CI `37176877888`, preserving the foreground
media test-source fix. The earlier detection setup now passes all seven release
checks; its full audio log was reviewed before this follow-up.

Version 0.3 fixes reproduced native defects: retained protection attenuation now
recovers with a 250 ms release; bass character uses a complementary first-order
split so punch no longer cancels at its crossover; turning bass off restores exact
identity. Three new core regressions pass (47 total). See AUDIOPHILE.md.

Flat now resets every sound layer, with a visible Reset all sound action in
Presets and JVM regressions. Fresh defaults use system effects and float output;
existing saved preferences are preserved. Android system EQ requests a detailed
80 ms frame (10/40 ms options available), and UI no longer equates requested band
counts with actual resolution. Quality/dither controls are explicitly capture-only.
The release emulator script adds three actual-output checks: layered capture reset,
63 Hz +6 dB boost, and 63 Hz −6 dB cut. CI `37207558523` passed all prior audio
checks and reset, but 40 ms bass boost/cut measured +4.9/−4.6 dB and failed the
unchanged ±1 dB limit. Reviewed all screenshots: boot margins, numeral 9 and
actual स्व system splash are correct. Reference FFT/window simulation reproduced
the +4.9 dB result and predicts +5.57 dB at 80 ms; 0.3.1 keeps faster options and
uses 80 ms for Detailed. New CI pending at writing. Follow the required
checks, inspect CI/screens, then deliver the tested APK; retest TECNO/YT Music over
Bluetooth. The user's current sound preference cannot be established by emulator.

## Continuation — 2026-10-04

Phone report after `55f84f8` / CI `37169778164` (all tests green and screenshots
reviewed): installed successfully, but YouTube Music remained completely
unprocessed. Screenshots show zero detected apps; the running capture service
was processing silence at 29.4% measured DSP load. Wi-Fi is available. Other
players are available on a different phone but have not yet been tested.

Current follow-up addresses the delivery-to-audio gap, with CI pending at writing:
- Runtime session receiver in SystemEqService accepts general player broadcasts.
  The old tests explicitly addressed Svan, hiding Android's manifest-only receiver
  limitation. New checks omit the package and revoke DUMP.
- Phone-only Music detection setup uses Shizuku 13.1.5 API (MIT) and a fixed,
  short-lived UserService to grant only Svan's DUMP permission. Normal operation
  remains in Svan's own process; the helper can be stopped afterwards.
- Discovery refreshes after a live grant and polls every five seconds as backup.
  Duplicate playback records preserve a playing sibling and all capture opt-outs.
- No admitted source skips DSP/dither and produces zero output; input/output
  signal peaks make silence visible. EQ status no longer calls zero apps active.
- `detection_release.sh` exercises the R8 grant service against pinned official
  Shizuku 13.6.0 under shell identity, already-playing discovery, helper shutdown,
  single-copy 4× capture, graphic +6 dB, bypass, and parametric −12 dB.
- Version 0.2 requires another phone check after setup. Android/OEM behavior,
  Bluetooth delay and perceived sound quality are still not established by CI.

The original priorities and previous implementation history follow.

User now reports heavy delay / echo-like doubled playback with **YouTube Music
on Bluetooth** on the TECNO LH7n. Other players/outputs are untested. They want
Svan’s own identity, measurable feature effectiveness and fewer unsupported claims.
Treat this as unresolved phone evidence, even when synthetic emulator tests pass.

Commit `0ff00da` passed CI run `37168656837`: core/sanitizers, Android checks,
release smoke and 20/20 audio checks. Screenshots confirm readable 9, boot margins
and the actual स्व system splash. Review also exposed vertical swipes editing
Sound knobs instead of scrolling. Follow-up uses horizontal dial drags, adds
accessible range actions and a CI state-preservation check for scrolling; its
CI verification is pending at this writing.

Changes in this continuation:
- Boot name margins confirmed in `a48a8bd` screenshots. Its flipped d still looked
  like q; now using a serif numeral 9 with accessible “EQ Extended”. The system
  splash gets a new screenshot attempt; confirm the actual icon image.
- Engine B now captures only known UIDs whose routed sessions are all muted.
  Unknown audio is excluded. Mute loss/conflicting UID routes stop capture.
  Smaller explicit input/output buffers; Hi-Fi shows output queue, DSP load and
  underruns, explicitly excluding total Bluetooth/capture latency.
- Per-app Auto/System effects choices in Hi-Fi. Changing one stops capture;
  a new explicit grant applies it. Global System effects only also stops Engine B.
- Engine A’s foreground lifetime/discovery moves into SystemEqService, with
  notification Stop and a Hi-Fi start/stop control. Failed/dead effects no longer
  terminate the update collector; unavailable processing is shown in the app row.
- Measured duplicate headroom attenuation fixed: −6 dB preamp plus +6 dB bell no
  longer becomes −12 dB preamp. Headroom/protection toggles now apply to Engine A.
  Engine B protection resets on EQ edits, so bypass does not retain old overload loss.
- DSP capacity reserved at 256 stages for 128 manual bands plus up to 96 tuning
  bands and tuners. Previously later non-neutral layers could be silently dropped.
  Native processing remains allocation-free in steady playback.
- Removed comparison branding; qualified mid/side and synthetic-test claims.
  No voice/instrument recognition or total phone-latency guarantee is claimed.
- New core regressions bring the suite to 44 tests. E2E adds boost/bypass, per-app
  overrides, global mode, background EQ and unknown-audio exclusion (20 checks).
- CI uploads `Svan-preview` (release APK) as well as debug and emulator artifacts.
  `-PsvanProduction=true` requires owner-provided signing inputs; no production
  key created, no store publication. See RELEASE_READINESS.md and PRIVACY.md.

Remaining work, in original priority order:
1. Inspect every new CI log and screenshots after each push; confirm numeral 9,
   boot margins, actual system-splash icon and new Hi-Fi rows on the small emulator.
2. Deliver latest preview and get phone retest using PHONE_VALIDATION.md. Detection
   without DUMP, Bluetooth delay/echo, arm64 behavior and 30-minute screen-off/battery
   stability remain unresolved. Undetected apps are excluded from Engine B, not
   magically processed; the UI must say so.
3. Per-app controls implemented; refine based on phone evidence, especially
   multiple sessions sharing one UID (currently fails safely back to Engine A).
4. Foreground lifetime implemented; actual OEM kill/restart behavior needs phone tests.
5. Signing inputs, owner-approved privacy policy URL/contact, Play declarations,
   dependency upgrades and OEM backup review remain release gates. No blanket
   dependency update in the audio correctness change.
6. Licence question sent to user; no answer yet. Keep original-code licence undecided.
7. Ear-tune only after stable routing and level-matched music evidence. Do not
   increase all dial gains or invent thresholds to imply perceptual improvement.
8. No live Squiglink scraping: no applicable data licence established; user file
   import remains available.
9. Crossfeed/loudness/delay/convolution remain future work, after the reported
   playback/efficacy problems are verified on the real phone.

Baseline CI `37166890912` failed 1/12 checks: the source was stopped while a final
“tuners off” dynamics rebuild attempted attachment to its closed session. Added
settling time before source close and lifecycle serialization/resource cleanup.
The actual level and routing checks plus release smoke passed in that run.

## 1. The user and the goal
- Solo builder, **one Android phone (TECNO LH7n, Android 14), no PC.** Fan of Neutron Music Player
  (60–80-band EQ, "audiophile" 64-bit processing/resampling) and Wavelet/Poweramp EQ.
- Wants a **global (system-wide)** EQ — chosen over a Neutron-style standalone player on purpose.
- Likes the app so far ("I like the app"). Cares a lot about: audiophile depth, a polished **gold** look,
  brand ("Svan"), and being usable by non-geeks (hence the Sound tab with headphone tunings + three tuners).
- Quirks: numerology (3-6-9, Tesla) — it is deliberately woven into timings, icon, titles. Wants honest
  reporting; wants screenshots/APKs sent. Doesn't want to run commands unless unavoidable.

## 2. Current state (all verified in CI on a KVM emulator unless noted)
Latest verified-green commit: `11f26ef` (CI runs #23–#25 green: core + sanitizers, Android build/lint/tests,
release smoke test, **12/12 e2e PASS**). Local, not yet pushed at handoff time: boot-name sizing and the
flipped-d lift (see §6 item 1) — pushed with this file.

### Features built
- **DSP core (`core/`)**: 128-band parametric EQ (RBJ biquads, TDF-II, double), lock-free param updates;
  2/4/8× oversampled EQ (linear-phase Kaiser FIRs); rational polyphase resampler (Quality/Audiophile);
  TPDF + noise-shaped dither; auto headroom + Automatic Gain Protection; AutoEq parsers;
  `BassShaper` (punch↔sustain transient shaper); `StereoTuner` (mid/side vocal tuner + orchestral amp);
  headphone tuning maths (`tuning.cpp`: target−measurement, taste, dense-band fit).
- **Engine A / B + routing** as described in `AGENTS.md`. `CaptureCompat` probes per app whether capture
  carries audio (apps that opt out, e.g. Spotify, are un-muted and left on Engine A, never silenced).
- **UI (Compose)**: tabs **Sound | EQ | Presets | Hi-Fi | Lab**.
  - *Sound*: search ~8,850 AutoEq headphones (oratory1990, crinacle, Super Review, Rtings, Innerfidelity…),
    pick a signature (Harman, Harman lighter bass, Neutral, oratory1990, AutoEq in-ear, reviewer's published
    profile, imported target), taste sliders, 32/64/96 bands; **Bass tuner / Vocal tuner / Orchestral amplifier**
    with rotary knobs + presets.
  - *EQ ("EQ exten9ed")*: interactive response graph (drag nodes, tap to add, long-press to delete),
    band editor, graphic mode (10/15/31/64 faders).
  - *Presets*, *Hi-Fi* (engine start/stop, quality modes, dither, bits, gain staging), *Lab* (device probes, log).
  - Boot animation Svan → Svanam Shreshtham → ULTIMATE SOUND (0.3/0.6/0.9 s beats, 369 ms fade); brand line on every tab.
  - Icon: स्व (sva, from स्वनम्) in a brass yantra ring; adaptive + monochrome; Android 12+ splash uses it.
- **Release build**: R8-minified ~4.9 MB (arm64 + x86_64), signed with the **debug key** (preview only).

### Measured facts (all in tests; see docs/AUDIOPHILE.md)
- 16 kHz +9 dB bell vs ideal analog: error 5.56 dB @1× → 1.06 @2× → **0.24 @4×** → 0.06 @8×.
- Resampler 20.5 kHz passband loss: −5.9 dB (Quality) vs 0.0000 dB (Audiophile); alias rejection 104 vs 155 dB.
- HD 650 → Harman correction matches AutoEq's published one to 0.04 dB rms; 64-band fit 0.06 dB (10 bands: 0.84).
- Bass shaper: punch +8.3 dB attack/tail, sustain −5.2 dB; off = bit-exact. Vocal "Smooth": shrill voice −8.7 dB, mellow −0.0 dB.
  Orchestral amp: centred source bit-identical with dials at max; sides ±6 dB; side bass within 0.1 dB.
- Host CPU (cloud x86, one core, 80 bands stereo): 1× 3%, 2× 7%, **4× 14%**, 8× 29%. **Phone numbers unknown** — run `eqcore_bench` on-device.
- TECNO LH7n: `DynamicsProcessing` accepted 10–1024 bands, gains read back exactly, but each band costs ~1–2 ms
  (128 bands ≈ 100–250 ms; bulk set is no faster). Whether >31 bands are *audibly* resolved is **unknown**
  (the Visualizer reads pre-effect audio, so that probe was inconclusive).

## 3. Test/CI infrastructure (important — it is the only "device")
- `emulator-e2e` job: boots an Android 14 x86_64 KVM emulator (reactivecircus/android-emulator-runner), then runs
  `smoke_release.sh` (installs the R8 release build, opens all tabs, drives native paths), `diag.sh`, `e2e.sh`, `screens.sh`.
- `e2e.sh` plays a 1 kHz tone from the fake apps in `android/testsource/` and measures the final output mix with a
  Visualizer on session 0 (`MixMeter`). PASS/FAIL lines are computed from level differences (single processed copy
  vs double audio vs silence). The job fails on any `FAIL`.
- Scriptable app commands (adb, used by tests): `am start -n app.svan/.MainActivity --es cmd <probe|resolution|sessions|preset|
  start_capture|stop_capture|measure_mix|forget_verdicts|diag_capture|dump_lines|bass|tuners|tune> ...`; logs go to logcat tag `EqSpike`.
- Local container had no KVM; a software emulator took >20 min and looped on system_server watchdog. **Use CI, not a local emulator.**
- GitHub MCP tools were how artifacts were downloaded; if unavailable, use the Actions UI.

## 4. Hard-won gotchas (don't re-learn these)
1. `-Pandroid.injected.build.abi=…` (Android Studio flag) marks the APK **`android:testOnly="true"`** → installer says
   "package appears to be invalid". Build previews with plain `:app:assembleRelease`.
2. Without `<queries>` (Android 11+ package visibility) `getApplicationInfo()` fails for other apps → uid −1 → the
   per-UID capture check hears silence and wrongly caches BLOCKED. Fixed with `<queries>` + uid from the audio-service dump.
3. DynamicsProcessing instances on one session share one engine: Engine A's EQ and the source-mute must never both be attached.
4. `adb shell` flattens arguments: quote multi-word values for the *device* shell (`"'Sennheiser HD 650'"`).
5. `adb install -r` keeps app data → stale saved EQ skews later tests. Scripts uninstall first.
6. AutoEq correction math: apply AGP *after* the EQ (applying it before overshoots/pumps).
7. `pkill -f <pattern>` can match your own shell command line — match on binary path.
8. Visualizer on a player session sees **pre-effect** audio (also why capture-then-mute works). Use session 0 for the final mix.
9. Compose: reuse `android.graphics.Paint` objects in drawing code; allocating per frame stutters.
10. Mid/side crossovers must be Linkwitz-Riley low+high (not `x − low`), or the band gain leaks into the bass.

## 5. Known limitations (be honest about these in UI/docs)
- Orchestral amplifier needs mid/side → **Engine B only**; a centred solo instrument (sax, etc.) is treated like a vocal.
- Vocal tuner and bass "feel" on Engine A are coarse approximations (static EQ + multiband compressor).
- Spotify/Chrome etc. block capture → they only get Engine A.
- Engine B adds latency and needs a MediaProjection grant each session (an `appops PROJECT_MEDIA allow` via ADB avoids the prompt);
  discovering apps that don't broadcast their session needs `adb shell pm grant app.svan android.permission.DUMP`.
- Capture-based Engine B can't do bit-perfect/USB-DAC passthrough.
- All tuning thresholds were derived from **synthetic** signals; real music needs ear-tuning.

## 6. Open work, in priority order
1. **Verify the two visual fixes** on the next CI screenshots (`1-eq.png`, `boot-2.png`):
   - Boot: "Svanam Shreshtham" must be ONE centred line with side margins (font = width/12.8).
   - "EQ exten9ed": the flipped **d** (`Exten9edTitle` in `Brand.kt`, `translationY = −0.07em`) must read as a 9
     (bowl up, stem ending on the baseline). Previous values: −0.12em floated like a superscript; +0.03em hung below
     the baseline and read as a "q". Nudge in 0.02em steps. Note a flipped lowercase d is inherently q-like; if it
     still reads as "q", consider a real numeral 9 in the same serif with slightly smaller size, or a drawn glyph.
   - स्व icon on the Android 12+ system splash (`values-v31/themes.xml` uses `ic_launcher_foreground`).
2. **Get real-phone evidence** (the user will test; no PC): install `Svan-preview.apk` (release build), then ask for
   screenshots. Specific unknowns: does Engine B run stably for 30+ min (foreground service, screen off), battery cost per
   quality mode, audible latency, does R8 build behave (CI smoke test says yes on x86_64 only; arm64 untested).
3. **Per-app UI**: let the user see/override which engine each app uses (`SessionRouter.snapshot`, `CaptureCompat.all()`).
4. **Foreground-service robustness**: Engine A currently lives in the process (receiver-driven). Host it in a service so it survives backgrounding.
5. **Release readiness**: real signing key (currently debug), `targetSdk 35` foreground-service/MediaProjection policy text,
   privacy policy (INTERNET is used for AutoEq downloads only), `allowBackup` lint note, dependency bumps (lint lists newer Compose/AndroidX).
6. **App licence**: not chosen. Ask the user. Keep GPL code out (JamesDSP/RootlessJamesDSP are GPL; the repo's DSP is original).
7. **Sound-quality iteration by ear**: vocal smoothness threshold, bass shaper time constants, orchestral amp curves.
8. **Squiglink**: no data licence found for scraping; the app imports user-supplied curve files instead. If the user wants live
   Squiglink browsing, check each site's terms and the `phone_book.json` format first.
9. Nice-to-haves: Neutron-style crossfeed, loudness compensation, time-delay, convolution/FIR mode (core is ready for a FIR stage),
   AutoEq `GraphicEQ` for Engine A directly (currently re-fit to dense bells).

## 7. Reusable how-tos
- **Send the user an APK** (they install it directly): build `:app:assembleRelease` locally (no extra flags), copy
  `app/build/outputs/apk/release/app-release.apk` to `android/build-kit/Svan-preview.apk`. Large debug APKs (57 MB) failed to upload once; release is ~4.9 MB.
- **Look at CI screenshots**: download artifact `e2e-results`, view `screens/*.png`, `boot-*.png`.
- **Regenerate the icon**: `pip install fonttools uharfbuzz`, then follow the header comment in `tools/brand/make_icon.py` (Poppins SemiBold, OFL).
- **Add a native API**: C++ in `core/`, JNI in `android/app/src/main/cpp/jni_bridge.cpp`, Kotlin external in `NativeEngine.kt`
  (names must be `Java_app_svan_NativeEngine_native…`; R8 keeps them via `proguard-rules.pro`).
- **Add an e2e check**: add a scripted command in `MainActivity.handleCommand`, a step + `check` line in `scripts/e2e.sh`.

## 8. Attribution & licences in use
- AutoEq (MIT, Jaakko Pasanen): data/targets fetched at runtime; test fixtures in `core/tests/data/` with README.
- Poppins SemiBold (SIL OFL 1.1, Indian Type Foundry): launcher-icon glyph outlines. Mukta/Eczar were evaluated, not shipped.
- JamesDSP/RootlessJamesDSP (GPL-3): **read for technique only; no code copied.**

Play preparation follow-up: native ELF load alignment was still 4 KB despite 16 KB ZIP packaging.
NDK 27 flexible page-size support now produces 16 KB load segments. The release artifact gate
checks every shared library in both APK and AAB; older 4 KB Android systems remain supported.
