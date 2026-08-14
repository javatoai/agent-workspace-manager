# Agent Workspace Manager

Agent Workspace Manager（AWM）是一个桌面工具，用来把“一项需求涉及多个代码仓库”的本地开发工作整理成一个清晰、安全的任务工作区。

它适合需要同时修改多个服务、希望使用 Git Worktree 隔离任务、并且会配合 Codex、Cursor 等 Agent 工具开发的人或团队。创建任务后，AWM 会按你选择的服务建立独立工作区、生成任务说明、展示 Git 改动与推送状态，并在需要时构建 Tag。

当前版本：**0.8.0**

## 适合什么场景

- 一个需求往往涉及两个或更多 Git 仓库，例如前端、后端、网关、公共服务需要一起修改。
- 同时进行多个需求，不希望切换分支、未提交改动或本地配置互相干扰。
- 希望每个任务都有独立目录、独立 `AGENTS.md` 说明，并能从任务目录直接打开 IDEA、WebStorm、Codex 或 Cursor。
- 团队按业务线把服务分组管理；不同组可以使用不同服务、分支前缀、Agent 说明和 Tag 配置。
- 需要在本地提交前快速确认：哪些文件还没有提交、哪些提交尚未推送、是否可以安全归档或删除任务。

## 它能做什么

- **按任务创建隔离工作区**：标准服务使用 Git Worktree；也可将服务配置为独立克隆。
- **按组管理服务**：为每组维护服务清单、服务排序、分支前缀、Agent 说明、默认打开的工具和 Tag 开关；只有一个组时界面保持简洁。
- **一次覆盖多个服务**：一个任务可选择多个服务；标准 Worktree 多模块始终按模块建立独立工作区和目标分支。
- **管理任务说明**：合成全局、组、任务三级 `AGENTS.md`，任务人工说明独立保存；外部编辑文件后可自动同步并处理冲突。
- **打开日常工具**：从任务或工作区直接打开 IDEA、WebStorm、终端、文件夹、Codex 或 Cursor。
- **查看 Git 健康状态**：显示未提交文件数（含未跟踪文件、不含忽略文件）以及本地已知上游下的未推送提交数。
- **辅助填写飞书需求**：可选用本地 Meegle CLI 获取需求标题；组分支前缀支持从需求链接提取 `{num}`。
- **构建与追溯 Tag**：按组和服务配置创建 Tag，保留可复制的构建历史。
- **保护已有工作**：归档、删除和 Tag 操作执行 Git 安全检查；不会 Force Push、自动 Rebase 或自动解决冲突。

## 三分钟上手

1. 在“设置”中选择任务工作区根目录，并添加本地 Git 仓库到一个组。
2. 为服务选择“标准 Worktree”或“独立克隆”，按需配置基础分支、Tag 和开发工具。
3. 点击“创建任务”，填写或选择需求链接、任务名称和分支，再选择需要修改的服务。
4. 在任务详情中打开工作区开始开发；完成后检查未提交/未推送状态，按需构建 Tag、归档或删除任务。

完整的界面操作与配置字段说明见[日常使用指南](docs/USER-GUIDE.md)和[配置与使用](docs/CONFIGURATION.md)。

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

0.8.0 只提供桌面应用，不再包含命令行模块或命令行发布包。

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

0.8.x 使用字符串 schema。相同主次版本的 PATCH 可兼容读取；主版本或次版本不同则拒绝读取，且不会迁移或删除旧数据。0.7.x 数据不会读取或迁移。

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

推送到 `master`、推送 `v*` 标签，或手动运行 **Release packages** 工作流后，会分别构建 Windows 桌面三件套与 macOS DMG，再上传到 GitHub Release。`master` 使用可覆盖的 `continuous` 预发布，`v*` 标签生成正式 Release。

## 模块

```text
core/      Domain、Application 与 Infrastructure 实现
desktop/   Compose Desktop 界面与原生安装包
docs/      配置、架构和安全流程文档
scripts/   桌面打包脚本
```

## 文档

- [配置与使用](docs/CONFIGURATION.md)
- [日常使用指南](docs/USER-GUIDE.md)
- [架构与测试](docs/ARCHITECTURE.md)
- [安全与 Tag 流程](docs/SAFETY-AND-TAG-FLOW.md)
- [本地开发指南](docs/DEVELOPMENT.md)
- [发布指南](docs/RELEASE.md)
- [版本升级规则](docs/VERSIONING.md)
- [版本变更记录](docs/CHANGELOG.md)
- [产品定位与验证](docs/PRODUCT-FIRST-PRINCIPLES-REVIEW.md)
- [Worktrunk 功能适配调研](docs/WORKTRUNK-FEATURE-ADAPTATION.md)

## License

按仓库内声明使用；未单独声明时默认供个人与团队内部使用。
