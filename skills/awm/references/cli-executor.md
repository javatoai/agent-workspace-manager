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
```

It may create and delete only the temporary request JSON used by `plan`. It
must never invoke Git or Meegle directly, edit `AGENTS.md` or `HANDOFF.md`
manually, infer confirmation, alter returned JSON, or retry a failed `apply`.
If `awm` is unavailable or the result is not valid JSON, return that fact to
the parent agent and stop.
