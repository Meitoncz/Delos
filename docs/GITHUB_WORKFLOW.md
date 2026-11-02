# GitHub task workflow

Activated 2026-10-28. After the initial selective import, the user explicitly approved moving **all unfinished intentions** to GitHub and removing Markdown backlogs.

## One task tracker

- [Issues](https://github.com/Meitoncz/Delos/issues) and the private [Delos Project](https://github.com/users/Meitoncz/projects/1) are the only source of truth for work, ideas, research, deferred/conditional plans, verification and recurring maintenance.
- Read current issue descriptions, comments, dependencies and Project fields before starting. Do not rely on stale local notes or silently recreate local tracking when authorization is unavailable.
- Do not create Markdown TODO files, roadmaps, task indexes or parallel status checklists. Markdown may contain technical specifications, setup guidance, provenance and explicitly dated evidence, not another task queue.
- Repository and Project must remain private. Titles, descriptions and work updates are English; user discussion is Czech.
- The approved [prototype](prototype/delos-gui.html) and [GUI specification](GUI.md) remain technical/design references. Creating an issue is not permission to implement, redesign or publish.

## Field semantics

| Field | Values / use |
| --- | --- |
| Status | Backlog; Needs decision; Ready; In progress; Odin testing; Blocked; Done. |
| Work type | Bug; Feature; Verification; Maintenance; Research; Idea. |
| Area | Streaming; UI; Controls; Host integration; Platform; Documentation; Upstream. |
| Priority | P1; P2; P3. Leave unset until agreed. |

Backlog records work without selecting it. Needs decision requires the specified product/design/protocol agreement. Ready means scope/prerequisites are available. In progress is active work, normally one task. Odin testing is relevant physical-device acceptance. Blocked names an unavailable prerequisite. Done means actual criteria, relevant checks/acceptance, integration and push are complete.

Use matching type/area labels. `needs-decision`, `blocked`, `deferred`, `conditional` and `recurring` make approval/prerequisites explicit. Research is not a proven bug; verification does not mean the feature is absent. A recorded idea is not approved development. Conditional publication records do not authorize releases, signing or version work.

## Daily operation

1. Refresh the selected issue and comments. Resolve new user instructions and dependencies; record agreed decisions in English. The user chooses priorities in Czech. Ask before open design/product decisions.
2. New unfinished intentions go directly into an appropriate issue, including deferred ideas. Search first: update an existing record or a grouped acceptance checklist instead of duplicating it. Do not create open issues for already completed/historical work.
3. Move selected unblocked work to In progress. Queue new requests behind active work unless the user explicitly changes direction. A brief status question does not cancel work.
4. Follow feature branch → relevant local build/tests/lint → physical Odin acceptance where applicable → commit → fast-forward main → push with `[skip ci]` → completed-branch cleanup. Documentation/comment-only work needs no APK rebuild/install or game launch. Never reinstall during a stream, inject device navigation or remotely launch games.
5. Record actual test environment, observations and untested cases. File a linked bug for demonstrated failures. Unavailable hardware remains unverified, not a fabricated pass or a new missing feature.
6. Post the implementation/decision result, exercised checks and completion commit. Close/set Done only after actual criteria are met. Keep blockers, unfinished acceptance and unselected ideas open; keep Project fields and relevant labels consistent. Never copy live status into Markdown.
7. For recurring records, add dated review results and create bounded linked work for concrete changes. A standing record is not completed by one review unless explicitly retired. No scheduled/background automation is implied.
8. Refresh GitHub at the next task interaction; no persistent background watcher is running. Preserve related technical documentation when behavior changes, but keep its task state on GitHub.

## Roadmap

Agreed 2026-11-02. The roadmap lives only on GitHub: **milestones M1–M7**, **Research and ideas** and **After publication** order the open work by phase (the milestone description states each goal), and the Project **Priority** field holds P1 (M1, M2), P2 (M3–M5) or P3 (M6, M7, research). Recurring records (I01–I03) have no milestone or priority; conditional publication records have a milestone but no priority until the project goes public. Milestones have no due dates: the order is the plan. Move an issue by changing its milestone and priority and record the reason in a comment. Add the Project **Roadmap** view (layout Roadmap, group by Milestone) in the web UI; the API cannot create views. A milestone is closed when all its issues are closed.

## CLI examples (PowerShell)

```powershell
gh issue list --repo Meitoncz/Delos --state open --limit 100
gh issue view 13 --repo Meitoncz/Delos --comments
gh project item-list 1 --owner Meitoncz --limit 100 --format json
```

Use the issue number selected for the actual task. CLI access must cover the private repository; Project operations additionally need Projects authorization. Never store credentials/device codes in repository files.

## Cutover evidence — historical, not a live issue index

The full migration checkpoint contains 52 issues: the initial 22 plus 30 new records; six remaining conditional/optional checks were folded into existing verification issues. All 77 non-retired audit IDs are represented, including merged aliases. Eight implemented/historical records were excluded. Read GitHub for subsequent counts/status.

The [frozen code audit at 299d98b1](https://github.com/Meitoncz/Delos/blob/299d98b1/docs/TODO.md) retains all 85 source records and evidence for source snapshot `17310729`; it is history, not a local task queue. The Project README explains original-ID aliases. Detailed readiness research and idea constraints were moved into their issues before the planning files were removed. Feature attribution is preserved in the [README](../README.md#feature-provenance); current UI architecture is preserved in GUI.md.

REST/GraphQL readback verifies issue content/labels, Project membership/fields, privacy, saved Board/All work layouts and the native client→host readiness dependency. The managed browser is signed out and the authenticated relay unavailable; a signed-in visual check is not claimed.

## No implied CI or release

GitHub Actions run only on explicit user request. No Actions, schedules, releases, milestones, versions or signing changes are authorized merely by task tracking. The build workflow (`.github/workflows/build.yml`) has only a `workflow_dispatch` trigger, so neither pushes, pull requests nor issue creation start it; repository pushes still carry `[skip ci]` in case a trigger is added later.
