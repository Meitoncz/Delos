# GitHub work index

Current selected work is tracked in [GitHub Issues](https://github.com/Meitoncz/Delos/issues) and the private [Delos Project](https://github.com/users/Meitoncz/projects/1). **Read GitHub for live status, comments, decisions and dependencies.** This file is a link/ID index, not a second checklist.

Migration approved by the user on 2026-10-28 after the codebase reconciliation. The initial import contains 22 issues: 17 concrete implementation/blocker records, one explicitly deferred design record and four grouped verification records. Creating an issue does not authorize implementation or redesign. Titles and bodies are English.

## Selected work

| Audit ID(s) | GitHub issue |
| --- | --- |
| N01 | [#1 — Constrain exported poster reads to the box-art cache](https://github.com/Meitoncz/Delos/issues/1) |
| A05 | [#2 — Report the applied supersampling level after fallback](https://github.com/Meitoncz/Delos/issues/2) |
| A06 | [#3 — Save live upscaler changes per game](https://github.com/Meitoncz/Delos/issues/3) |
| A07 | [#4 — Hide the PC cursor](https://github.com/Meitoncz/Delos/issues/4) |
| A08 | [#5 — Custom Delos launcher identity](https://github.com/Meitoncz/Delos/issues/5) |
| A09 | [#6 — Style the virtual-controls editor](https://github.com/Meitoncz/Delos/issues/6) |
| A11 | [#7 — Handle abnormal and shortcut stream exits in Delos UI](https://github.com/Meitoncz/Delos/issues/7) |
| A12 | [#8 — Remove three reachable ordinary confirmations](https://github.com/Meitoncz/Delos/issues/8) |
| A14 | [#9 — Adapt layouts to other screen shapes](https://github.com/Meitoncz/Delos/issues/9) |
| A15 | [#10 — Localize the new shell](https://github.com/Meitoncz/Delos/issues/10) |
| A16 | [#11 — Bundle approved Desktop/app artwork](https://github.com/Meitoncz/Delos/issues/11) |
| A17 | [#12 — Streaming-menu design polish](https://github.com/Meitoncz/Delos/issues/12) |
| B01 | [#13 — Wait for the game window on the client](https://github.com/Meitoncz/Delos/issues/13) |
| B02 | [#14 — Expose game-window readiness in Vibepollo](https://github.com/Meitoncz/Delos/issues/14) |
| B03 | [#15 — Synchronize artwork choices with the host](https://github.com/Meitoncz/Delos/issues/15) |
| B06 | [#16 — Expose and verify PyroWave 4:4:4](https://github.com/Meitoncz/Delos/issues/16) |
| B07 | [#17 — Complete PyroWave LZ4 handling](https://github.com/Meitoncz/Delos/issues/17) |
| B08 | [#18 — Measure and feed PyroWave bandwidth guidance](https://github.com/Meitoncz/Delos/issues/18) |
| C01, C03, C04, F11, G05, N02 | [#19 — Validate PyroWave image quality and sustained performance](https://github.com/Meitoncz/Delos/issues/19) |
| F01, F02, F05, F06, F12 | [#20 — Validate UI, controller and touch navigation](https://github.com/Meitoncz/Delos/issues/20) |
| F03, F04, F10, F13, F14 | [#21 — Validate pairing, PC management and host integrations](https://github.com/Meitoncz/Delos/issues/21) |
| C05, F07, F08, F09, F16 | [#22 — Validate device, Android version and external-display compatibility](https://github.com/Meitoncz/Delos/issues/22) |

## Merged-ID aliases

| Former ID | Follow the actual work |
| --- | --- |
| A10 | [A09 editor](https://github.com/Meitoncz/Delos/issues/6), [A11 errors/shortcuts](https://github.com/Meitoncz/Delos/issues/7), [A12 confirmations](https://github.com/Meitoncz/Delos/issues/8); not another stream-menu redesign. |
| F17 | Acceptance belongs to [B01 client](https://github.com/Meitoncz/Delos/issues/13) and [B02 host](https://github.com/Meitoncz/Delos/issues/14). |
| G05 | Additional hardware AV1 coverage is included in [codec verification](https://github.com/Meitoncz/Delos/issues/19). |

## Unselected candidates — no issue yet

These are retained ideas, investigations, optional measurements or maintenance, not a second active backlog. Consult the frozen audit for their rationale; ask for the appropriate product decision before selecting new work.

| Audit ID(s) | Candidate category |
| --- | --- |
| A13, B04, B05, C06, E01, E02, E03, E04, E07, E08, E09, E10, G08, H01, H02, H03, H04 | Design / product / hardware research |
| C02, C07, G01, G03, G04, G07, G11 | Unconfirmed investigations |
| D01, D02, D03, D04, D05 | Maintenance candidates |
| F15, G10 | Optional measurements |
| E05, E06 | Unselected gesture extensions |
| I01, I02, I03 | Recurring work; create a bounded issue only when triggered |

## Retired records

A01, A02, A03, A04, G02, G06, G09, G12 are implemented or historical records, not missing-feature issues. Selected controller-variant verification remains in [#20](https://github.com/Meitoncz/Delos/issues/20).

## Frozen code audit and evidence

The full [pre-migration audit at cab8c7c4](https://github.com/Meitoncz/Delos/blob/cab8c7c4/docs/TODO.md) preserves all 83 original IDs plus N01/N02, source evidence, runtime results and limitations. It describes source snapshot `6d0688c2`; its dispositions are historical, not current issue status. No original audit ID is silently discarded.

Use [GITHUB_WORKFLOW.md](GITHUB_WORKFLOW.md) for daily operation. [ROADMAP.md](ROADMAP.md) remains high-level phase/history documentation, not a duplicate task-status list.
