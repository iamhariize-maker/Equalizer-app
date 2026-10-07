# Detection strategy for locked-down ROMs (OxygenOS / ColorOS / HyperOS / HiOS)

Status: **design proposal, nothing here is implemented or device-verified.** Written 2026-10-07 after an
owner screenshot of Shizuku saying "The permission of adb is limited" on a OnePlus (OxygenOS) phone, then
Shizuku reporting that the manufacturer had turned off wireless adb. Read `AGENTS.md` and
`docs/CODEX_VISION.md` first; this document only adds to them.

## 0. Constraints this design obeys (already decided, not re-litigated)

- **No notification listener, SMS or accessibility permission** for player discovery (owner decision,
  5 Oct 2026; Play Protect fraud-warning trigger). Earlier chat advice to use `NotificationListenerService`
  was wrong for this repo and is withdrawn.
- **No Shizuku UserService.** It timed out on the owner's HiOS phone and was replaced by the fixed direct
  binder grant in `ShizukuDetectionGrant.kt`. Do not bring `app_process`/AIDL back as the main path.
- No root requirement. Shizuku/ADB is an **optional one-time helper**; normal playback must not need it.
- Honest reporting: say what is verified and what is not.

## 1. What is actually blocking, and what is not

1. `DUMP` is a one-time grant. Svan's `PlaybackSessions` reads the `audio` service dump; after
   `pm grant app.svan android.permission.DUMP` succeeds, Shizuku and debugging can be turned off
   (`docs/SETUP.md` steps 5-6). So the problem on a locked-down ROM is **one setup step**, not a permanent
   dependency. Every design choice below follows from that.
