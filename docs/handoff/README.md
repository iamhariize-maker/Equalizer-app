# Handoff folder (11 October 2026)

Preparation material for the model that builds the next changes. Nothing here changes product behaviour.

| File | What it is |
|---|---|
| `../OPUS_BUILD_BRIEF_2026-10-11.md` | **The current work order. Start here.** |
| `HANDOFF_BRIEF_2026-10-11.md` | Background: the owner's reports read stage by stage, the original P-1 detail, owner experiments E1 to E6. Where it disagrees with the build brief, the build brief wins. M-1 (Apple Music) is moot: the owner reports it works per app again. |
| `privacy_ignore_candidates.tsv` | Candidate package names for ticket WP-1 (the ignore list), with confidence. Confirm the "verify" rows on a phone before hard-coding them. |
| `probes/svaresa_trim.cpp` | Measures the Svaresa loudness trim before and after the analysis becomes valid (WP-3). Reference harness. |
| `probes/lab_cost.cpp` | Host CPU cost of each Lab and Svaresa switch. Reference harness. |

The probes are not wired into CMake. Compile them against `core/include` and `core/src`:
`g++ -std=c++17 -O2 -I core/include core/src/*.cpp docs/handoff/probes/<probe>.cpp`.
