# AWM Agent CLI protocol

The plugin relies on the `awm` executable distributed from this repository's
`:cli:installDist` output. The executor must use JSON output and must not run
Git, Meegle, or direct filesystem mutations as substitutes for AWM.

## Request file

```json
{
  "folderName": "OBT-7064764629-需求中文简写",
  "featureBranch": "feature/OBT-7064764629",
  "groupId": "default",
  "serviceIds": ["service-id"],
  "requirementLink": "https://project.feishu.cn/obt/userstory/detail/7064764629",
  "requirementTitle": "需求中文简写",
  "taskNotes": "",
  "handoffMarkdown": "# AWM 任务交接\n\n## 目标\n\n- ...",
  "confirmedBranchReuseKeys": []
}
```

`requirementTitle` is a short Chinese title for the directory name. It is only
needed when the local Meegle client cannot read a title. The handoff is written
as `.awm/HANDOFF.md`; it must be self-contained and must not contain a source
conversation/session ID, credentials, or raw chain-of-thought.

## Commands

```text
awm agent inspect --json
awm agent handoff-template --json
awm agent plan --request <request.json> --json
awm agent apply --operation <operation-id> --nonce <nonce> --json
awm agent status --operation <operation-id> --json
```

`plan` stores an audit record but does not create a task, worktree, handoff, or
documentation directory. `apply` expires after ten minutes and recomputes a
fingerprint over the configuration, task path, Sprint/history decision, and
branch state before creating anything.

## Sprint and documentation decision

AWM first searches the configured documentation root for an exact manifest
identity `{space, kind, workItemId}`. One valid historical directory is reused.
Multiple historical directories block the operation. If there is no history,
AWM queries the linked requirement's Sprint associations through the local
Meegle CLI and requires exactly one Sprint whose status is `进行中`.

For a new document workspace AWM writes:

```text
<documentation-root>/
  .awm-requirement-index.jsonl
  <sprint-label>/
    .awm-iteration.json
    00-迭代任务总览.md
    <requirement-id>-<Chinese-title>/
      .awm-requirement.json
      00-需求总览.md
```

The index is only an accelerator: its target manifest is validated before it is
used. AWM does not fuzzy-match titles or mutate a pre-existing non-AWM folder.

## Branch reuse

Every conflict contains a complete object under `key`:

```json
{
  "repositoryId": "...",
  "branch": "...",
  "stateFingerprint": "..."
}
```

The user must explicitly approve that exact key. Put it in
`confirmedBranchReuseKeys` and run a new `plan`; a changed key means the old
approval is invalid.
