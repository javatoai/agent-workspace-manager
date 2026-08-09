# 配置与使用

## 配置目录

Windows 使用 `%USERPROFILE%\.AgentWorkspaceManager`，macOS 使用 `~/.AgentWorkspaceManager`。0.3.0 的说明文件固定保存在：

```text
agents/global/AGENTS.md
agents/groups/<groupId>/AGENTS.md
```

磁盘文件是唯一可信来源；说明正文不重复保存在 `config.json`。

## 严格数组 schema

0.3.0 的 `config.json` 使用严格 schema 6。顶层仓库和组均为数组，数组顺序就是界面顺序：

```json
{
  "schemaVersion": 6,
  "taskRoot": "Q:\\tasks",
  "repositories": [
    {
      "id": "repo-…",
      "name": "order-service",
      "rootPath": "Q:\\services\\order-service",
      "gitCommonDirectory": "Q:\\services\\order-service\\.git",
      "originUrl": "git@github.com:example/order-service.git",
      "currentBranch": "master",
      "defaultRemoteBranch": "master"
    }
  ],
  "groups": [
    {
      "id": "default",
      "name": "默认组",
      "uatTagEnabled": true,
      "defaultBranchPrefix": "feature/zhangsan_{num}_",
      "defaultWorkspaceToolIds": ["codex"],
      "services": [
        {
          "id": "service-order",
          "repositoryId": "repo-…",
          "displayName": "订单服务",
          "enabled": true,
          "ideType": "IDEA",
          "strategy": "STANDARD_WORKTREE",
          "modules": [
            {
              "id": "default",
              "name": "",
              "baseRef": "origin/master",
              "baseRemote": "origin",
              "uatTagEnabled": true,
              "uatRef": "origin/release/test",
              "initialUatTag": null,
              "tagMessagePrefix": "UAT"
            }
          ],
          "cloneDefaultBranch": null,
          "cloneUatTagEnabled": false,
          "cloneUatRef": "origin/release/test",
          "cloneInitialUatTag": null,
          "cloneTagMessagePrefix": "UAT",
          "bootstrap": {
            "copyRules": [],
            "commands": []
          }
        }
      ]
    }
  ],
  "theme": "SYSTEM"
}
```

未知字段、旧 schema 或未来 schema 都会被拒绝，应用不会自动迁移或改写原文件。旧 TaskWT 用户目录与任务文件不会被读取、迁移或删除。

## 组

- 配置始终至少有一个组；只有一个组时，任务和服务页面隐藏组选择与折叠层级。
- 多组时，任务和服务按组折叠展示。
- 设置页可创建、重命名、排序组；只有空组可以删除，且必须保留至少一个组。
- 一个仓库可以加入多个组，但同一组内只能出现一次。
- 组内服务也使用数组保存并支持排序。
- `defaultBranchPrefix` 可包含唯一占位符 `{num}`。飞书链接优先使用工作项 ID；其他 URL 忽略 query/fragment 后从 path 取最后一段数字，普通文本取最后一段数字。无法解析时必须手工修正分支后才能创建。

## 人工添加仓库

设置页通过操作系统原生目录选择器一次选择一个或多个仓库目录。验证过程会：

1. 解析所选路径所属的 Git 顶层目录；
2. 获取规范化 `git-common-dir` 作为物理仓库身份；
3. 拒绝非 Git 目录、Bare 仓库、子模块和临时 Linked Worktree；
4. 读取当前分支及 `origin`，拒绝 URL 中嵌入的明文凭据；
5. 每个目录独立校验，不递归扫描其子目录、父目录或同级目录；
6. 合法仓库批量写入一次配置，新增服务默认采用标准 Worktree，之后可在服务配置中修改策略。

应用启动不会重新校验全部仓库或访问远端；只对当前任务执行本地只读 Git 状态检查。用户点击顶部“刷新”后才重新校验已经配置的仓库。

