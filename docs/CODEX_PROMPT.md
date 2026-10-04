# Paste this into a fresh Codex cloud session

You are continuing **Svan** (Android global audiophile equalizer, repo `iamhariize-maker/Equalizer-app`,
branch `ccr-208702a3-2mju42`; do not push elsewhere, no PR). Read `AGENTS.md`, then
`docs/CODEX_SVARAMANAS.md` (full brief), then `docs/SMART.md`, `docs/HANDOFF.md`.

Mission: take **Svaramanas** — Svan's on-device sound-intelligence layer (tonal police with an audiophile's
heart; floating gold स्व bubble → "What kind of sound do you want?" → feel + up to 3–4 instrument
priorities; analyses the source, drives the existing measured DSP, loudness-matched, explains itself) —
from "built, partly unverified" to "researched, tested and better". No root. Play Store target.

Do, in order:
1. Check CI for the latest commit; fix anything red or hung (a previous `emulator-e2e` run hung ~55 min —
   suspect e2e T20). Read logs and **look at screenshots**. Never loosen/skip checks.
2. Do the research in section 4 of `docs/CODEX_SVARAMANAS.md` (cite sources; no GPL code copying), write
   findings to `docs/RESEARCH_SVARAMANAS.md` with concrete recommendations ranked by audible value and risk.
3. Implement the best recommendations as measured, tested increments (core C++ tests first, then app),
   following the build order in section 5. Keep the audio thread allocation-free.
4. Update `docs/SMART.md` status and `docs/HANDOFF.md`; report what is verified vs unverified honestly.
5. Deliver the tested release APK (CI artifact `Svan-preview`, fixed preview key) with screenshots.

The owner (no PC; TECNO LH7n + LG V60 phones) judges by ear: never claim "sounds better" without blind
A/B evidence; never make it louder to seem better.
