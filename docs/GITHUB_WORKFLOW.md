# GitHub task workflow

Activated 2026-10-28 after the user reviewed the code-backed inventory and explicitly approved migration. The user completed GitHub device authorization for Projects.

## Source of truth

- Repository: [Meitoncz/Delos](https://github.com/Meitoncz/Delos), private, Issues enabled.
- Selected work and current discussions: [Issues](https://github.com/Meitoncz/Delos/issues).
- Private planning Project: [Delos](https://github.com/users/Meitoncz/projects/1), linked to this repository. Views: **Board** and **All work**.
- Original-ID routing: [TODO.md](TODO.md), a link index only. Roadmap, UI plans and technical documents provide context; do not maintain competing live task checklists.
- Full reconciliation/evidence: [frozen audit cab8c7c4](https://github.com/Meitoncz/Delos/blob/cab8c7c4/docs/TODO.md), source snapshot `6d0688c2`.

Issue titles, bodies and work updates are English. User conversations remain Czech. The Project and repository must stay private unless the user explicitly changes that decision.

## Project fields

| Field | Values / use |
| --- | --- |
| Status | Backlog; Needs decision; Ready; In progress; Odin testing; Blocked; Done. |
| Work type | Bug; Feature; Verification; Maintenance. GitHub reserves the custom-field name Type. |
| Area | Streaming; UI; Controls; Host integration; Platform; Documentation; Upstream. |
| Priority | P1; P2; P3. Leave unset until priority is actually agreed. |

| Status | Meaning |
| --- | --- |
| Backlog | Recorded, not selected for implementation. |
| Needs decision | Resolve the specified product, visual, protocol or interaction decision first. |
| Ready | Scope and prerequisites are agreed; available for selection. |
| In progress | Actively implementing; normally one task at a time. |
| Odin testing | A locally checked build is ready/installed for the relevant physical-device acceptance. |
| Blocked | A stated host, asset, device or other prerequisite is unavailable. |
| Done | Relevant checks, user acceptance where needed, integration and push are complete. |

Labels identify bugs/features/testing and areas. Keep `needs-decision`, `blocked` and `deferred` aligned with the issue/Project where applicable. A17 is explicitly deferred. Creating an issue is not approval to implement or redesign. No priorities or active implementation were selected by the migration itself.

## Initial import

- 17 selected concrete implementation/blocker records, A17 as a separate deferred design record, and four grouped verification issues: **22 total**. Exact links/IDs are in TODO.md.
- Implemented, historical and duplicate generic tasks were not imported. Unselected ideas, conditional publication work, optional measurements and recurring maintenance remain candidates in the frozen audit. Create a bounded issue only when the user selects work or a concrete trigger occurs.
- Partial implementations describe only their actual remainder. Per-game defaults, paddle mappings, four PyroWave upscaler modes, local artwork and existing import/clipboard features must not be reintroduced as missing.
- Native dependency: [B01 client readiness #13](https://github.com/Meitoncz/Delos/issues/13) is blocked by [B02 Vibepollo readiness #14](https://github.com/Meitoncz/Delos/issues/14). Combined acceptance includes former F17.
- Verification is grouped into [stream quality #19](https://github.com/Meitoncz/Delos/issues/19), [UI/input #20](https://github.com/Meitoncz/Delos/issues/20), [host integrations #21](https://github.com/Meitoncz/Delos/issues/21), and [platform/display #22](https://github.com/Meitoncz/Delos/issues/22). These are existing-functionality acceptance, not claims that the features are absent.
- N01 is a confirmed bug; N02 stays a conditional investigation inside #19 until the relevant real host reproduces it. Inherited FIXME comments do not establish a bug.

## Daily operation

1. Read the current issue description, all relevant latest comments, Project fields and dependencies before starting. Resolve conflicting/new user instructions in chat and update the issue accordingly. Do not rely only on a stale local note.
2. The user chooses priorities in Czech. Record agreed scope/decisions in English. Ask before open design decisions; never redesign under the cover of an implementation issue.
3. Move selected, unblocked work to In progress. New requests queue behind current work unless the user explicitly switches tasks. Brief status questions do not cancel active work.
4. Follow the existing feature-branch → local build/tests/lint as relevant → physical Odin acceptance where applicable → commit → fast-forward main → push with `[skip ci]` → feature-branch cleanup workflow. Documentation-only work needs no APK rebuild/install or game launch. Never reinstall during a stream, inject device navigation or remotely launch PC games.
5. For verification, record actual environment, scenarios, observed results and untested cases. A reproduced consumer-visible failure gets a linked bug; unavailable hardware remains unverified, not a fabricated pass or new feature.
6. Add an issue update with implementation/decision evidence, exercised checks, commit link and remaining limitations. Only close the issue and set Done after its actual completion criteria are met; blockers/unfinished acceptance remain open. Keep GitHub fields/labels consistent instead of copying status back into TODO.md.
7. Reopen/update the relevant existing issue for revised work rather than creating duplicates. Add newly selected items to the link index if useful. No persistent background watcher is implied: refresh GitHub at the next task interaction.

## Reading and updating with GitHub CLI

```powershell
gh issue list --repo Meitoncz/Delos --state open
gh issue view 13 --repo Meitoncz/Delos --comments
gh project item-list 1 --owner Meitoncz --format json
```

Use the current issue number selected by the user. Comments and task changes require the same repository/account access as this migration; Project fields additionally require Projects authorization. Do not store credentials or device codes in repository files.

## No implied automation or publication

GitHub Actions remain opt-in on explicit request. No Actions, scheduled automation, releases, milestones, version numbers or signing changes were introduced. Issue creation does not trigger the current push/pull-request/manual build workflow; documentation pushes retain `[skip ci]`. Publication-only candidates remain unselected.

## Verification boundary

The migration is verified against live GitHub REST/GraphQL records: issue content/labels, Project membership/fields, private visibility, saved view layouts and the native readiness dependency. The managed browser is not signed in, and the authenticated browser relay is unavailable, so a signed-in visual check is not claimed.
