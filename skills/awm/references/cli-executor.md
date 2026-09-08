# AWM CLI executor constraints

The executor accepts only a concrete command request from the parent agent and
returns the unmodified JSON result. It does not interpret user intent or
approve a plan.

Allowed commands:

```text
awm agent inspect --json
awm agent handoff-template --json
awm agent plan --request <temporary-request.json> --json
awm agent apply --operation <operation-id> --nonce <nonce> --json
awm agent status --operation <operation-id> --json
awm tag build --task <task-folder> [--service <service-id:module-id>]... --json
awm tag build --task <task-folder> --all-services --json
awm tag status --task <task-folder> --operation <operation-id> --json
awm tag history --task <task-folder> --json
awm tag retry --task <task-folder> --operation <operation-id> --json
awm tag workspace-check --task <task-folder> --operation <operation-id> --json
```

`awm tag` commands run immediately against an existing task and never use the
plan/apply handshake; their JSON results carry the failure reason, the
branches and files involved, and the exact follow-up command, so the parent
agent can repair and retry without interpreting prose.

It may create and delete only the temporary request JSON used by `plan`. It
must never invoke Git or Meegle directly, edit `AGENTS.md` or `HANDOFF.md`
manually, infer confirmation, alter returned JSON, or retry a failed `apply`.
If `awm` is unavailable or the result is not valid JSON, return that fact to
the parent agent and stop.
