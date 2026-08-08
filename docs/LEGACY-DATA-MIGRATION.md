# 旧数据手工迁移指南

TaskWT 0.2.0 使用严格的新数组 schema，不会自动读取、迁移或改写 0.1.x 的配置和任务。这样可以避免在仓库身份、组归属或工作区策略不明确时静默做出错误选择。

本指南供未来的 AI 或维护者在用户确认后逐项迁移。不要把这些步骤做成启动时自动迁移。

## 安全前提

1. 完全退出 TaskWT，并确认没有正在执行的 Git、归档或 Tag 操作。
2. 复制整个 `~/TaskWorktreeManager` 为带时间戳的备份。
3. 复制旧 `taskRoot` 下的 `taskwt.json` 与 `AGENTS.md`；不要移动或删除任何 Worktree。
4. 对每个仓库记录 `git status --porcelain` 和 `git worktree list --porcelain`。有未提交内容时先让用户处理。
5. 所有转换先写入独立临时目录，通过校验后再由用户确认替换。

## 配置迁移

旧配置通常包含 `scanRoots`、以仓库 ID 为键的 `services` 对象和 `agentsMdAppendix`。新配置的 `schemaVersion` 为 `4`，使用 `repositories` 与 `groups` 数组，并用 `uatRef` / `cloneUatRef` 保存 `<remote>/<branch>`。

逐个旧服务执行：

1. 找到服务实际仓库目录，不要从旧扫描根目录重新递归发现仓库。
2. 用 Git 解析顶层目录、`git-common-dir`、当前分支和 `origin`。
3. 拒绝 Bare、子模块、Linked Worktree 或包含明文凭据的远程 URL；交给用户修正。
4. 按规范化 `git-common-dir` 去重并生成稳定仓库 ID，写入 `repositories` 数组。
5. 创建至少一个 `groups` 元素。若用户不需要分组，使用 `id: "default"`、`name: "默认组"`。
6. 按用户指定的顺序，把旧服务转换为各组的 `services` 数组；同一仓库在同一组内只能出现一次。
7. 让用户为每个组内服务确认 `STANDARD_WORKTREE` 或 `INDEPENDENT_CLONE`。

标准服务把旧基础分支和 UAT 配置放入 `modules` 数组。独立克隆服务必须确认 `cloneDefaultBranch`，并使用 `clone*` Tag 字段；不能猜测默认分支。

旧 `agentsMdAppendix` 经用户审阅后写入：

```text
~/TaskWorktreeManager/agents/global/AGENTS.md
```

不要再把说明正文写回配置 JSON。

## 任务迁移

每个旧任务都要单独确认所属组和每个服务的策略：

1. 保留原任务目录、Git 工作区和分支，不重新创建 Worktree 或 Clone。
2. 将旧 `taskwt.json` 复制到临时文件，再转换为 schema 3；补充 `groupId`。
3. 对标准 Worktree 补充 `groupServiceId`、`moduleId`、`moduleName`、`strategy`、`tagEnabled` 和 `baseRef`。
4. 对独立克隆补充实际分支、`strategy: "INDEPENDENT_CLONE"`、`originUrl` 和 Tag 状态。必须核对该目录是普通克隆而不是 Linked Worktree。
5. 校验 manifest 中的绝对路径确实位于预期任务目录或已确认的仓库目录，禁止路径穿越。
6. 把旧任务 `AGENTS.md` 中人工编写的内容提取到 `TASKWT:TASK-NOTES` 区；让 0.2.0 重新生成系统区。无法区分人工与生成内容时必须询问用户。
7. 原子替换单个任务的 manifest，然后在 UI 中验证；一个任务验证通过后再迁移下一个。

## 验证清单

- 启动只读取文件，没有 Fetch、扫描或外部请求。
- 单组时界面不显示多余组层级；多组顺序和服务顺序与数组一致。
- 每个仓库身份与 `git-common-dir` 一致，同组无重复仓库。
- 每个标准 Worktree 的分支和路径与 manifest 一致。
- 每个独立克隆的 `origin` 和实际分支与 manifest 一致。
- 任务 `AGENTS.md` 系统区可更新，人工区原样保留。
- 手动刷新只校验已配置内容，不发现新仓库。
- 归档/删除预检能识别未提交、未跟踪和进行中的 Git 操作。

## 回滚

若任一步失败，立即退出应用，恢复备份的配置和对应任务 manifest。不要删除 0.1.x Worktree 或分支，也不要用 `git reset --hard` 清理问题。记录失败任务、原文件、转换文件和错误信息，等待人工判断后再继续。
