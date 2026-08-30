# Agent Workspace Manager

Agent Workspace Manager（AWM）是一个桌面工具，用来把“一项需求涉及多个代码仓库”的本地开发工作整理成一个清晰、安全的任务工作区。

它适合需要同时修改多个服务、希望使用 Git Worktree 隔离任务、并且会配合 Codex、Cursor 等 Agent 工具开发的人或团队。创建任务后，AWM 会按你选择的服务建立独立工作区、生成任务说明、展示 Git 改动与推送状态，并在需要时构建 Tag。

当前版本：**1.0.2**

## 适合什么场景

- 一个需求往往涉及两个或更多 Git 仓库，例如前端、后端、网关、公共服务需要一起修改。
- 同时进行多个需求，不希望切换分支、未提交改动或本地配置互相干扰。
- 希望每个任务都有独立目录、独立 `AGENTS.md` 说明，并能从任务目录直接打开 IDEA、WebStorm、Codex 或 Cursor。
- 团队按业务线把服务分组管理；不同组可以使用不同服务、分支前缀、协作说明和 Tag 配置。
- 需要在本地提交前快速确认：哪些文件还没有提交、哪些提交尚未推送、是否可以安全归档或删除任务。

## 它能做什么

- **按任务创建隔离工作区**：标准服务使用 Git Worktree；也可将服务配置为独立克隆。
- **按组管理服务**：为每组维护服务清单、服务排序、分支前缀、协作说明、默认打开的工具和 Tag 开关；只有一个组时界面保持简洁。
- **一次覆盖多个服务**：一个任务可选择多个服务；标准 Worktree 多模块始终按模块建立独立工作区和目标分支。
- **管理任务说明**：合成全局、组、任务三级 `AGENTS.md`，任务人工说明独立保存；外部编辑文件后可自动同步并处理冲突。
- **打开日常工具**：从任务或工作区直接打开 IDEA、WebStorm、终端、文件夹、Codex 或 Cursor。
- **查看 Git 健康状态**：显示未提交文件数（含未跟踪文件、不含忽略文件）以及本地已知上游下的未推送提交数。
- **辅助填写飞书需求**：可选用本地 Meegle CLI 获取需求标题；组分支前缀支持从需求链接提取 `{num}`。
- **构建与追溯 Tag**：按组和服务配置创建 Tag，保留可复制的构建历史。
- **保护已有工作**：归档、删除和 Tag 操作执行 Git 安全检查；不会 Force Push、自动 Rebase 或自动解决冲突。

## 三分钟上手

1. 首次启动会自动使用用户目录下的 `awm/tasks` 作为任务根目录；在“设置”中添加本地 Git 仓库到一个组。
2. 为服务选择“标准 Worktree”或“独立克隆”，按需配置基础分支、Tag 和开发工具；尚未填写的常见开发工具路径会在启动后由本机静默探测补齐。
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

## AWM CLI 与 Codex 插件

桌面绿色包内置了只面向 Agent 工作流的 `awm` CLI。它只支持 `awm agent` 下的 JSON 协议命令，不提供任意 Shell 或 Git 操作入口。

### 安装 CLI

Windows 绿色包启动后，打开 **设置 → AWM CLI → 安装 CLI**。应用会把 CLI 与专用 Java 运行时复制到当前用户的 `LOCALAPPDATA`，并将命令目录加入用户 `PATH`；无需管理员权限或系统 JDK。安装后重开终端；如果终端由 Codex、IDE 或 Windows Terminal 打开，请重启对应应用，再运行：

```powershell
awm --help
```

macOS/Linux 绿色包同样内置 `resources/cli/bin/awm` 与相邻的 `resources/cli-runtime`。将 `resources/cli/bin` 加入当前用户的 `PATH` 后即可使用；启动脚本会优先使用随包运行时。

### 安装 Codex 插件

先确认 `awm --help` 在 Codex 新开的终端中可用，再运行以下两条命令安装与当前发布版本绑定的插件：

```powershell
codex plugin marketplace add https://github.com/javatoai/agent-workspace-manager.git --ref v1.0.2
codex plugin add awm-codex@agent-workspace-manager
```

用 `codex plugin list` 确认 `awm-codex@agent-workspace-manager` 为 `installed, enabled`，然后新开一个 Codex 任务并显式输入 `$awm`。插件会让主 Agent 负责澄清和展示计划，并只把受限 JSON CLI 调用委派给 `awm-executor` 子代理；不会自行调用 CLI，也不会代替人工确认创建任务。

## 数据位置

```text
~/awm/
├── config.json
├── tasks/
├── agents/
│   ├── global/AGENTS.md
│   ├── groups/<groupId>/AGENTS.md
│   └── task-templates.json
├── locks/
└── temp/tag-build/
```

每个任务目录包含严格版本的 `agent-workspace.json`、最终合成的 `AGENTS.md`、Tag 构建历史以及服务 Worktree 或独立克隆。

Windows 的 `~` 为 `%USERPROFILE%`，macOS 的 `~` 为 `$HOME`。首次启动会创建 `~/awm`、`~/awm/tasks` 和包含默认 `taskRoot` 的当前版本配置。AWM 不探测或读取旧的 `~/.AgentWorkspaceManager`；如需保留旧数据，应在停止 AWM 后手动迁移并验证。

在设置页更改任务根目录时，空目录可直接切换；已有任务时会先显示迁移预览。确认后，同磁盘整体移动、跨磁盘完整复制，标准 Worktree 会修复 Git 注册；全部任务及 Git 状态校验成功后才更新 `taskRoot`。

`1.0.x` 使用字符串 schema，当前写入版本为 `1.0.2`。相同主次版本的 PATCH 可兼容读取；主版本或次版本不同则拒绝读取，且不会迁移或改写旧数据。`0.12.x` 及更早数据不会读取、迁移或删除。升级时需先备份数据，手工移除旧配置中的 `requirementDocumentationRoot`，将 schema 改为 `1.0.2`，并在设置页重新保存需求资料根目录与资料子目录。

需求资料统一使用设置页填写的 `requirementMaterialsRoot` 和 `requirementMaterialsSubdirectory`：`<资料根>/<Sprint>/<需求编号>-<任务文件夹名>/<资料子目录>`。桌面端创建或复用资料目录；`awm agent` 在同一目录的 `write_root` 内补写过程文档，Sprint 总览保留在资料根的 Sprint 层。旧的独立过程文档目录不会自动搬迁。

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
- [已知问题与支持边界](docs/KNOWN-ISSUES.md)
- [发布指南](docs/RELEASE.md)
- [版本升级规则](docs/VERSIONING.md)
- [版本变更记录](docs/CHANGELOG.md)
- [产品定位与验证](docs/PRODUCT-FIRST-PRINCIPLES-REVIEW.md)
- [Worktrunk 功能适配调研](docs/WORKTRUNK-FEATURE-ADAPTATION.md)

## License

按仓库内声明使用；未单独声明时默认供个人与团队内部使用。