2. On the OnePlus the failure is the OEM shell restriction, which Shizuku calls "the permission of adb is
   limited". Shizuku's own guide names the per-OEM switch: Xiaomi "USB debugging (Security settings)",
   OPPO/OnePlus ColorOS "Permission monitoring" (disable), Meizu "Flyme payment protection"
   ([Shizuku setup](https://shizuku.rikka.app/guide/setup/)).
3. For newer ColorOS/OxygenOS the toggle is renamed and can be hidden: a third-party compatibility page
   reports that from ColorOS 16 it only appears under **English** system language, labelled
   **"Disable system optimization"** (same switch as the older "permission monitoring", with opposite
   wording, so the label decides which position is correct)
   ([Octoclip device compatibility](https://docs.octoclip.app/features/source/background-monitoring/android/device-compatibility/)).
   The "adb authorization timeout" toggle the owner tried is a different setting and does not address this.
   *Unverified on the owner's exact build: single secondary source.*
4. Wireless-debugging start needs Android 11+, a Wi-Fi/local network (a hotspot from another device works),
   and must be repeated after each reboot ([Shizuku setup](https://shizuku.rikka.app/guide/setup/)).
   The message that "the manufacturer turned wireless adb off" has no public documentation I could find;
   treat it as a device fact to capture in the diagnostic report, not as something to guess around.

## 2. Detection ladder (what Svan already has, and the one real gap)

Every tier must keep working when the tiers above it are unavailable, and the app must say which tier it is
on. Files are under `android/app/src/main/java/app/svan/`.

| Tier | Source | Gives | Needs | Status in repo |
|---|---|---|---|---|
| T0 | `ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION` broadcasts (`SessionReceiver.kt`) | exact session id + package, from cooperating players only | nothing | exists |
| T1 | `AudioManager.AudioPlaybackCallback` (`SystemEqService.kt`, `CaptureService.kt`) | "something plays" count/usage; identity is anonymized without privileged access, as the TECNO report showed (public active = 2, zero sessions) | nothing | exists; used as blind-spot detector in `DetectionMonitor` |
| T2 | `dumpsys audio` + `media.audio_flinger` through `ServiceManager`, fused by `SessionLedger.kt` / `AudioFlingerDump.kt` | session ids and owning uid/package for **every** player, incl. native/AAudio | `DUMP` (one-time grant) | exists, 10 release checks |
| T3 | Shizuku/ADB/root | only used to *obtain* T2's `DUMP` | one-time | `DetectionSetup.kt`, `ShizukuDetectionGrant.kt` |

**Finding:** I did not find any public, permission-free source that yields session ids for players that do
not broadcast. T1 is anonymized, and the notification route is excluded by the owner. The repo evidence and
AOSP's privileged-only identity fields agree, but I could not re-read the AOSP source in this session (mirror
returned 502), so label this "repo-evidenced, not source-verified". A session-0 (global mix) effect fallback
is not evaluated anywhere in the docs and is deprecated on modern Android; do not rely on it without a
measured device test.

So the design is not a new detection source. It is **making the one-time DUMP grant reachable on as many
phones as possible, and degrading honestly where it is not.**

## 3. Grant-acquisition ladder (new work)

Offer the cheapest path that fits the device; never present a path the ROM is known to block as the default.

| # | Path | Needs | Persistence | Notes |
|---|---|---|---|---|
| G1 | Shizuku + wireless debugging, **after the OEM switch** | Android 11+, Wi-Fi, Shizuku installed | grant persists; Shizuku needs restart after reboot | current flow; add OEM-switch guidance (§4) |
| G2 | Shizuku started **over USB** from any computer: `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh` | a computer, once | as above | the documented non-wireless start |
| G3 | **Direct** `adb shell pm grant app.svan android.permission.DUMP` from a computer | a computer, one minute, no Shizuku at all | persists across reboots (same-key updates; revoked on uninstall/OEM policy) | simplest for users who can borrow a PC; already the documented fallback in `HANDOFF.md` |
| G4 | Browser-based ADB (WebUSB) page that runs G3 | Chromium browser on any PC/Chromebook, USB cable | as G3 | removes the "install adb" burden; needs a hosted page and its own security review. Candidate, not designed |
| G5 | Root / Sui | rooted phone | boot-persistent | out of product scope except as a free bonus |
| G6 | In-app ADB client over wireless debugging (no Shizuku install) | Android 11+, Wi-Fi | as G3 | **blocked by the same OEM restriction**; adds a licence check (do not copy GPL) and Play-policy risk. Not recommended until G1-G3 are proven |

Not on the list on purpose: any listener/accessibility/SMS path; a UserService launch.

Why G3/G4 matter: the owner has no PC, but most users who hit this wall can borrow one for a minute, and G3
avoids Shizuku, pairing, Wi-Fi and the Play Protect install step in one go.

## 4. Product changes, in priority order

Each item needs its own test before it ships; none is done.

1. **Classify the failure instead of "Pending".** In `DetectionSetup.kt`, when the grant returns a rejection
   code, record `Build.MANUFACTURER`/`BRAND`, Android API, whether the Shizuku binder was alive, the
   rejection text, and whether wireless debugging was reachable. Surface it in `DiagnosticReport.kt`.
   This also gives the owner the evidence this document lacks.
2. **OEM-specific recovery card** (extends the `OnboardingState.kt` maker table at ~line 99, which today
   covers only battery guidance): for OnePlus/OPPO/realme show the "Disable permission monitoring / Disable
   system optimization (set language to English)" step; for Xiaomi/Redmi/POCO the "USB debugging (Security
   settings)" step. Keep the wording hedged ("labels vary by version").
3. **Offer G2/G3 as an alternative** when the failure is classified as an OEM shell restriction or wireless
   debugging is unavailable: a short card with the exact one-line commands to copy. Never run or claim
   to run commands for the user.
4. **Honest degraded mode.** Without DUMP, broadcasting players still get System effects (T0). Non-broadcasting
   players must show "needs music detection", not a silent no-op. `CaptureService` already refuses to start
   Engine B without DUMP and without a real connected source, which is correct: without a session id Svan
   cannot mute the source and would double the audio.
5. **Detection-health verdict reuse.** Map the new failure classes onto the existing
   `DetectionStatus` verdicts (OK/IDLE/DEGRADED/BLIND/NO_PERMISSION) rather than adding a parallel state.
6. **Tests.** Unit tests for the classifier (`DetectionFixturesTest` style, with a recorded OnePlus-shaped
   rejection once a real one exists); emulator/e2e checks stay at 39 routing + 10 detection. Do not claim
   OnePlus/OxygenOS support from synthetic tests (`AGENTS.md`).

## 5. What would make this stronger (research still owed)

- A real **OnePlus/OxygenOS diagnostic report** showing Shizuku state, the rejection code, and whether G3
  (USB `pm grant`) succeeds on the same phone. If G3 also fails, the restriction is in `pm grant` itself and
  the "disable system optimization" switch becomes the only route.
- Whether the direct-binder grant in `ShizukuDetectionGrant.kt` behaves differently from `adb shell pm grant`
  under the OEM restriction. The doc evidence concerns adb authorization, not this binder path.
- Whether OxygenOS keeps the `DUMP` grant across updates and under its background-app policies
  (`docs/PHONE_VALIDATION.md` already lists retention as unvalidated).
- G4 feasibility and security review, and a licence check if G6 is ever considered.

## Sources

- Shizuku user manual: https://shizuku.rikka.app/guide/setup/
- Octoclip OEM/ADB compatibility notes (secondary source): https://docs.octoclip.app/features/source/background-monitoring/android/device-compatibility/
- Repo evidence: `docs/HANDOFF.md` (TECNO report, UserService timeout, notification-access removal), `docs/SETUP.md`,
  `AGENTS.md`.
