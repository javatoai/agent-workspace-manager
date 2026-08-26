# 版本升级规则

AWM 使用 `MAJOR.MINOR.PATCH` 版本号，产品版本、Gradle、安装包、Git Tag、CHANGELOG 与新写入的 `config.json`、`agent-workspace.json` 的字符串 `schemaVersion` 必须一致。

- 不影响 `config.json` 或 `agent-workspace.json` 持久化字段的更新，PATCH +1，例如 `0.4.0` 到 `0.4.1`。
- 新增、删除、重命名、改变类型或语义的上述字段，MINOR +1 且 PATCH 归零，例如 `0.4.1` 到 `0.5.0`。
- 大改版由产品负责人判断，MAJOR +1 且其余归零，例如 `0.5.3` 到 `1.0.0`。

`AGENTS.md`、日志、Tag 历史和构建产物不属于 schema 字段。相同 `MAJOR.MINOR` 的不同 PATCH（如 `0.5.0` 与 `0.5.1`）兼容读取，下一次正常保存时会写为当前 PATCH；不同主版本或次版本始终严格拒绝，不自动迁移。

`0.11.0` 是新的 schema 硬边界：0.10.x 及更早版本的 `config.json` 和 `agent-workspace.json` 不兼容，应用不会读取、迁移或改写这些文件。新增 Agent CLI 交接上下文与需求过程文档根目录，因此配置与任务 schema 同步升至 `0.11.0`。
