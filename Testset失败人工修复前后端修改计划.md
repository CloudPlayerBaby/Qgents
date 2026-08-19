# Testset 失败人工修复前后端修改计划

## 1. 目标与边界

本功能用于处理 `MR_FIRST` 任务在 MR 创建前 Dry Run 中因目标分支 Testset 未通过而暂停的场景。

目标流程：

```text
Task = WAITING_PREFLIGHT
  -> Dry Run 执行目标分支绑定的 Testset
  -> Testset FAILED
  -> 回群/任务详情展示失败原因
  -> 用户明确点击“让 Agent 修复”
  -> 服务端创建续跑 Task（复用原 Workspace）
  -> Developer 修改业务代码
  -> Tester 重新执行 Testset
  -> Reviewer/CQ+1 重新完成门禁
  -> Dry Run PASSED 后创建 MR
```

本次不做：

- 不把所有 Testset 失败自动派给 Agent；
- 不允许 Agent 默认修改 Testset 定义、门禁配置或断言；
- 不绕过 Dry Run、独立成员 CQ+1 或 Project Admin 的 MR 合并权限；
- 不新增独立的 Diff/MR 模型，不把续跑 TaskStep 当成用户交付物；
- 不改变 `DIFF_FIRST` 的确认、应用 Patch 和交付流程。

当前分支已迁移的冲突自动续跑逻辑只处理 `MERGE_CONFLICT` / `GIT_MERGE_CONFLICT`，本计划在其基础上增加 Testset 失败的人工触发续跑。

## 2. 权威约束

优先级为：题目文档 > 已确认接口文档 > 当前实现。

- 题目要求云端 Agent 完成开发、测试并返回 Diff/MR，但没有要求 Testset 失败后无人确认自动改代码。
- 题目要求 Agent 分工，不能由单一 Agent 梭哈完整任务；续跑仍必须经过 Developer、Tester、Reviewer 的角色链路。
- 接口文档 v2.0.9 规定 Dry Run 自动加载目标分支绑定的 Testset；任一 Testset 未通过时不得创建 MR。
- 接口文档已有 `TaskRun retry`，但本功能需要保留失败事实并创建带来源关系的新续跑 Task，不能重置原 TaskRun。

## 3. 后端修改计划

### 3.1 识别可修复的 Testset 失败

新增统一判定方法，区分以下结果：

| 类型 | 示例 | 自动行为 |
|---|---|---|
| 合并冲突 | `MERGE_CONFLICT`、`GIT_MERGE_CONFLICT` | 沿用现有冲突自动续跑守护链 |
| Testset 断言失败 | `report.tests.status=FAILED` 且存在失败结果 | 仅提供人工触发修复入口 |
| Worker/GitHub 瞬时故障 | Worker 不可用、凭据交换失败、超时 | 后端补偿重试，不派 Agent 改代码 |
| 配置/权限/上下文错误 | Testset 不存在、基线失效、Workspace 被锁 | 展示明确原因，转人工处理 |

判定必须依据结构化 `DryRun.report.tests` 和 `failureCode`，不得解析原始日志或模型文本。

### 3.2 新增修复续跑入口

建议新增：

```http
POST /api/v1/projects/{projectId}/dry-runs/{dryRunId}/repair
Idempotency-Key: <key>
```

权限：Task 发起人或 Project Admin。

请求体建议：

```json
{
  "instruction": "请修复本次 Testset 失败，不要修改 Testset、门禁配置或测试断言。"
}
```

约束：

- `dryRunId` 必须属于当前 Project，且关联 `taskId`；
- Dry Run 必须是 `FAILED`，且失败类型为可修复 Testset 失败；
- 原 Task 必须为 `MR_FIRST + WAITING_PREFLIGHT`；
- 当前 Workspace HEAD、目标分支和 Testset 配置必须仍与失败 Dry Run 对应；上下文过期返回 `409 PREFLIGHT_CONTEXT_STALE`；
- 同一失败 Dry Run 已存在活动续跑时返回原续跑 Task，不能重复创建；
- 已存在未合并 MR、Workspace 写入租约冲突或任务已终止时拒绝；
- 达到最大修复次数（默认 3 次）后返回 `409 REPAIR_ATTEMPT_LIMIT_REACHED`，转人工处理。

服务端创建续跑 Task 时：

- `continuationOfTaskId` 指向原 Task；
- `workspaceId` 显式复用原 Workspace；
- `requirementGroupId`、项目和仓库范围与原 Task 一致；
- 记录 `sourceDryRunId`、`repairAttempt` 和脱敏失败摘要；
- 不复用原 TaskRun，不重置原 Dry Run；
- 续跑 Task 的交付模式沿用原 Task 的 `MR_FIRST`；
- 创建操作必须受 `Idempotency-Key` 保护，并在数据库层/服务层防并发重复。

### 3.3 续跑任务上下文

