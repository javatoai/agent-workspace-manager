# 安全与 UAT Tag 流程

## 预检

每个服务构建前会：

1. 检查 Feature Worktree 没有暂存、未提交、未跟踪或进行中的 Git 操作。
2. `fetch --prune --tags` 配置的 Remote。
3. 比较本地和远端 Feature 分支；远端领先或已分叉时停止，不自动 pull/rebase。
4. 解析远端测试分支的精确 SHA。
5. 在 `TaskWorktreeManager/temp/tag-build` 下创建 Detached 临时 Worktree。
6. 执行不提交的合并检测，收集冲突文件并清理临时 Worktree。
7. 展示合并方式、提交列表、Diff Stat 和预计 Tag。

## 正式构建

确认预检结果后：

1. 必要时推送 Feature 分支并设置 Upstream。
2. 重新 Fetch，基于最新远端测试分支创建临时 Worktree。
3. Feature 已包含在测试分支时直接进入 Tag 阶段。
4. 可 Fast-forward 时允许 Fast-forward；分叉时创建普通 Merge Commit。
5. 使用普通、非强制 Push 更新测试分支。
6. 校验远端测试分支 SHA 与本次合并 SHA 完全一致。
7. 创建包含任务、服务、Feature/Test SHA、构建人和时间的 Annotated Tag。
8. 推送 Tag，并处理同名 Tag 竞态；最多重新计算并重试一次。
9. 输出可复制的 `服务：Tag` 清单。

工具明确不会执行：

- Force Push
- 自动 Pull / Rebase
- 自动解决冲突
- 修改用户正在开发的 Worktree
- 删除远端分支或远端 Tag

## Tag 规则

桌面端只展示同时通过两级开关的 Tag 入口：任务所属组的 `createTagEnabled` 为开，且对应标准模块的 `tagEnabled` 或独立克隆的 `cloneTagEnabled` 为开。独立克隆以任务中实际检出的分支参与 UAT，不会为 Tag 再创建 Feature 分支。

有效格式：

```text
X.Y.Z
X.Y.Z.N
X.Y.Z.beta-N
```

递增规则与现有 `auto-build-uat-tag.ps1` 兼容：

```text
1.6.89.beta-9 → 1.6.89.beta-10
1.6.89.7      → 1.6.90.beta-1
1.6.89        → 1.6.90.beta-1
```

多条 major/minor 版本线并存时，同样按照参考脚本的规则先结合 Tag 创建时间确定活跃版本线，
再在该版本线内按版本号选择最新 Tag，避免被仍保留在仓库中的旧版本线误导。

仓库没有有效 Tag 时，必须配置 `initialUatTag`。

## 冲突处理

发生冲突时，工具会显示冲突文件并清理临时 Worktree。用户应：

1. 手工将 Feature 分支合并到配置的测试分支。
2. 解决冲突并推送测试分支。
3. 回到任务页重新预检并构建。

重新构建会识别 Feature 已是测试分支祖先，不重复制造 Merge Commit，而是基于最新测试分支生成 Tag。

## 状态恢复

每次操作保存在：

```text
<task>/tag-operations/<operationId>.json
```

终态追加到：

```text
<task>/tag-build-history.jsonl
```

状态包括：

```text
CREATED
PREFLIGHT_PASSED
FEATURE_PUSHED
TEST_BRANCH_PUSHED
LOCAL_TAG_CREATED
TAG_PUSHED
SUCCESS
CONFLICT
FAILED
PARTIAL
```

测试分支已推送但 Tag 推送失败时标记为 `PARTIAL`。桌面端“继续”
会复用相同本地 Tag，不会错误地跳到下一个版本。

## 归档

安全归档会检查每个标准 Worktree 或独立克隆工作区：

- Staged
- Unstaged
- Untracked
- 当前分支未推送提交
- Merge / Rebase / Cherry-pick / Revert

只有全部安全时才移除工作区。标准 Worktree 通过 Git 安全移除，独立克隆则在路径与状态复核后删除；分支、任务清单和 Tag 历史会保留。
强制归档必须输入完整 `taskKey`；它可能丢弃未保存的本地文件，应谨慎使用。
