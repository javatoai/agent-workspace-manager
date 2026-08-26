---
name: awm
description: "Prepare an AWM Agent task from a requirement link through explicit user confirmation and a dedicated CLI executor."
---

# AWM Agent Handoff

Use this skill only when the user explicitly invokes `$awm`. It prepares an
Agent-created AWM task; it does not replace the normal desktop task-creation
flow.

The main agent owns understanding the request, drafting the handoff, showing
the plan, and receiving the user's confirmation. It must not invoke `awm`
itself. Delegate every `awm` CLI call to a single `awm-executor` subagent using
the instructions in [the executor profile](../../agents/awm-executor.md).

## Workflow

1. Ask only for information missing from the request JSON: task directory,
   branch, task group, selected services, requirement link, and a concise
   Chinese requirement title when Meegle cannot provide one. Draft a
   self-contained handoff; do not include chat/session IDs, private reasoning,
   credentials, or raw transcripts.
2. Have `awm-executor` run `awm agent inspect --json`. Stop if the task root or
   requirement-documentation root is not configured in AWM.
3. Have it run `awm agent plan --request <request.json> --json`. Present the
   full returned plan: task directory, requirement documentation directory,
   Sprint, historical-directory reuse flag, branch-reuse conflicts, fingerprint
   and expiry. Explain that the environment fingerprint binds the confirmation
   to those observed inputs.
4. Do not apply until the user explicitly confirms that exact plan. If a
   branch-reuse conflict is returned, show the complete `key`; include it in a
   new request only after the user explicitly confirms that specific reuse, then
   generate a fresh plan.
5. After confirmation, have the same executor run `awm agent apply` with only
   the returned operation ID and nonce. It must then run `awm agent status` and
   report the resulting task, `AGENTS.md`, `.awm/HANDOFF.md`, and documentation
   paths. If apply reports a changed fingerprint, return to planning rather than
   retrying.

Read [the protocol reference](references/protocol.md) when constructing a
request, interpreting a conflict, or handling an executor failure.

## Boundaries

- Never call desktop-only behavior or manually create a handoff/document
  directory to imitate this flow.
- A manual AWM desktop task has no mandatory `.awm/HANDOFF.md` and no automatic
  requirement-process documentation directory.
- The generated task `AGENTS.md` requires future coding agents to read the
  handoff before performing side-effecting work.
