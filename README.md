# Agent Workspace Manager

Agent Workspace Manager（AWM）是“任务级 Agent 开发工作区编排器”。它把每个任务的隔离代码工作区、分支、Agent 指令、外部 Agent 工具与交付动作集中管理，并用 Git 安全检查保护用户已有工作。

当前版本：**0.3.0**

## 0.3.0 主要能力

- 启动直接进入研发任务，不执行 Fetch 或访问远端；只异步读取当前任务的本地 Git 状态。
- 配置有序的组，每个组维护独立的服务列表、默认分支前缀与任务工作区工具；只有一个默认组时隐藏组相关界面。
- 可通过操作系统原生目录选择器批量添加 Git 仓库。工具逐项解析顶层仓库并按 `git-common-dir` 去重，拒绝非 Git、Bare 仓库和临时 Linked Worktree。
- 同一物理仓库可加入多个组，但在同一组内只能出现一次。
- 标准服务可按多个基础分支创建 Worktree；相同基础分支共用一个 Worktree，多模块分支自动增加稳定后缀。
- 独立克隆服务从 `origin` 完整克隆并切到指定远程分支，不额外创建 Feature 分支、Worktree 或执行 Bootstrap。
- 组级总开关与模块/克隆子开关共同控制 UAT Tag；独立克隆使用实际克隆分支参与构建。
- 全局、组、任务三级 `AGENTS.md` 合成；任务人工区使用标记保护，外部文件变化可自动同步并检测编辑冲突。
- 任务完成后可通过通用适配器打开 Codex 或 Cursor；未来新增 Claude 等工具无需修改任务创建主流程。
- 工作区行实时展示未提交文件数和基于本地已知上游的未推送提交数。
- 组级分支前缀支持 `{num}`，可从飞书链接或普通文本中提取最后一段数字；飞书标题通过本地 Meegle CLI 非阻断填充。
- AWM 自己生成的时间统一使用上海时区的 `yyyy-MM-dd HH:mm:ss` 格式。
- 安全归档与删除会检查未提交内容和进行中的 Git 操作，不执行 Force Push、自动 Rebase 或自动解决冲突。
- 浅色、深色及跟随系统主题。

## 快速开始

要求 **JDK 21**。

```powershell
.\gradlew.bat test
.\gradlew.bat :desktop:compileKotlin
.\gradlew.bat :desktop:run
```

Windows 完整构建（测试、绿色包、EXE、MSI）：

```powershell
.\scripts\build-windows.ps1
```

macOS 构建 DMG：

```bash
./scripts/build-macos.sh
```

0.3.0 只提供桌面应用，不再包含命令行模块或命令行发布包。

## 数据位置

```text
~/.AgentWorkspaceManager/
├── config.json
├── agents/
│   ├── global/AGENTS.md
│   └── groups/<groupId>/AGENTS.md
├── locks/
└── temp/tag-build/
```

每个任务目录包含严格版本的 `agent-workspace.json`、最终合成的 `AGENTS.md`、Tag 构建历史以及服务 Worktree 或独立克隆。

0.3.0 使用全新的严格 schema：配置 v7、任务 v5。AWM 不读取、不迁移也不删除旧 TaskWT 数据。

## 构建产物

| 类型 | 路径 |
|---|---|
| 绿色目录 | `desktop/build/compose/binaries/main/app/Agent Workspace Manager/` |
| 绿色 Zip | `desktop/build/compose/binaries/main/zip/` |
| Windows EXE | `desktop/build/compose/binaries/main/exe/` |
| Windows MSI | `desktop/build/compose/binaries/main/msi/` |
| macOS DMG | `desktop/build/compose/binaries/main/dmg/` |

Compose Desktop 原生安装包不能跨平台生成：EXE/MSI 必须在 Windows 构建，DMG 必须在 macOS 构建。

## CI / GitHub Release

推送到 `main`、推送 `v*` 标签，或手动运行 **Release packages** 工作流后，会分别构建 Windows 桌面三件套与 macOS DMG，再上传到 GitHub Release。`main` 使用可覆盖的 `continuous` 预发布，`v*` 标签生成正式 Release。

## 模块

```text
core/      Domain、Application 与 Infrastructure 实现
desktop/   Compose Desktop 界面与原生安装包
docs/      配置、架构和安全流程文档
scripts/   桌面打包脚本
```

## 文档

- [配置与使用](docs/CONFIGURATION.md)
- [架构与测试](docs/ARCHITECTURE.md)
- [安全与 UAT Tag 流程](docs/SAFETY-AND-TAG-FLOW.md)

## License

按仓库内声明使用；未单独声明时默认供个人与团队内部使用。