续跑 Task 传给 Planner/Developer 的上下文必须包含：

- 失败 Dry Run 的结构化 Testset 结果；
- 失败 Testset ID、失败用例摘要、退出码和脱敏错误信息；
- 当前 Workspace/仓库/HEAD/目标分支；
- 原 Task 的验收标准；
- 明确约束：
  - 只能修改业务代码和必要配置；
  - 不得修改 Testset 定义、质量门禁、测试断言以规避失败；
  - 无法判断是业务缺陷还是测试缺陷时必须 `success=false` 并等待人工；
  - 修改必须产出 Task 级 Diff。

禁止把 Token、凭据、宿主机路径、完整命令输出或未脱敏日志带入 Agent 上下文。

### 3.4 失败后的状态和门禁

原 Task 保持 `WAITING_PREFLIGHT`，不提前标记成功或交付失败；续跑 Task 独立执行。

续跑产生新 HEAD 后：

1. 原 Dry Run/CQ+1 不再匹配新 HEAD；
2. 服务端为新 HEAD 创建新的 Dry Run；
3. Dry Run 重新加载目标分支绑定的 Testset；
4. Testset 全部通过后，等待独立成员 CQ+1；
5. CQ+1 通过后，按现有幂等逻辑创建 MR；
6. 所有仓库 MR 创建成功后，原 Task 才进入 `SUCCEEDED`。

续跑失败不应覆盖原失败原因，应在 Task 详情中显示失败链：原 Dry Run -> 第 N 次修复 Task -> 新 Dry Run。

### 3.5 SSE 与消息事件

新增或复用以下事件：

- `preflight.repair-requested`：用户已请求修复，携带 `taskId`、`dryRunId`、`repairTaskId`；
- `task.updated`：续跑 Task 的 `PLANNING/RUNNING/FAILED/SUCCEEDED`；
- `dry-run.updated`：新 Dry Run 的状态；
- `preflight.updated`：新的 CQ+1 状态；
- `merge-request.updated`：真实 MR 创建/同步状态。

事件只携带资源 ID、状态、脱敏原因和时间戳。客户端收到事件后重新 GET 详情，不能把事件 payload 当完整 DTO。

需求群自动化卡片应更新原任务卡片或使用幂等 clientMessageId，避免每次重试新增重复消息。

### 3.6 数据模型建议

优先复用 Task 的续跑关系和现有 DryRun 结构，不新增旧式 Deliverable/WorkPackage 模型。

如果现有 `tasks` 表没有足够字段，新增版本化迁移：

- `continuation_source_dry_run_id`；
- `repair_attempt`；
- `repair_reason_code`；
- `repair_status`（可选，若能由 Task 状态和关联查询推导则不新增）。

同时更新初始化 schema、Entity、Mapper、DTO、Service 和接口文档。字段必须有项目归属校验、索引和幂等约束设计。

## 4. Web 前端修改计划

### 4.1 Dry Run 失败展示

在任务详情、预检区域和任务状态卡片中区分：

- 合并冲突；
- Testset 失败；
- Worker/GitHub 临时故障；
- 配置或上下文失效。

Testset 失败至少展示：Testset 名称/ID、失败状态、退出码、耗时、脱敏失败摘要、失败 HEAD 和目标分支。

### 4.2 “让 Agent 修复”按钮

仅在以下条件满足时展示：

- 当前用户是 Task 发起人或 Project Admin；
- Dry Run 为可修复的 Testset 失败；
- Task 为 `MR_FIRST + WAITING_PREFLIGHT`；
- 没有活动续跑；
- 未达到修复次数上限；
- 当前 Workspace 未被未合并 MR 锁定。

点击后弹出确认框，允许补充修复说明；默认提示明确说明 Agent 不会修改 Testset 和门禁配置。

提交时携带稳定 `Idempotency-Key`。按钮进入提交中状态，重复点击不得创建多个续跑 Task。

### 4.3 续跑状态展示

展示原任务与续跑任务关系：

```text
Testset 失败
  -> 修复任务 #1 运行中
  -> 重新 Dry Run
  -> 等待 CQ+1
  -> 创建 MR
```

失败超过上限时显示“需要人工处理”，不要显示“系统异常”。

### 4.4 事件与刷新

- 监听 `preflight.repair-requested`、`task.updated`、`dry-run.updated`、`preflight.updated`、`merge-request.updated`；
- SSE 断线或游标过期后重新拉取 Task、Dry Run、TaskRun 和预检详情；
- 不依据本地按钮状态判断 MR 是否已创建，以后端真实状态为准。

## 5. 移动端修改计划

移动端与 Web 保持同一接口和状态语义：

