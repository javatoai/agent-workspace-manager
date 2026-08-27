# 生产 Tag 构建功能交接

## 当前分支与远端

- 工作树：`Q:\workspace_idea\agent-workspace-manager-production-tag-build`
- 分支：`codex/production-tag-build`
- 当前提交：`803c62b752adb200d4ce290209b6565d0104096d`（`fix: preserve queued build audit on interruption`）
- 上游：`origin/codex/production-tag-build`
- PR：<https://github.com/javatoai/agent-workspace-manager/pull/new/codex/production-tag-build>
- 基线：`origin/master@807196ec35b10ee4937acd54bce5199fd1106ee7`

本分支已推送；继续开发、审查或创建 PR 时都必须基于该分支，不要误在原始脏工作树 `Q:\workspace_idea\agent-workspace-manager` 中操作。

## 已完成内容

生产 Tag 流程已实现并提交为 8 个连续提交（`fb7202d` 至 `803c62b`）：

- 全局开关、设置页与位于“设置”上方的生产 Tag 导航；
- 通过 Genbu 查询生产运行版本，比较生产 Tag SHA 与 `master` SHA；
- 生产 Tag 合入 `master`、创建 `release/<版本>`、多 Feature 顺序预检/合并、冲突终止；
- 无合并权限时生成 GitLab/GitHub MR/PR 链接，支持刷新；
- 轻量正式 Tag：`major.minor.patch`，后续为 `.1`、`.2`；不构建测试包；
- 持久化构建记录和完整审计，覆盖权限失败、远端写后崩溃恢复、陈旧页面确认及并发点击；
- Build 点击通过 OS 文件锁排队；被中断的排队点击也会在活动写入完成后保留失败记录与审计，不会重复推送 Tag。

完整需求口径见：

- `F:\obsidian-docs\knowledge-vault\awm开发笔记\05-多 Feature 并行发版计划.md`
- `docs/SAFETY-AND-TAG-FLOW.md`

## 验证证据

- SOL 审查循环共 8 轮，最终结论：无 P1/P2，可交付。
- `./gradlew.bat test :desktop:compileKotlin --rerun-tasks --no-daemon`：成功（完整测试及桌面端编译）。
- SOL 独立完整回归：413 tests，0 failures，0 errors，1 skipped。
- `git diff --check origin/master...HEAD`：通过。

真实 Genbu、受保护 Git、MR/PR 平台与远端 Tag 写入未在本分支验证；不要把测试替代真实生产操作验证。

## 当前外部环境状态与待办

1. **Genbu 服务名兼容仍未解决。** AWM 按既定口径将仓库名 `android-transit-service` 传给 Genbu；当前 Genbu 返回“未在产线 ANDCN 中找到服务 android-transit-service”。同一环境中 `fp-android-transit-service` 查询成功并返回生产版本 `3.11.70`。此前决定是由 Genbu 内部完成该兼容，故 AWM 尚未加入前缀映射。若决定改由 AWM 临时处理，先取得用户明确授权并补测试。
2. **本机代理修复不在仓库中。** 快捷方式 `T:\Desktop\start-codex-with-clash.cmd - 快捷方式.lnk` 引用的 `D:\codex-start-with-tun\proxy-bypass-domains.json` 已加入精确条目 `genbu.snowballtech.com`；其原文件备份为 `proxy-bypass-domains.before-genbu-exact-host.json`。重启后的 AWM 本地进程已使用更新的 `NO_PROXY`。此项只影响当前机器，禁止提交进本仓库。
3. **GitLab SSH。** 曾出现一次 `git fetch` 连接 22 端口超时；后续 `git ls-remote --heads origin refs/heads/master` 对 `android-transit-service` 已成功。再次出现时先验证网络/SSH，禁止修改业务仓库 remote URL 或 Force Push。
4. **本机配置兼容。** `C:\Users\Administrator\.AgentWorkspaceManager\config.json` 中不兼容字段 `requirementDocumentationRoot` 已移除，并有同目录备份。该本地文件不是仓库变更。

## 推荐继续步骤

1. 等待或验证 Genbu 对仓库名的兼容更新；然后在 AWM 中创建生产 Tag 流水线，确认生产版本、master SHA 和 Release 预期 Tag。
2. 使用一个可控测试服务进行真实受保护分支/MR 与 Tag 推送验证；严禁在未确认的生产服务上试写。
3. 在 GitHub 上为 `codex/production-tag-build` 创建 PR，并以 `origin/master` 为目标分支。

## 建议技能

- `implement`：仅在口径确认后修改 AWM 代码；应保持当前分支与独立 worktree。
- `tdd`：为 Genbu 服务名兼容、代理环境或 Git 故障分类新增行为时先补回归测试。
- `T:\Downloads\SKILL.md`（Genbu CLI）：查询生产 Pod、配置或 Tag 时必须使用本地 `genbu` CLI，禁止直调 Genbu HTTP API 或输出凭据。
- `handoff`：后续会话结束前更新此文件；用户指定 `plan` 目录优先于技能的临时目录默认位置。
