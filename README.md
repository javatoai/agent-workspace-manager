# Task Worktree Manager

多仓库研发任务的 **Git Worktree** 桌面管理器（含 CLI）。按任务编号与 Feature 分支批量创建各服务隔离工作区，一键打开 IDEA / WebStorm，开发完成后可安全合并测试分支并生成 UAT Tag。

当前版本：**0.1.4**

## 主要能力

- 扫描一个或多个目录中的主 Git 仓库（支持 Windows 目录链接 / 符号链接）
- 自动忽略构建目录、子模块、Linked Worktree、任务根目录及工具自建临时 Worktree
- 自由字符串 `taskKey`，自动生成 Windows 安全且稳定的任务目录名
- 一次需求可为多个服务创建同名 Feature 分支与 Worktree；事后可追加服务
- 生成 IDEA 聚合 Maven 工程、WebStorm 工程及唯一 JetBrains 项目显示名
- 一键打开 IDEA / WebStorm、终端、资源管理器；复制任务或服务绝对路径
- 服务级 Bootstrap：复制规则、顺序命令、超时、平台过滤与 CodeGraph 预设
- **安全归档 / 恢复**：检查暂存、未提交、未跟踪、未推送及进行中的 Git 操作
- **永久删除任务**：只删任务目录与 worktree，保留本地 / 远端 Feature 分支；有未提交改动时需勾选确认丢弃（仅未推送不阻断）
- 单服务或批量 UAT Tag；批量时单个失败不中断其他服务
- 在隔离临时 Worktree 中检测 / 执行合并，冲突不污染 Feature 工作区
- 浅色、深色及跟随系统主题

## 快速开始

### 绿色免安装包（推荐）

构建后产物：

```text
desktop/build/compose/binaries/main/app/Task Worktree Manager/
desktop/build/compose/binaries/main/zip/Task-Worktree-Manager-<version>-portable.zip
```

解压或进入目录后双击 `Task Worktree Manager.exe` 即可运行（自带 runtime，无需安装 JDK）。

### 从源码运行

要求 **JDK 21**。

```powershell
.\gradlew.bat :desktop:run
.\gradlew.bat :cli:run --args="--help"
.\gradlew.bat test
```

Windows 完整打包（测试 + CLI + 绿色包 + EXE/MSI）：

```powershell
.\scripts\build-windows.ps1
```

## 首次配置

配置文件固定保存在：

```text
%USERPROFILE%\TaskWorktreeManager\config.json
```

首次启动需选择：

1. **服务扫描目录**（可后续在设置中继续添加）
2. **任务根目录**（扫描器会自动排除，避免把任务 worktree 当成主仓）

## 任务目录结构

```text
<taskRoot>/<taskDirectoryName>/
├── taskwt.json
├── tag-build-history.jsonl
├── tag-operations/
├── idea-<taskDirectoryName>/
│   ├── pom.xml
│   ├── .idea/.name
│   └── <各 Java 服务 worktree>/
└── webstorm-<taskDirectoryName>/
    ├── .idea/.name
    └── <前端服务 worktree>/
```

JetBrains 项目显示名：

```text
TaskWT - <taskKey> - IDEA
TaskWT - <taskKey> - WebStorm
```

建议在 IDEA / WebStorm 中将「打开项目」设为「新窗口」。工具不会修改 JetBrains 全局设置。

## CLI 常用命令

```powershell
taskwt config init --scan-root <服务目录> --task-root <任务根目录>
taskwt task create --task-key "OBT-123 支付" --branch feature/OBT-123 --services job-manager,order-center
taskwt task list
taskwt task reveal --task-key "OBT-123 支付"
taskwt task delete --task-key "OBT-123 支付"            # 干净时可直接删
taskwt task delete --task-key "OBT-123 支付" --force-discard  # 丢弃未提交后删除
taskwt task archive "OBT-123 支付"
taskwt tag preflight --task-key "OBT-123 支付" --service <repositoryId>
```

## 归档 vs 删除

| 操作 | 任务目录 / manifest | Worktree | Feature 分支 | 脏工作区 |
|------|---------------------|----------|--------------|----------|
| 安全归档 | 保留（状态变 ARCHIVED） | 移除 | 保留 | 含未推送也会阻断，需强制确认 |
| 永久删除 | 整夹删除 | 移除 | 保留 | 仅未提交 / 操作中阻断；勾选丢弃后可删 |

## 构建产物

| 类型 | 路径 |
|------|------|
| 绿色免安装目录 | `desktop/build/compose/binaries/main/app/` |
| 绿色 Zip | `desktop/build/compose/binaries/main/zip/` |
| Windows EXE | `desktop/build/compose/binaries/main/exe/` |
| Windows MSI | `desktop/build/compose/binaries/main/msi/` |
| CLI ZIP | `cli/build/distributions/` |

Compose Desktop 原生安装包不能跨平台生成：EXE / MSI 须在 Windows 构建，DMG 须在 macOS 构建。

## 模块结构

```text
core/      业务核心（Git、任务、Bootstrap、Tag）
desktop/  Compose Desktop 界面
cli/      Picocli 命令行
docs/     详细文档
scripts/  打包脚本
```

## 文档

- [用户与配置说明](docs/CONFIGURATION.md)
- [安全与 UAT Tag 流程](docs/SAFETY-AND-TAG-FLOW.md)
- [架构与测试说明](docs/ARCHITECTURE.md)

## License

按仓库内声明使用；未单独声明时默认供个人与团队内部使用。
