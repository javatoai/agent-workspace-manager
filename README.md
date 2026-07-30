# Task Worktree Manager

面向多仓库研发任务的 Git Worktree 桌面管理器。输入任意任务编号、Feature 分支并选择服务后，
即可创建相互隔离的 IDEA / WebStorm 工作区；开发完成后，可预检并安全地合并测试分支、生成 UAT Tag。

## 主要能力

- 扫描一个或多个目录中的主 Git 仓库，支持 Windows 目录链接/符号链接，不支持手工登记单个仓库。
- 自动忽略构建目录、子模块、Linked Worktree、任务根目录及工具自己创建的临时 Worktree。
- 使用自由字符串 `taskKey`，自动生成 Windows 安全且稳定的任务目录名。
- 为一次需求创建多个服务的同名 Feature 分支和 Worktree。
- IDEA 聚合 Maven 工程、WebStorm 工程及唯一的 JetBrains 项目显示名。
- 一键打开 IDEA / WebStorm、终端、文件管理器，以及复制任务或服务绝对路径。
- 服务级 Bootstrap：显式文件/目录复制规则、顺序命令、超时、平台过滤和 CodeGraph 预设。
- 安全归档与恢复：检查暂存、未提交、未跟踪、未推送及进行中的 Git 操作。
- 单服务或批量 UAT 构建；批量模式下，一个服务失败不会中断其他服务。
- 在隔离临时 Worktree 中检测和执行合并，冲突不会污染 Feature 工作区。
- 先非强制推送测试分支，再创建带审计信息的 Annotated Tag 并推送。
- 持久化 Tag 状态机、构建历史、冲突清单和 `PARTIAL` 恢复信息。
- 浅色、深色及跟随系统主题。

## 首次启动

Windows 配置文件固定保存在：

```text
%USERPROFILE%\TaskWorktreeManager\config.json
```

例如：

```text
C:\Users\16776\TaskWorktreeManager\config.json
```

首次启动没有默认扫描目录和任务根目录，客户端会显示配置引导。服务扫描目录可在设置中继续添加；
任务根目录会被扫描器自动排除。

## 任务目录

```text
<taskRoot>/<taskDirectoryName>/
├── taskwt.json
├── tag-build-history.jsonl
├── tag-operations/
├── idea-<taskDirectoryName>/
│   ├── pom.xml
│   ├── .idea/.name
│   ├── job-manager/
│   └── data-center/
└── webstorm-<taskDirectoryName>/
    ├── .idea/.name
    └── operation-app/
```

JetBrains 显示名分别为：

```text
TaskWT - <taskKey> - IDEA
TaskWT - <taskKey> - WebStorm
```

建议在 IDEA / WebStorm 全局设置中将“打开项目”配置为“新窗口”，工具不会修改 JetBrains 全局设置。

## 本地开发

要求 JDK 21。

```powershell
.\gradlew.bat test
.\gradlew.bat :desktop:run
.\gradlew.bat :cli:run --args="--help"
```

Windows 完整构建：

```powershell
.\scripts\build-windows.ps1
```

macOS 完整构建：

```bash
./scripts/build-macos.sh
```

Compose Desktop 原生安装包不能跨平台生成：EXE/MSI 必须在 Windows 构建，DMG 必须在 macOS 构建。

## 产物

- Windows EXE：`desktop/build/compose/binaries/main/exe/`
- Windows MSI：`desktop/build/compose/binaries/main/msi/`
- macOS DMG：`desktop/build/compose/binaries/main/dmg/`
- CLI ZIP：`cli/build/distributions/`

## 文档

- [用户与配置说明](docs/CONFIGURATION.md)
- [安全与 UAT Tag 流程](docs/SAFETY-AND-TAG-FLOW.md)
- [架构与测试说明](docs/ARCHITECTURE.md)
