# Meegle 多空间获取“我名下需求链接”规格

## 目标

调用方提供多个空间配置，程序逐个空间查询进行中的 Sprint，找出所有角色/参与人员包含当前登录用户的 User Story、Tech Improvement、Bug 和 Task，最终只输出唯一的工作项链接。

## 输入

```json
[
  {
    "project_key": "67c17e40bf0d47db9549cb08",
    "simple_name": "obt"
  },
  {
    "project_key": "680b2de0f1ddf8d50a24dff8",
    "simple_name": "rta"
  }
]
```

`project_key` 和 `simple_name` 都由调用方提供。实现不得调用 `project search` 获取或扩展空间列表，也不得再次查询 `simple_name`。

输入处理：校验两个字段非空，保持顺序，并按 `project_key` 去重。

## 工作项类型和链接路径

| CLI 工作项类型 | URL 路径 |
| --- | --- |
| `User Story` | `userstory` |
| `Tech Improvement` | `technical` |
| `Bug` | `bug` |
| `Task` | `othertask` |

链接格式：

```text
https://project.feishu.cn/<simple_name>/<url_type>/detail/<item_id>
```

## 查询流程

### 1. 查询进行中的 Sprint

对每个配置项执行：

```powershell
meegle workitem query `
  --project-key <project_key> `
  --mql 'SELECT `Item Id` FROM `<project_key>`.`Sprint` WHERE `Status` = ''进行中'' LIMIT 100' `
  --auto-paginate `
  --format json
```

从结果中只读取 `Item Id` 作为 `sprint_id`。不要假设一个空间只有一个进行中的 Sprint，所有返回的 Sprint 都要继续处理。

### 2. 查询每个 Sprint 下的四类工作项

对每个 `sprint_id` 和四种工作项类型执行：

```powershell
meegle workitem query `
  --project-key <project_key> `
  --mql 'SELECT `Item Id` FROM `<project_key>`.`<work_item_type>` WHERE array_contains(`Sprint`, ''<id:SPRINT_ID>'') AND array_contains(all_participate_persons(), current_login_user()) LIMIT 100' `
  --auto-paginate `
  --format json
```

必须使用：

```sql
array_contains(all_participate_persons(), current_login_user())
```

这表示所有角色/参与人员中包含当前登录用户。不要使用：

- `Operators`：只表示当前流程节点处理人；
- `participate_persons()`：本地验证结果过宽；
- 单个角色字段，例如 `__服务端工程师` 或 `__Dev Owner`；
- `owner`/`Creator`：只能表示创建人。

### 3. 构造并去重链接

每条命中记录只需要 `Item Id`、工作项类型和输入配置中的 `simple_name`。按类型映射 URL 路径，构造完整链接，并按完整 URL 去重。

## 实现伪代码

```text
types = {
  "User Story": "userstory",
  "Tech Improvement": "technical",
  "Bug": "bug",
  "Task": "othertask"
}

links = ordered_set()

for project in normalizeAndDedupe(inputProjects, key = "project_key"):
    projectKey = project.project_key
    simpleName = project.simple_name
    sprints = query(projectKey, "Sprint", status = "进行中")

    for sprint in sprints:
        for (workItemType, urlType) in types:
            items = query(
                projectKey,
                workItemType,
                sprintId = sprint.itemId,
                condition = "array_contains(all_participate_persons(), current_login_user())"
            )

            for item in items:
                links.add(
                    "https://project.feishu.cn/" + simpleName + "/" +
                    urlType + "/detail/" + item.itemId
                )

return links
```

## 错误处理

- 单个空间查询失败：记录错误，继续下一个空间；
- 单个工作项类型查询失败：记录错误，继续其他类型；
- 没有进行中的 Sprint：该空间无结果；
- 没有命中工作项：该 Sprint 无结果；
- `project_key` 或 `simple_name` 缺失：该空间不能构造链接，不能猜 URL；
- CLI 未认证、命令不存在或全局参数错误：作为全局错误返回；
- 所有查询必须开启 `--auto-paginate`，避免只取第一页。

## PowerShell 引号

MQL 使用 PowerShell 单引号字符串；SQL 内部的单引号写成两个单引号，MQL 反引号必须保留：

```powershell
$mql = 'SELECT `Item Id` FROM `' + $projectKey + '`.`Sprint` WHERE `Status` = ''进行中'' LIMIT 100'
```

## 验收样例

当前账号、OBT 空间、Sprint `7052641502` 的已验证结果：

- User Story：7 条；
- Bug：2 条，包含 `https://project.feishu.cn/obt/bug/detail/7056364288`；
- Task：1 条，包含 `https://project.feishu.cn/obt/othertask/detail/7055846637`；
- Tech Improvement：当前 0 条。

验收重点：四种类型都会扫描；多个空间不会漏查；同一工作项关联多个 Sprint 时不会产生重复链接。
