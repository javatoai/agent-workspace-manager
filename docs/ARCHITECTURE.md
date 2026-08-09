# 架构与测试说明

## 模块和分层

0.3.0 只保留两个 Gradle 模块：

```text
core      领域模型、应用编排、Git/JSON/文件系统基础设施
desktop   Compose Desktop 展示、输入和窗口生命周期
```

代码按以下依赖方向组织：

```text
Desktop -> Application -> Domain
                ^
                |
         Infrastructure
```

- **Domain**：组、仓库、服务模块、任务、工作区、Tag 策略等稳定模型和规则。
- **Application**：通过用例服务编排配置、任务、刷新、工作区创建和 Agent 文档，不包含 Compose 控件。
- **Infrastructure**：实现 Git、JSON、原子文件写入、WatchService 和外部系统适配器。
- **Desktop**：`Main` 负责窗口、主题、导航和装配；`AppSessionStore`、`OperationCoordinator` 以及任务、设置、Agent、交付控制器维护展示状态和回调，不直接执行 Git 命令、解析 JSON 或拼接 AGENTS.md。

配置、任务、Git、工作区创建、Agent 文档和飞书集成都通过小接口及构造函数注入。标准 Worktree 与独立克隆是两个 `WorkspaceProvisioner` 策略，控制器只选择策略并展示结果。

## 启动和刷新边界

启动只读取本地 `config.json`、任务清单和 Agent 文件，并异步检查最近任务的本地 Git 状态；不递归发现仓库、不 Fetch、不访问飞书。顶部手动刷新会校验已配置仓库、重新读取任务与 Agent 文件、刷新飞书状态和当前任务 Git 状态。

## 持久化

- 全局配置：`~/AgentWorkspaceManager/config.json`
- 全局说明：`~/AgentWorkspaceManager/agents/global/AGENTS.md`
- 组说明：`~/AgentWorkspaceManager/agents/groups/<groupId>/AGENTS.md`
- 任务清单：`<taskDir>/agent-workspace.json`
- 最终说明：`<taskDir>/AGENTS.md`
- Tag 操作快照：`<taskDir>/tag-operations/*.json`
- 构建历史：`<taskDir>/tag-build-history.jsonl`
- 仓库锁：`~/AgentWorkspaceManager/locks/<git-common-dir-hash>.lock`

配置和任务使用严格 schema。文件写入采用同目录临时文件加原子替换，避免进程中断留下半个 JSON 或覆盖用户正在维护的 Agent 文档。

## Git 隔离和安全

- 人工选择的仓库按规范化 `git-common-dir` 标识和去重。
- 一个标准服务中，每个不同基础分支对应一个 Worktree；相同基础分支只创建一个 Worktree。
- 独立克隆从 `origin` 完整克隆到任务目录，并直接使用选定分支。
- Tag 构建按仓库公共目录获取 OS 文件锁，临时 Worktree 路径包含仓库 Hash 和 UUID。
- 测试分支和 Tag Push 均非强制；不自动 Pull/Rebase、不 Force Push、不自动解决冲突。

## 验证

核心测试使用真实临时 Bare Remote、Clone 和 Linked Worktree，覆盖：

- 有序组与服务数组、单组界面降级、空组删除约束；
- 人工仓库校验、`git-common-dir` 去重以及 Bare/Linked Worktree 拒绝；
- 启动零扫描与用户触发的手动刷新；
- 标准 Worktree、独立克隆、单模块兼容和多模块分支后缀；
- Tag 组级和子级开关、克隆分支 Tag；
- 三级 Agent 合成、人工区保留、外部同步和冲突处理；
- 归档、删除、合并预检及 UAT Tag 状态机。
- `{num}` 分支占位符、Meegle 标题保护以及本地未提交/未推送状态；
- 可注入交付流水线注册表和 UAT Tag 适配器。

提交前至少运行：

```powershell
.\gradlew.bat test
.\gradlew.bat :desktop:compileKotlin
.\gradlew.bat :desktop:run
.\scripts\build-windows.ps1
```

Windows 脚本会构建绿色目录/Zip、EXE 与 MSI。Release 工作流还会在 macOS 运行同一测试集并构建 DMG。
