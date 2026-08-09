# 版本变更记录

本项目采用 `vX.Y.Z` 标签发布桌面安装包。重大行为变化会在对应版本说明中记录；配置与任务数据 schema 的兼容边界以 [配置与使用](CONFIGURATION.md) 为准。

## 0.3.0

- 产品更名为 Agent Workspace Manager（AWM），定位为任务级 Agent 开发工作区编排器；
- 移除 CLI，仅保留 `core` 与 `desktop`；
- 引入组、服务、模块、标准 Worktree 与独立克隆策略；
- 增加三级 `AGENTS.md` 合成、磁盘同步和冲突保护；
- 增加任务 Git 健康状态：未提交文件、未推送提交和工作区风险；
- 支持飞书 Meegle 需求链接、`{num}` 分支前缀和需求标题自动填写；
- 将 UAT Tag 封装为交付流水线适配器，并保留安全预检和历史；
- 支持通过适配器打开 Codex 和 Cursor 工作区；
- 配置 schema 升级为 v7，任务 schema 升级为 v5；旧 TaskWT 数据不会读取、迁移或删除。

## 0.2.0

- 引入组、服务工作区与任务级 Agent 说明的基础能力；
- 发布 Windows portable ZIP、EXE、MSI 和 macOS DMG 流程。

## 0.1.4

- Task Worktree Manager 的最后一个公开早期版本。
