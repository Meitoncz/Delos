# Proposed GitHub task workflow

Prepared 2026-10-28. **Proposal only; no issues, labels or Project board were created by this work.**

## Last checked access

- Repository: `Meitoncz/Delos`, private, Issues enabled.
- `gh repo view` works and `gh issue list --state all` returned no issues.
- GitHub CLI is installed and can access this repository; an additional plugin is not required for
  this local CLI workflow.
- `gh project list --owner Meitoncz` failed because the token lacks `read:project`.
  Consequently, existing Projects could not be enumerated; do not infer that none exist.
- Project creation/editing needs appropriate project authorization (the CLI's `project` scope),
  beyond the repository access already working. No token scopes, account settings or visibility
  have been changed. Do not start interactive authorization until the user is ready.

## Recommended structure

Use **Issues for work records** and a **private Project named Delos for the board**. GitHub supports
parent/sub-issues and dependencies, so larger features can group their implementation steps.

Suggested status field:

| Status | Meaning |
| --- | --- |
| Backlog | Recorded but not selected next; an idea is not implementation approval. |
| Ready | Scope is agreed and prerequisites are available. |
| In progress | Currently being implemented; normally one active task. |
| Odin testing | A concrete, locally validated build is installed for the user's acceptance. |
| Blocked | Needs host work, hardware, assets, permission or a product decision. |
| Done | Relevant checks, user acceptance where needed, integration and push are complete. |

Use labels for kinds (`feature`, `bug`, `testing`, `research`, `maintenance`, `idea`) and areas
(`pyrowave`, `ui`, `controls`, `host`, `quality`, `upstream`). Keep approval state separate:
`needs-decision`, `deferred` or `conditional` must not look like selected development.
Priority can be a Project field; no release milestones or dates are implied.

## Required audit and review gate before migration

The user explicitly required a fresh codebase reconciliation before creating any issues.
The earlier suggestion to import all 82/78 items was withdrawn: those numbers mixed implementation,
verification, old comments, deferred ideas and completed behavior.

[TODO.md](TODO.md) now records the code-backed dispositions of every original ID, plus separately
identified audit findings. It includes evidence paths, concrete remainders and runtime-check limits.
It is an audit inventory, **not a bulk-import queue**.

1. Present and review the corrected inventory with the user. No migration is authorized merely
   because the audit is complete. Do not start Project authorization or create issues yet.
2. **Issue titles and bodies must be in English.** Chat and priority decisions remain Czech.
3. Create work records only for the selected, reviewed remainders. Preserve old IDs as references;
   do not open implemented, merged or historical items as missing features.
4. Separate implementation from verification and approval. Untried hardware does not prove a bug;
   optional design ideas remain `needs-decision`/`deferred`; host/assets dependencies stay blocked.
5. Scope partial work narrowly. Per-game defaults already exist (A06 lacks live-save interaction);
   paddle handling exists (G02 is retired); the normal stream already uses the new menu (A10 is
   merged into its actual remaining areas). A05 is now only the applied-level allocation-fallback
   reporting gap, not another four-mode upscaler implementation.
6. Combine dependent acceptance with its feature where sensible: F17 belongs to B01/B02.
   Use parents/sub-issues and dependencies only when they reflect genuine deliverables.
7. Evaluate new findings separately. N01 is runtime-confirmed with an audit-owned fixture; N02
   is a conditional code-level HDR concern, not a reproduced general failure.
8. Keep any future Project private independently of repository visibility. After an approved import,
   use GitHub for current task status and TODO.md as an ID/link index, not a competing checklist.

The earlier A01–A05 PyroWave parity example is no longer an open parent deliverable: A01–A04 are
implemented and A05 has only the narrow reporting remainder above. No release milestone is implied.

## Day-to-day operation

- The user chooses priorities in Czech in chat; the agent updates English issue records.
- Acknowledge new requests, queue them behind current work and finish that work first unless the
  user explicitly requests a stop/switch. Brief status requests do not cancel active work.
- Move one selected issue through implementation, local build/tests/lint and device acceptance.
  Never reinstall during a stream, inject Odin navigation or launch a PC game remotely.
- Finish the established feature-branch → fast-forward main → `[skip ci]` push → branch cleanup
  workflow, attach the commit/checks to the issue, then mark it done. Documentation-only tasks do
  not require an APK reinstall or a game launch.
- GitHub Actions remain **opt-in on explicit request**. Do not add Actions or a persistent scheduled
  automation just to manage the board. The existing build workflow runs on push/pull request/manual
  dispatch, not on issue creation.
- Issue creation and updates can already use GitHub CLI. The Project board can be added once the
  account permissions are deliberately granted; repository visibility must remain private.

## Official references

- [GitHub Issues](https://docs.github.com/en/issues/tracking-your-work-with-issues/learning-about-issues/about-issues)
- [GitHub Projects](https://docs.github.com/en/issues/planning-and-tracking-with-projects/learning-about-projects/about-projects)
- [Sub-issues](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/adding-sub-issues)

These pages were checked on 2026-10-28. Account permissions above come from the actual local CLI
responses, not from a documentation assumption.
