# 用户与配置说明

## 配置目录

Windows：

```text
%USERPROFILE%\TaskWorktreeManager\
├── config.json
├── locks/
├── logs/
└── temp/tag-build/
```

macOS：

```text
~/TaskWorktreeManager/
```

`config.json` 使用原子替换写入。主配置不强制校验 `schemaVersion` 数值，缺少、旧版或未来版本号都可以读取；但字段集合仍严格校验，未知字段会直接报错。任务清单仍严格校验 `schemaVersion`，不执行迁移或兼容读取。

可选字段 `agentsMdAppendix`：写入设置页「AGENTS.md 模板追加」的多行文本；生成/刷新任务目录 `AGENTS.md` 时拼接到「自定义说明」章节。

## 服务扫描

扫描器遵循以下规则：

1. 从每个 `scanRoots` 递归向下扫描，并跟随 Windows 目录链接、Junction 和符号链接；检测到链接环时安全跳过。
2. 发现包含 `.git` 目录的主仓库后，不再扫描其子目录。
3. 发现 `.git` 指针文件时，视为 Linked Worktree 或子模块并跳过整棵子树。
4. 跳过 `.gradle`、`.idea`、`build`、`out`、`target`、`node_modules`、`.next` 和 `dist`。
5. 跳过整个 `taskRoot`。
6. 使用规范化 `git-common-dir` 去重。

客户端和 CLI 都只允许添加扫描目录，不提供“添加单个 Git 仓库”入口。

## 服务配置

每个扫描到的服务具有独立配置：

| 字段 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 是否允许新任务选择 |
| `ideType` | 自动判断 | `IDEA` 或 `WEBSTORM` |
| `defaultBaseRef` | `origin/master` | 创建 Feature 分支的基础 Ref |
| `uatRemote` | `origin` | UAT 推送 Remote |
| `uatBranch` | `release/test` | 测试环境分支 |
| `initialUatTag` | 空 | 仓库无有效历史 Tag 时必须配置 |
| `tagMessagePrefix` | `UAT` | Annotated Tag 消息前缀 |
| `bootstrap` | 空 | 文件复制规则和初始化命令 |

## Bootstrap

复制规则只接受明确相对路径，不支持 Glob；禁止绝对路径、`..`、`.git` 和符号链接。
目录复制为递归合并，不会删除目标中的额外文件。默认允许覆盖，并对覆盖已跟踪路径给出警告。

初始化命令按声明顺序执行。某一步失败后仍继续后续步骤，最终状态为 `READY_WITH_WARNINGS`。

示例：

```json
{
  "copyRules": [
    {
      "source": ".env.example",
      "target": ".env",
      "overwrite": true
    }
  ],
  "commands": [
    {
      "name": "初始化 CodeGraph 索引",
      "executable": "codegraph",
      "arguments": ["init", "-i"],
      "workingDirectory": ".",
      "timeoutSeconds": 600,
      "platforms": [],
      "enabled": true
    }
  ]
}
```

桌面端可直接编辑 Bootstrap JSON，也提供 CodeGraph 预设。CLI 示例：

```powershell
taskwt service bootstrap show <repositoryId>
taskwt service bootstrap set <repositoryId> --preset codegraph
taskwt service bootstrap set <repositoryId> --config .\bootstrap.json
```

## CLI 快速参考

```text
taskwt config init --scan-root <dir> --task-root <dir>
taskwt source add|remove|list|scan
taskwt service list|set|enable|disable
taskwt service bootstrap show|set
taskwt task create --folder-name <text> --requirement-link <url-or-text> --branch <branch> --services <id,id>
taskwt task list
taskwt task open <folderName> --ide all|idea|webstorm
taskwt task open-service --folder-name <folderName> --service <id-or-name>
taskwt task path <folderName> --copy
taskwt task terminal|reveal <folderName>
taskwt task initialize <folderName> [--failed-only]
taskwt task archive <folderName> [--force-confirm <folderName>]
taskwt task restore <folderName> [--skip-bootstrap]
taskwt task delete --folder-name <folderName> [--force-discard]
taskwt tag preflight --folder-name <folderName> --service <id>
taskwt tag build --folder-name <folderName> --services <id,id>
taskwt tag retry --folder-name <folderName> --operation <operationId>
taskwt tag history --folder-name <folderName>
```

批量命令返回值：

- `0`：全部成功
- `1`：命令或参数错误
- `4`：部分成功、冲突或需要人工处理
