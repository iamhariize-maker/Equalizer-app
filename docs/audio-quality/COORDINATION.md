# Claude + Codex coordination (audio-quality work)

Goal: a better Svan. Two agents, one repo, no overwriting each other. This file is the contract; the owner
(iamhariize) is the tie-breaker and the relay between sessions.

## Who owns what

| Area | Owner | Rule for the other |
|---|---|---|
| `core/**` (C++ DSP, tests, bench, tools), `core/CMakeLists.txt` | **Claude** | Codex does not edit. If a core change is needed, file a request (below). |
| `android/app/src/main/cpp/**` (JNI C++, Android CMake) | **Claude writes the API; Codex may make minimal compile fixes** | Codex may fix NDK compile errors only, and must list each fix in `REQUESTS_FROM_CODEX.md` so Claude can adopt it. |
| `android/**` Kotlin, resources, Gradle, `android/scripts/**`, screenshots, emulator/CI Android jobs | **Codex** | Claude does not edit. Request instead. |
| `docs/audio-quality/STATUS.md`, `POLICY_RULES.md` (generated), `COORDINATION.md` | **Claude** | Codex reads. |
| `docs/audio-quality/CODEX_STATUS.md` (create it), `docs/HANDOFF.md`, release/signing docs, version bumps | **Codex** | Claude reads. |
| `.github/workflows/ci.yml` core steps | Claude | Android steps are Codex's. One-line changes by either must be announced in the request files. |
| `AGENTS.md`, `LICENSE`, privacy/permissions docs | Owner decisions only | Neither agent changes these without an owner decision recorded in the file. |

## Branches and merging

* Claude: `ccr-2e937472-6z53b0` (core + JNI API).
* Codex: use the branch your session instructs. If you may choose, branch from the current head of Claude's branch as
  `codex/aq-kotlin-integration`.
* Neither agent force-pushes or rewrites the other's branch. Codex merges Claude's branch into its own regularly
  (`git merge origin/ccr-2e937472-6z53b0`); Claude never merges Codex's work, the owner decides when branches meet.
* No new PR and no release unless the owner asks. The published v0.5.6-beta.1 and PR #6 stay untouched.
* Because the file ownership above is disjoint, a merge should never conflict. If it does, stop and ask the owner.

## Requests (append-only, one file each, so they never conflict)

* `docs/audio-quality/REQUESTS_FROM_CODEX.md`: Codex writes needs of the core/JNI (new API, bug found in a core test,
  NDK compile fix applied). Format: date, what, why, the failing command or screenshot.
* `docs/audio-quality/REQUESTS_FROM_CLAUDE.md`: Claude writes needs of the app (a UI state to expose, a setting, a measurement).
* Each agent reads the other's request file after every merge and answers in its own status file.

## Interface contract (what exists today; changing it needs a request)

Native API, all on `NativeEngine` handles. Existing: `nativeSetBassResolve(h, double 0..1)`, `nativeSetBassUnmask(h, double 0..1)`
(default 0 = off, bit-exact), `nativeSetStereoTuner(h, ...7 doubles)`, `nativeLatency(h)` (includes the Detailed spatial delay).
Added by Claude with this contract (JNI C++ only; Kotlin `external` declarations are Codex's):
* `nativeCreateDetailed(sampleRate, channels, oversample, stopbandDb, ditherBits, ditherMode, autoHeadroom, gainProtection, spatialResidual: Boolean): Long`
  same as `nativeCreateCustom` plus the Detailed (streaming spatial-residual) mode. Latency grows by exactly N frames
  (1024 at 44.1/48 kHz, 2048 at 96 kHz); it is reported by `nativeLatency`.
* `nativeBassUnmaskDiagnostics(h): DoubleArray` = `[cut70, cut110, cut180, cut280 (dB, <= 0), noteHz (0 = unknown)]`.
* `nativePolicyRulesJson(): String`: the rule registry as JSON (`id, version, owner, inputs, minConfidence, maxAgeSeconds, sameEpoch,
  nativeOnly, parameter, units, min, max, competes, reason, rollback, counterexample`) for a "How Svaresa decides" screen.

Added later (see `REQUESTS_FROM_CLAUDE.md`): `nativeSvaramanasPlanGated(...)` (plan + evidence-gate outcomes), `nativePolicySkipText(code)`;
`BassUnmask` diagnostics are thread-safe; `SourceAnalyzer` decimates inputs above ~52 kHz.

Core facts the app must not contradict: Backing vocals/Binaural do nothing on hard-panned or already-wide material once a centre exists
(Detailed also holds a signal with no centre; Fast keeps the static response when the mid is silent, see the pinned test);
Bass Resolve's Auto level is 0.6 and never lowers or overwrites the saved manual value; bass unmasking is experimental and off;
the original source rate and the DAC rate are always "unknown"; 48 kHz is the safe default and fallback.

## Truthfulness (applies to both)

Never claim sound quality, compatibility or device behaviour that was not measured. Synthetic core tests are not listening
evidence. State what was and was not run in the status file. Keep the privacy/permission rules in `AGENTS.md` (no notification
listener, SMS or accessibility for player discovery; Play Protect stays on).
