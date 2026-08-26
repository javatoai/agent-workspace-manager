---
name: awm
description: Prepare and create an AWM task through the installed awm Agent CLI when the user explicitly invokes $awm; do not use for ordinary desktop task creation.
---

# AWM Agent CLI

Use this skill only when the user explicitly invokes `$awm`. It creates an AWM
task through the installed `awm` Agent CLI, and does not replace the ordinary
desktop task-creation flow.

The main agent owns requirement understanding, the handoff draft, plan display,
and user confirmation. Delegate every `awm` CLI call to one executor subagent
using [the executor constraints](references/cli-executor.md); the main agent
must not invoke `awm` directly.

## Workflow

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
  0.12.x install strictly rejects 0.11.x and earlier config/task schemas and
  does not migrate old standalone documentation directories.
- A manually created desktop task has no mandatory `.awm/HANDOFF.md` and does
  not automatically create process documents; it only creates the configured
  materials directory when a requirement is associated.