当前任务会异步运行 `git status --porcelain=v2 -z --untracked-files=all`，展示包括未跟踪文件在内的未提交文件数；同时只比较本地已知 upstream 与 `HEAD`，不会为了状态展示执行 Fetch 或访问远端。

## 服务工作区策略

### 标准 Worktree

标准服务至少有一个模块。每个模块指定 `baseRemote`、基础 Ref、`uatRef` 和 Tag 子开关；基础远程与 UAT 目标引用相互独立。`uatRef` 统一使用 `<remote>/<branch>` 格式，例如 `origin/release/test`：

- 不同基础 Ref 分别创建 Worktree；
- 相同基础 Ref 只创建一个 Worktree，具体模块的修改边界写入组级 `AGENTS.md`；
- 单模块保留用户输入的任务分支名；
- 多模块按基础 Ref 自动添加后缀，例如 `feature/ABC-master`、`feature/ABC-development`。

创建前会执行 `fetch --prune --tags`，并从最新的 `refs/remotes/<remote>/<branch>` 创建 Worktree；不会切换或移动用户本地 `master`。标准服务创建 Worktree 后按服务配置执行 Bootstrap。

### 独立克隆

独立克隆服务必须保存默认远程分支，创建任务时可以覆盖。它从 `origin` 完整克隆并直接切到该分支，不创建额外 Feature 分支或 Linked Worktree，也不执行 Bootstrap。归档和删除仍会进行 Git 安全检查。

## UAT Tag 开关

有效 Tag 入口需要同时满足：

1. 任务所属组的 `uatTagEnabled` 已开启；
2. 标准模块的 `uatTagEnabled` 或独立克隆的 `cloneUatTagEnabled` 已开启。

独立克隆启用后，以任务中实际克隆的分支参与 UAT，而不是重新派生 Feature 分支。

## 三级 AGENTS.md

最终任务说明按以下层级合成：

1. 系统生成的任务与工作区信息；
2. 全局说明；
3. 组说明；
4. 任务人工说明。

冲突时任务级优先，其次是组级，再其次是全局。任务文件用以下协议分隔系统区和人工区：

```text
<!-- AWM:GENERATED:BEGIN -->
…系统生成内容…
<!-- AWM:GENERATED:END -->

<!-- AWM:TASK-NOTES:BEGIN -->
…用户可编辑内容…
<!-- AWM:TASK-NOTES:END -->
```

重新生成只替换系统区并保留人工区。应用使用 WatchService、窗口聚焦补检、内容哈希、防抖和原子写入同步文件；外部修改与未保存编辑冲突时必须由用户选择磁盘版本或本地版本。标记缺失或重复时会停止自动覆盖，等待用户修复。

## Bootstrap

Bootstrap 仅适用于标准 Worktree。复制规则必须使用明确的相对路径，禁止绝对路径、`..`、`.git` 和符号链接穿越。命令按声明顺序运行，单步失败会记录警告并继续后续步骤，最终工作区标记为 `READY_WITH_WARNINGS`。

## 任务工作区工具与任务 schema

`agent-workspace.json` 使用严格 schema 5。创建任务时会继承所属组的 `defaultWorkspaceToolIds`，用户可以在创建页增减。任务本身创建成功后，工具适配器逐项打开；其中一个失败不会回滚 Git 工作区，也不会阻止其他工具。

```json
{
  "schemaVersion": 5,
  "workspaceToolLaunches": [
    {
      "toolId": "codex",
      "status": "OPENED",
      "updatedAt": "2026-01-01 00:00:00",
      "message": null
    }
  ]
}
```

未注册的工具 ID 会原样保留在配置中并在界面显示为“当前不可用”。Core 只认识通用工具 ID 和执行结果，不依赖 Codex、Claude、Cursor 的 URI 或命令。

AWM 生成的任务、Tag 操作、历史记录、JSONL 事件和 AGENTS.md 时间统一使用 `Asia/Shanghai` 时区，格式为 `yyyy-MM-dd HH:mm:ss`。这不会重写 Git 提交时间、远程 Tag 原始时间或文件系统修改时间。
