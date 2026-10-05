# Paste this into a fresh Codex cloud session

You are continuing **Svan** (Svanam Shreshtham: Ultimate Sound — Android global, root-free audiophile equalizer;
repo `iamhariize-maker/Equalizer-app`). Work ONLY on the branch your session instructs (currently
`ccr-f859b567-dgrdoj`; PR #1 is open; never push elsewhere, never open another PR). Read, in order: `AGENTS.md`,
**`docs/CODEX_VISION.md` (your brief: the owner's vision, the honest state, and the directed improvement spaces)**,
`docs/HANDOFF.md` (0.5.0 section = newest truth), `docs/SMART.md`, `docs/PHONE_VALIDATION.md`.

The owner's three pillars, in priority order: (1) **supremely robust music-session detection** — "if this isn't
seamless the rest won't matter"; (2) **absolute sonic brilliance** — measured, loudness-matched, never louder-is-better;
(3) a **highly capable Svaramanas sonic brain with Svaresa as its supreme automatic mode** — a real, honest
day-and-night difference. The owner has one phone (TECNO LH7n, Android 14, HiOS), no PC, and judges by ear.

**Streaming and popular apps first:** Spotify, Amazon Music, YouTube Music, then Apple Music and other
widely used streaming players. Neutron, Poweramp and advanced local/direct-output players come later;
they already offer extensive native sound controls. Treat synthetic package-labelled scenarios as
regression coverage, never proof that a commercial app works on the owner's phone.

Do, in order:
1. Check CI on the latest commit (should be green); read the verified/NOT-verified lists in `docs/HANDOFF.md`.
2. Follow `docs/CODEX_VISION.md` §5 "Suggested first sequence": Space A (detection: real-device fixtures, media-session
   third source, direct/offload coaching, OEM survival, time-to-processed, hostile-world tests) before Space B
   (Svaresa ears via the listen-only tap, calibrated quiet listening, headphone intelligence, blind A/B tool, dynamic
   EQ) and Space C (signal-path brilliance). Small, tested increments; core C++ tests first, then app; keep the audio
   thread allocation-free; extend `android/scripts/e2e.sh` and look at the CI screenshots.
3. Never loosen or skip a check to get green; when CI contradicts a test, find out whether the test or the DSP is
   wrong. Never claim sound quality, device behaviour or Neutron/Apple/YT Music compatibility you have not measured;
   keep `docs/HANDOFF.md` truthful about verified vs unverified.
4. Ask the owner (batched) for the **Share diagnostic report** from Hi-Fi → Music detection with Spotify,
   Amazon Music or YouTube Music active; record the output route. Apple Music follows, then advanced
   local-player tests such as Neutron's *DSP Effect (Device)* off/on. Turn evidence into fixtures.
5. Deliver the CI `Svan-preview` APK (fixed preview key) with screenshots and a plain-language status.
