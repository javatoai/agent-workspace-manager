# 架构与测试说明

## 模块

```text
core     Git、扫描、配置、任务、Bootstrap、归档、Tag 状态机
cli      taskwt 命令行客户端
desktop  Compose Desktop 图形客户端与原生安装包
```

`core` 不依赖 Compose，CLI 与桌面端调用同一套业务逻辑，避免两套实现行为不一致。

## 关键持久化

- 全局配置：`TaskWorktreeManager/config.json`
- 任务清单：`taskwt.json`
- Tag 操作快照：`tag-operations/*.json`
- 构建历史：`tag-build-history.jsonl`
- 仓库锁：`TaskWorktreeManager/locks/<git-common-dir-hash>.lock`

配置、任务清单和操作快照使用同目录临时文件及原子替换，避免进程中断产生半个 JSON 文件。

## 并发与隔离

- 扫描结果按规范化 `git-common-dir` 去重。
- Tag 构建按仓库公共目录获取 OS 文件锁。
- 临时 Worktree 路径包含仓库 Hash 和 UUID。
- 测试分支与 Tag Push 均非强制，并在远端更新时重新 Fetch/重试一次。
- 批量 UAT 在服务维度隔离失败。

## 自动化测试

测试不仅覆盖纯函数，还会创建真实临时 Bare Remote、Clone 和 Linked Worktree，验证：

- 首启空配置与配置往返
- `taskKey` Windows 安全目录名
- 仓库扫描、任务根目录排除和 Linked Worktree 排除
- Bootstrap 复制、命令失败继续及路径穿越阻断
- 多仓库任务创建、服务独立 Worktree、归档与恢复
- 脏 Worktree 阻止归档
- UAT Feature/Test/Annotated Tag 真实推送
- 合并冲突文件检测及 Feature Worktree 不受污染
- Tag 版本递增规则

运行：

```powershell
.\gradlew.bat clean test
```

## 原生安装包

Compose Desktop 使用 `jpackage`：

- Windows：EXE、MSI、每用户安装、开始菜单和快捷方式
- macOS：DMG、Bundle ID `com.snowball.taskwt`

正式对外分发前建议使用企业代码签名证书签署 EXE/MSI，并使用 Apple Developer ID
签署和公证 DMG。当前构建脚本不包含任何私钥或证书。
