# Handoff folder (11 October 2026)

Preparation for frontier models that will write the code. Nothing here changes product behaviour.

| File | What it is |
|---|---|
| `HANDOFF_BRIEF_2026-10-11.md` | The brief: owner report read stage by stage, tickets P-1, P-2, M-1, M-2, L-1, L-2, owner experiments E1 to E6, tests, decisions. Start here. |
| `privacy_ignore_candidates.tsv` | Candidate package names for ticket P-1, with confidence. Verify the "verify" rows on a phone before hard-coding. |
| `probes/svaresa_trim.cpp` | Measures the Svaresa loudness trim before and after the analysis becomes valid (ticket L-1). Reference harness. |
| `probes/lab_cost.cpp` | Host CPU cost of each Lab and Svaresa switch (ticket L-2). Reference harness. |
| `../LAB_INTEGRATION_PLAN.md` | The Lab and Svaresa review, rebased to 0.5.13, with the plan. |

**Target branch.** The owner's build is `ccr-f8964344-8f7mf5` (0.5.13, `fbe0680`). Implementation belongs there, on the
branch the owner names. This branch is 0.5.5 and is only the place where these documents were written.

**Not built here.** The probes are not wired into CMake. Compile them against `core/include` and `core/src` as shown in
the brief, §4.
