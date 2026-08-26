# AWM Agent CLI protocol

The executor uses the `awm` executable distributed from this repository's
`:cli:installDist` output. It always requests JSON output and never substitutes
direct Git, Meegle, or filesystem mutations for a CLI operation.

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

`requirementTitle` is required only when local Meegle cannot provide a title.
The self-contained handoff is written to `.awm/HANDOFF.md`; it must not include
credentials, raw reasoning, or a conversation/session ID.

## Plan and apply

`plan` persists an audit record but creates no task, worktree, handoff, or
process-document directory. `apply` expires after ten minutes and recomputes a
fingerprint over configuration, task path, Sprint/history decision, and branch
state before creating anything.

## Shared requirement materials directory

AWM uses the configured `requirementMaterialsRoot` and
`requirementMaterialsSubdirectory` for both desktop requirement materials and
Agent process documents. It first searches the materials root for the exact
identity `{space, kind, workItemId}`. One valid historical directory is
reused; multiple `<workItemId>` / `<workItemId>-*` matches block the operation.
Without history, it requires exactly one linked Sprint whose status is `进行中`.
The requirement directory name is always the task `folderName`; the request's
`requirementTitle` is used only as a Markdown title.

```text
<requirementMaterialsRoot>/
  .awm-requirement-index.jsonl
  <Sprint>/
    .awm-iteration.json
    00-迭代任务总览.md
    <需求编号>-<任务文件夹名>/
      .awm-requirement.json
      00-需求总览.md
      <requirementMaterialsSubdirectory>/
        ... process documents and development materials ...
```

The CLI's returned `write_root` points to the final subdirectory shown above.
`awm agent apply` writes process documents there; `.awm/HANDOFF.md` remains in
the task directory. Existing documentation manifests are validated for
identity before reuse. AWM never moves, deletes, or automatically migrates
old standalone documentation directories.

## Branch reuse

Each branch conflict returns a complete `key` object. The user must approve
that exact key; insert it into `confirmedBranchReuseKeys` and generate a fresh
plan. A changed key invalidates prior approval.
