# Diff 拒绝后回群引用：前端与移动端变更说明

## 适用范围

本次变更用于“用户拒绝交付 Diff 后，回到需求群引用该 Diff，带着拒绝意见创建续作任务”的流程。服务端会复用原 Task 的 Workspace，并把拒绝意见注入续作任务上下文。

## 接口变化

### 1. 拒绝最终 Diff

```http
POST /api/v1/projects/{projectId}/tasks/{taskId}/diff-review/reject
Content-Type: application/json
Idempotency-Key: {uuid}

{"reason":"请补充边界条件测试"}
```

`reason` 必填，最长 4000 字符。成功响应的 `data` 为 `DiffReviewBatchResponse`，重点字段：

```json
{
  "taskId": "...",
  "reviewStatus": "REJECTED",
  "reviewReason": "请补充边界条件测试",
  "deliveryStatus": "...",
  "diffs": [],
  "repositoryDeliveries": []
}
```

客户端收到成功响应后应立即把当前交付卡更新为 `REJECTED`，隐藏“确认交付”和“拒绝”按钮，提供“回群继续修改”。

### 2. 群消息中的 DIFF 卡

群消息接口保持不变：

```http
GET /api/v1/projects/{projectId}/groups/{groupId}/messages
```

当 `type=DIFF` 时，`content` 增加/返回：

```json
{
  "diffId": "...",
  "taskId": "...",
  "reviewStatus": "REJECTED",
  "reviewReason": "请补充边界条件测试",
  "deliveryStatus": "..."
}
```

旧消息没有 `reviewReason` 时按空值兼容，不应导致卡片渲染失败。

### 3. 引用 DIFF 创建续作任务

仍使用现有发送消息接口，不新增移动端专用接口：

```http
POST /api/v1/projects/{projectId}/groups/{groupId}/messages
```

引用 DIFF 时提交 `type=QUOTE`、`replyToId=DIFF 消息 ID`，并在 `content` 中携带引用信息：

```json
{
  "type": "QUOTE",
  "replyToId": "...diff-message-id...",
  "content": {
    "text": "请继续修改",
    "quotedMessageId": "...diff-message-id...",
    "quotedText": "...",
    "quotedSenderName": "编排助手",
    "replyText": "请继续修改"
  },
  "replyText": "请继续修改",
  "clientMessageId": "..."
}
```

服务端从被引用 DIFF 的 `content.diffId` 推导源 Task/Workspace。续作请求不要传 `repositoryIds`、`workspaceId` 或 `continuationOfTaskId`；普通引用非 DIFF 消息仍按普通消息处理。

发送成功后，如响应中的 `task` 为空，客户端可调用：

```http
POST /api/v1/projects/{projectId}/groups/{groupId}/messages/{messageId}/trigger-task
```

请求体只需传 `title` 和可选 `requirement`，同样不要传仓库列表。该接口具备按触发消息幂等，重复调用不会创建重复续作任务。

## 前端与移动端交互要求

1. `reviewStatus=REJECTED` 的 Diff 卡显示拒绝意见，并将操作文案改为“根据拒绝意见继续修改”。
2. 点击继续修改前，先查询现有预检状态；预检中、等待 CQ+1、创建中、已有 OPEN/MERGED/CLOSED MR 时，阻止引用并展示后端状态对应的原因。
3. 点击继续修改时，若输入框为空，可预填“请根据以下拒绝意见修改：\n{reviewReason}\n\n请继续修改：”；用户已有输入时不得覆盖。
4. 交付中心收到拒绝成功响应后立即刷新本地状态；显示“已拒绝，请回需求群根据拒绝意见继续修改”和“回群继续修改”入口。
5. 续作任务创建后刷新群消息、任务列表和任务详情；不要把续作当成新的独立 Workspace 展示。
6. `QUOTED_DIFF_INVALID`、`QUOTED_DIFF_NOT_ACCESSIBLE`、`QUOTED_DIFF_*` 422 错误和预检冲突应展示服务端 `error.message`，不要只显示通用“发送失败”。
7. 发送消息和显式触发任务都要携带 `Idempotency-Key`；发送消息使用唯一 `clientMessageId`，以便重试不重复建任务。

## 兼容与验证

- `reviewReason` 为可选字符串，移动端应兼容缺失、`null` 和空字符串。
- 不需要新增数据库字段或新增 API 路径。
- Diff 卡、交付中心、群聊消息列表应覆盖：拒绝成功、拒绝意见展示、引用续作、重复点击幂等、已有 MR/预检状态阻止引用。
