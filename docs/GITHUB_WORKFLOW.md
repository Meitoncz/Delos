# Proposed GitHub task workflow

Prepared 2026-10-28. **Proposal only; no issues or project have been created.**

## Verified current access

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

## Proposed migration of the backlog

1. Start from the **82 unique IDs** in [TODO.md](TODO.md), retaining each ID in the corresponding
   issue so the history remains traceable. Do not import older stale checklists as additional work.
2. Write issue titles/bodies in English, as required for repository content. Each issue should state
   the current behavior, intended result, boundaries, dependencies and meaningful acceptance checks.
3. Classify verification items as tests, inherited TODOs as triage/research, and unselected ideas as
   needing a decision. They are not 82 confirmed missing features or bugs.
4. Use a parent issue for a larger deliverable where helpful. For example, **PyroWave upscaler parity**
   groups SGSR 1 Standard/HQ, real SGSR 2 Video Quality, sharpening and truthful controls (A01–A05).
5. Add issue dependencies for host APIs, external hardware or user assets. Keep the Project private
   independently of the repository's privacy.
6. Once imported, use GitHub as the current task-status source. Keep TODO.md as an ID/link index and
   keep architectural decisions/technical detail in repository documents; avoid two independent
   checklists drifting apart.

**Migration is not yet authorized/executed.** The user asked whether GitHub could be used and then
requested documentation and the end of this VS Code session. Review/approve this concrete setup
when resuming in Herdr before creating the issues or board.

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