- Task 详情增加 Testset 失败摘要和修复次数；
- 增加“让 Agent 修复”操作及确认弹窗；
- 支持补充修复说明、提交中/成功/失败/已达上限状态；
- 续跑任务可跳转查看 TaskRun、Diff 和新的 Dry Run 报告；
- 复用现有 SSE/轮询刷新，断线后按接口重新同步；
- 不在客户端自行判断 Project Admin，不自行生成 `userId`、`agentId` 或 Git SHA；
- 无权限、上下文过期、活动续跑和达到上限时，按后端错误码展示对应文案。

## 6. 接口文档修改范围

需要更新 v2.0.9 的以下部分：


1. **§3.2 状态枚举**：补充修复续跑相关状态语义（若最终复用 Task/TaskRun 现有状态，则只补充关系说明，不新增状态）。
2. **§12.2 任务运行与执行上下文**：说明人工触发修复会创建新的 continuation Task/TaskRun，原运行不可重置。
3. **§12.4 Test Run 与 Dry Run**：补充 Testset 失败可修复类型、不可修复类型和上下文失效规则。
4. **§27.10 MR_FIRST 自动预检与创建 MR**：明确“Testset 失败不自动修改代码；用户调用 repair 接口后才创建续跑任务”。
5. 新增 **“Testset 失败人工修复”** 小节：请求、响应、权限、错误码、幂等、状态和 SSE 事件。

建议错误码：

| 错误码 | 含义 |
|---|---|
| `DRY_RUN_REPAIR_NOT_APPLICABLE` | 当前失败不是可修复 Testset 失败 |
| `DRY_RUN_REPAIR_TASK_NOT_WAITING_PREFLIGHT` | 原 Task 不在 MR 前预检等待状态 |
| `DRY_RUN_REPAIR_ALREADY_ACTIVE` | 已存在活动续跑 Task，返回其 ID |
| `PREFLIGHT_CONTEXT_STALE` | HEAD、目标基线或 Testset 配置已变化 |
| `REPAIR_ATTEMPT_LIMIT_REACHED` | 超过最大修复次数 |
| `WORKSPACE_WRITE_LOCKED` | Workspace 当前被其他运行占用 |
| `WORKSPACE_WRITE_BLOCKED_BY_OPEN_MR` | 分支已有未合并 MR |

接口响应至少返回：`repairTaskId`、`sourceTaskId`、`sourceDryRunId`、`attempt`、`status`、`createdAt`。

## 7. 测试计划

### 后端单元测试

- Testset 失败可触发修复，合并冲突不能走该入口；
- Worker/GitHub 瞬时故障不能创建修复 Task；
- 非发起人/非 Project Admin 无权限；
- Task 状态、deliveryMode、Project 归属校验；
- HEAD/target/Testset 变化返回 `PREFLIGHT_CONTEXT_STALE`；
- 同一 Idempotency-Key 返回同一续跑 Task；
- 并发请求只创建一个活动续跑；
- 达到 3 次上限后拒绝；
- 新 HEAD 会使旧 Dry Run/CQ+1 失效；
- 续跑不得修改 Testset 配置；
- SSE 和需求群卡片只发布一次且 payload 脱敏。

### 前端测试

- 不同失败类型显示不同操作；
- 无权限/已达上限/已有活动续跑时隐藏或禁用按钮；
- 重复点击只发一次请求；
- SSE 事件乱序、断线、游标过期后的重新拉取；
- Web 与移动端状态文案一致；
- 新 Dry Run、CQ+1、MR 状态能正确刷新。

### 集成验证

```text
创建 MR_FIRST Task
  -> 代码提交并推送
  -> Dry Run Testset 失败
  -> 用户点击“让 Agent 修复”
  -> 续跑 Task 复用 Workspace
  -> Agent 只修改业务代码
  -> Testset 重新通过
  -> 独立 CQ+1
  -> 幂等创建 MR
```

必须额外验证：Worker 不可用、目标分支推进、用户重复点击、并发修复请求、修复失败和超过上限等边界条件。

## 8. 实施顺序

1. 后端先实现失败分类、续跑关系、权限、幂等和修复接口。
2. 增加后端单元测试和数据库迁移（若确实需要新字段）。
3. 接通续跑 Task 的 Agent 上下文约束和 Testset 重新执行。
4. Web 接入详情、按钮、SSE 和错误码。
5. 移动端复用接口接入相同交互。
6. 更新接口文档和前端联调示例。
7. 完成完整链路和边界测试后，再决定是否开启生产配置。

## 9. 验收标准

- 用户不点击修复时，Testset 失败不会自动修改代码；
- 用户点击一次最多创建一个续跑 Task；
- 修复 Task 不得修改 Testset、质量门禁或测试断言；
- 修复后的新 HEAD 必须重新 Dry Run 和 CQ+1；
- 未通过门禁时绝不创建 MR；
- 原失败事实可追溯，续跑关系和次数可查询；
- Web、移动端、SSE、接口文档和后端状态语义一致；
- 所有失败路径都有明确、脱敏、可操作的错误提示。
