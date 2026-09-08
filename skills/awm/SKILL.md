---
name: awm
description: Prepare and create an AWM task through the installed awm Agent CLI when the user explicitly invokes $awm, and build or track test Tags for an existing task; do not use for ordinary desktop task creation.
---

# AWM Agent CLI

Use this skill only when the user explicitly invokes `$awm`. It drives the
installed `awm` Agent CLI, and does not replace the ordinary desktop
task-creation flow.

The main agent owns requirement understanding, the handoff draft, plan display,
and user confirmation. Delegate every `awm` CLI call to one executor subagent
using [the executor constraints](references/cli-executor.md); the main agent
must not invoke `awm` directly.

The CLI has two independent modes. Route the request before doing anything
else:

- **Create a task** from a requirement: follow the guarded plan/apply workflow
  below.
- **Build or track a test Tag** for a task that already exists: follow the Tag
  workflow instead. It never uses plan/apply.

## Task creation workflow

1. Collect only missing request fields: task folder, branch, group, services,
   requirement link, and a concise Chinese title if the local Meegle client
   cannot read one. Draft a self-contained handoff without credentials, raw
   transcripts, session IDs, or private reasoning.
2. Run `awm agent inspect --json` through the executor. Stop if the AWM task
   root, requirement materials root, or requirement materials subdirectory is
   unavailable.
3. Run `awm agent plan --request <request.json> --json`. Present the complete
   returned plan, including the shared requirement materials directory and
   `write_root`, Sprint, historical reuse decision, branch-reuse conflicts,
   fingerprint, and expiry.
4. Apply only after the user explicitly confirms that exact plan. A branch
   reuse key must be explicitly approved, inserted in a fresh request, and
   planned again.
5. Run `awm agent apply` with exactly the returned operation ID and nonce, then
   run `awm agent status` and report the task, `AGENTS.md`, `.awm/HANDOFF.md`,
   and process-document paths.

Read [the protocol reference](references/protocol.md) for request format,
Sprint resolution, process-document layout, and branch reuse handling.

## Tag workflow

Tag commands run immediately against an existing task, so confirm the task and
services with the user before the first build. Report the returned Tag, state,
and Genbu stages verbatim.

1. Run `awm tag history --task <task-folder> --json` when the operation ID is
   unknown.
2. Run `awm tag build` for the requested services, or `awm tag status` to
   follow a Tag's Genbu build and UAT release.
3. On a problem record, dispatch on the returned `retryKind` and follow
   `guidance`. Resolve a merge conflict in the task's own worktree, then run
   `awm tag retry`; never rewrite history or force-push to reach a green Tag.

Read [the Tag build reference](references/tag-builds.md) for the commands,
result shape, and the full self-repair loop.

## Boundaries

- Do not manually create task, handoff, or process-document directories to
  imitate the CLI workflow.
- The only requirement directory configuration is
  `requirementMaterialsRoot` plus `requirementMaterialsSubdirectory`. Desktop
  task creation creates or reuses the materials directory; `awm agent` writes
  process documents into the returned `write_root` inside that same directory.
  The requirement directory name always uses the task folder name; a request's
  `requirementTitle` is a Markdown title only.
- Do not use or recreate the removed `requirementDocumentationRoot` field. A
  1.0.x install strictly rejects 0.12.x and earlier config/task schemas and
  does not migrate old standalone documentation directories.
- A manually created desktop task has no mandatory `.awm/HANDOFF.md` and does
  not automatically create process documents; it only creates the configured
  materials directory when a requirement is associated.
- Tag commands mutate remote Git state. Never substitute direct Git commands
  for `awm tag`, and never retry a Tag on a task the user did not name.
