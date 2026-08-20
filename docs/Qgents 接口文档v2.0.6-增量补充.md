# Qgents 接口文档 v2.0.6 —— 增量补充（单独成册）

> 本册只包含 2026-08-17 ~ 2026-08-24 期间落地、并已补入 `Qgents 接口文档v2.0.6.md` 的接口改动，供前后端快速对齐；与主文档冲突时以主文档为准。
>
> 涉及范围：群聊 @ 提及（角标/提示/通知直达）、引用回复字段、Skill/Memory 审批优化、自定义 Agent 管理与头像上传、交付中心 CODE diffId、任务中心与交付中心 keyword 筛选、项目 repositoryCount、需求群成员管理写接口、Dry Run 执行与重试。
>
> 所有写接口均需 `Idempotency-Key`（统一约定）。

## Dry Run 执行与重试（2026-08-24）

本次增量以主文档第 28 节为准：Dry Run 报告新增冻结的 `headCommit`、`targetBranch`、`targetCommit`、`attemptCount`、`updatedAt`，逐 Testset 结果新增脱敏 `message`，SSE `dry-run.updated` 新增 `targetCommit`。

新增 `POST /api/v1/projects/{projectId}/dry-runs/{dryRunId}/retries`。它只对瞬时基础设施错误创建不可变新尝试并保留 `retryOfDryRunId/retryReasonCode`，不覆盖原失败事实，也不能用于绕过合并冲突或 Testset 失败。具体错误码、上限和 RetryContext 脱敏规则见主文档第 28 节。

---

## 目录

1. [群聊与消息（§7）](#1-群聊与消息)
2. [通知中心 MESSAGE_MENTION（§7.1）](#2-通知中心-message_mention)
3. [Skill 免审批与 PRIVATE 语义（§8）](#3-skill-免审批与-private-语义)
4. [Memory 免审批与 AI 按群检索（§9）](#4-memory-免审批与-ai-按群检索)
5. [自定义 Agent 管理与头像上传（§11.1）](#5-自定义-agent-管理与头像上传)
6. [交付中心 CODE diffId（§20）](#6-交付中心-code-diffid)
7. [任务中心 keyword 筛选（§16.1）](#7-任务中心-keyword-筛选)
8. [项目 repositoryCount（§24.1）](#8-项目-repositorycount)
9. [需求群成员管理写接口（§24.4.1）](#9-需求群成员管理写接口)
10. [团队 / 项目按最后活跃时间排序](#10-团队--项目按最后活跃时间排序)
11. [注册邮箱验证码（§4 补充）](#11-注册邮箱验证码)
12. [团队头像与成员头像（§5.1 补充）](#12-团队头像与成员头像)（含项目头像 §12.5、PATCH 语义 §12.4）
13. [自定义 Agent 发布审核（§11.1/§20 补充）](#13-自定义-agent-发布审核)
14. [Dry Run 前端实施要求（§28 补充）](#14-dry-run-前端实施要求)
15. [Worker 日志定位与数据库初始化（§15）](#15-worker-日志定位与数据库初始化2026-08-19)
16. [群聊最终 Diff 卡预览（§7/§23 增量）](#16-群聊最终-diff-卡预览2026-08-20)

---

## 1. 群聊与消息

### 1.1 `mentionedUnread`（未读「@ 我」数）

**接口**：`GET /projects/{projectId}/groups`、`GET /chat/main-groups`（主群聚合）、`GET /projects/{projectId}/groups/{groupId}`（详情）

`GroupResponse` 均返回 `mentionedUnread`：

- 类型 `number`，恒 ≥ 0，无未读 @ 时为 0。
- 口径（与 `unreadCount` 同源，是其子集）：`sequence_no > 已读游标` 且 `mentions` 含当前用户（`JSON_CONTAINS(mentions, {"type":"USER","id":<当前用户>})`）且 `sender_id != 当前用户` 的消息数。
- 按当前登录用户计：A 被 @ 不影响 B。
- 前端仅在 `typeof mentionedUnread === 'number' && mentionedUnread > 0` 时显示「有人@你」角标/提示。

### 1.2 标记已读 `POST /projects/{projectId}/groups/{groupId}/read`

|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/projects/{projectId}/groups/{groupId}/read`|群成员（主群=项目成员，需求群=群成员）|进群全读：把已读游标推进到该群最新消息 sequence；并发幂等（`GREATEST` 保证游标单调不回退）|

响应 `data`：

```JSON
{
  "groupId": "group-uuid",
  "lastReadSequenceNo": 42,
  "unreadCount": 0
}
```

- `lastReadSequenceNo` 是前端「新消息 `sequence > 游标` → 算未读 / 未读 @」的判定基准。
- 空群（无消息）时 `lastReadSequenceNo = 0`。

### 1.3 单条消息定位 `GET /projects/{projectId}/groups/{groupId}/messages/{messageId}`

|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/groups/{groupId}/messages/{messageId}`|群成员|按消息 ID 拉取单条群消息（通知直达被 @ 消息：目标消息较旧、不在前端已加载分页窗口时调用，拉取后合并进本地列表再滚动高亮）|

响应 `data` 为完整 `MessageResponse`（含 `senderName`/`sequence`/`mentions`/`replyToId`），与列表项结构一致。

错误：

|code|HTTP|场景|
|---|---|---|
|`GROUP_NOT_FOUND`|404|群不存在或无权限|
|`MESSAGE_NOT_FOUND`|404|消息不存在或不属于该群|

### 1.4 QUOTE 引用消息（回复/引用）字段冻结

发送 `type=QUOTE` 消息时，`content` 使用以下字段（不再要求 `text`）：

```JSON
{
  "type": "QUOTE",
  "content": {
    "quotedMessageId": "message-uuid",
    "quotedText": "被引用消息的原始内容摘要",
    "quotedSenderName": "张工"
  },
  "replyText": "我的回复正文",
  "replyToId": "message-uuid",
  "mentions": [{"type": "AGENT", "id": "agent-uuid"}],
  "clientMessageId": "cmsg_01J..."
}
```

- `quotedMessageId`/`quotedText` 必填，缺失返回 `422 MESSAGE_CONTENT_INVALID`（旧的 `text` 字段要求已废弃）。
- 引用 DIFF 卡时 `quotedText` 为该 DIFF 卡摘要；**引用续作判定只看 `replyToId` 指向的父消息类型（DIFF 与否），与正文无关**。
- 前端展示约定：气泡内只显示回复正文（`replyText`），被引用原消息以「竖线 + 灰色小字」挂载在气泡下方；嵌套引用（引用的是 QUOTE 消息）取 `replyText ?? quotedText` 显示，避免叠加成 `[引用][引用]…`。

---

## 2. 通知中心 MESSAGE_MENTION

### 2.1 kind 枚举

`Notification.kind` 新增 `MESSAGE_MENTION`。

### 2.2 触发来源

|触发来源|通知 kind|
|---|---|
|群聊发送消息 `POST .../messages` 且 `mentions` 含某用户（排除发送者本人）|`MESSAGE_MENTION`|

### 2.3 字段约定

- `title`：「有人在群聊中提到了你」；`description`：`发送者 在群「群名」中提到了你：消息文本摘要`（摘要为空时省略冒号后内容）。
- `projectId` = 消息所属项目，`groupId` = 来源需求群（点击跳转该群）。
- `resourceId` **必填** = 被 @ 的那条消息 ID：前端跳群后据它自动滚动高亮到该消息（`ChatPanel` 调用 `GET .../messages/{messageId}` 分页外定位，见 1.3）；`resourceId` 缺失时前端兜底跳到该群最上面一条被 @ 的消息。
- 通知写入独立于 SSE：`notification.created` 事件驱动铃铛刷新（既有机制）；`@AGENT` 不写用户通知，走自动触发任务链路。

---

## 3. Skill 免审批与 PRIVATE 语义

**免审批规则：**

- `PRIVATE` Skill：任何人创建即 `PUBLISHED`，无需审核；仅创建者自己可见/使用；**不进入交付中心**（交付中心 `skillItems` 过滤 `PRIVATE`）；**不支持转共享审核**（`submit-review` 对 PUBLISHED 状态返回状态冲突）。
- `PROJECT_SHARED` Skill：Project Admin 自建直接 `PUBLISHED`（免审批）；普通成员创建为 `DRAFT`，提交审核后由 Admin 发布。

**接口表（说明更新）：**

|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/skills`|项目成员|查询 Skill，支持状态、标签过滤；仅返回 `PROJECT_SHARED` 或自己创建的|
|`POST`|`/projects/{projectId}/skills`|项目成员|创建 Skill（`PRIVATE` 创建即发布；`PROJECT_SHARED` 按免审批规则定初始状态）|
|`GET`/`PATCH`|`/projects/{projectId}/skills/{skillId}`|项目成员/创建者或 Project Admin|获取/编辑草稿或审核中内容；他人 `PRIVATE` 视为不可见（404）|
|`POST`|`/projects/{projectId}/skills/{skillId}/submit-review`|创建者或 Project Admin|提交审核（仅 `DRAFT`/`REJECTED` 可提交；PRIVATE 已发布不可提交）|
|`POST`|`/projects/{projectId}/skills/{skillId}/approve`|Project Admin|发布为 `PROJECT_SHARED`（仅 `PENDING_REVIEW`）|
|`POST`|`/projects/{projectId}/skills/{skillId}/reject`|Project Admin|拒绝并给出原因|
|`POST`|`/projects/{projectId}/skills/{skillId}/archive`|Project Admin|下线已发布 Skill|

---

## 4. Memory 免审批与 AI 按群检索

**免审批规则：** Project Admin **手动创建**的 Memory 直接 `APPROVED` 上架（免审批）；普通成员手动创建为 `DRAFT`。AI 沉淀草稿（`POST /memories/drafts`）始终为 `DRAFT`，任何人均需审核（AI 不得直接批准）。

**接口表（说明更新）：**

|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/memories`|项目成员|查询 Memory，默认仅 `APPROVED`（非 APPROVED 仅创建者或 Admin 可见），支持状态、标签过滤|
|`POST`|`/projects/{projectId}/memories`|项目成员|手动创建（Admin 直接 `APPROVED`，成员 `DRAFT`）|
|`POST`|`/projects/{projectId}/memories/drafts`|项目成员|按群自动检索最近聊天，AI 生成草稿（`DRAFT`）|
|`GET`/`PATCH`|`/projects/{projectId}/memories/{memoryId}`|项目成员/创建者或 Project Admin|获取/编辑草稿或审核中内容|
|`POST`|`/projects/{projectId}/memories/{memoryId}/submit-review`|创建者或 Project Admin|提交审核|
|`POST`|`/projects/{projectId}/memories/{memoryId}/approve`|Project Admin|批准并发布|
|`POST`|`/projects/{projectId}/memories/{memoryId}/reject`|Project Admin|拒绝并给出原因|
|`POST`|`/projects/{projectId}/memories/{memoryId}/archive`|Project Admin|归档 Memory|

**群聊生成草稿（AI 沉淀）请求示例**（改为按群自动检索，客户端不再勾选消息）：

```JSON
{
  "groupId": "group-uuid",
  "instruction": "沉淀为项目认证安全约定"
}
```

- 服务端读取该群最近 50 条消息（`sequence` 倒序取最近 N 条，再还原时间正序），交由 AI 甄别值得沉淀的事实并生成草稿；来源消息自动记录到 `sources[]`。
- **空群不消耗 LLM**：群内无任何消息时返回 `422 GROUP_NO_MESSAGES`（「该需求群暂无消息，无需沉淀」）。
- 群不存在或不属于该项目返回 `422 GROUP_NOT_IN_PROJECT`。
- AI 生成失败返回 `500 AI_DRAFT_FAILED`。

---

## 5. 自定义 Agent 管理与头像上传

### 5.1 自定义 Agent 管理接口补充

|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/teams/{teamId}/agents`|团队成员|创建自定义 Agent（`PRIVATE`+`ACTIVE`）；创建后 `isDefault=false`；需 `Idempotency-Key`（同一 Key 重试返回首次结果，不同请求体复用同一 Key 返回 409）|
|`PATCH`|`/teams/{teamId}/agents/{agentId}`|创建者|编辑自定义 Agent（系统预置 `isDefault=true` 不可编辑）；至少一个字段；`visibility/status/createdBy/isDefault` 不允许客户端修改|
|`POST`|`/teams/{teamId}/agents/{agentId}/publish`|创建者|普通创建者提交审核（`PRIVATE+ACTIVE` → `PENDING+ACTIVE`）；Team Owner 发布自己创建的 Agent 时直接 `PRIVATE+ACTIVE` → `TEAM+ACTIVE`；需 `Idempotency-Key`|
|`POST`|`/teams/{teamId}/agents/{agentId}/unpublish`|创建者或 Team Owner|收回为私有（`TEAM+ACTIVE` → `PRIVATE+ACTIVE`）；需 `Idempotency-Key`|
|`POST`|`/teams/{teamId}/agents/{agentId}/archive`|创建者或 Team Owner|归档（`PRIVATE/TEAM+ACTIVE` → `ARCHIVED`），已运行任务不受影响；需 `Idempotency-Key`|

- 自定义 Agent 的 `role` 取值与身份卡一致（`ORCHESTRATOR`/`PLANNER`/`DEVELOPER`/`TESTER`/`REVIEWER`/`GENERAL`）；`name` 必填且去空白后非空，`prompt` 必填，`avatar`/`description` 可选（空转 `null`）。
- 创建响应为 `AgentResponse`（含 `visibility=PRIVATE`、`status=ACTIVE`、`isDefault=false`）。
- `PATCH` 支持修改 `name/avatar/role/description/prompt`；`role` 变更只影响后续新分配的 TaskStep，不改变已定型的 `TaskStep.assignedAgentId`。

### 5.2 Agent 头像上传（OSS 直传 + 公共读 URL）

|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/teams/{teamId}/agents/avatar/credential`|团队成员|签发 Agent 头像直传凭证；对象键 `agents/{teamId}/{uuid}.{ext}`|
|`POST`|`/teams/{teamId}/agents/avatar/confirm`|团队成员|确认上传并返回公共读 URL；不写任何用户字段|

`credential` 请求体：

```JSON
{
  "mediaType": "image/png",
  "sizeBytes": 20480
}
```

`credential` 响应 `data`：

```JSON
{
  "objectKey": "agents/{teamId}/{uuid}.png",
  "uploadUrl": "https://oss-endpoint/...",
  "method": "PUT",
  "headers": {"Content-Type": "image/png", "x-oss-...": "..."},
  "expiresAt": "2026-08-18T10:00:00Z"
}
```

`confirm` 请求体：`{ "objectKey": "agents/{teamId}/{uuid}.png" }`；响应 `data`：`{ "avatarUrl": "https://公共读URL/..." }`。

- 流程：前端 `avatarCredential` 取凭证 → 用凭证直传 OSS → `avatarConfirm` 校验对象键前缀（`agents/{teamId}/`）并返回公共读 URL → 把 `avatarUrl` 提交到 `PATCH /teams/{teamId}/agents/{agentId}`（或创建请求 `avatar` 字段）。
- 对象键与 `teamId` 不匹配时 `confirm` 返回 `422` 错误；OSS bucket 需配置公共读（否则前端展示 403，属基础设施配置而非代码缺陷）。

---

## 6. 交付中心 CODE diffId

**接口**：`GET /api/v1/projects/{projectId}/delivery-items`

**CODE 专属字段新增 `diffId`**：批次内按 `projectRepositoryId` 升序第一条的代表性 Diff ID，与 Task 详情 `diffReviewSummary` 同源；交付中心「查看 Diff」据此直接跳转 Diff 查看页 `GET /diffs/{diffId}`，无需经任务中心中转。

**查询参数新增 `keyword`**：按不区分大小写包含匹配 title/summary/resourceId/来源任务展示码与标题/创建人/提交人/CODE 仓库名/MEMORY·SKILL 摘要字段。

---

## 7. 任务中心 keyword 筛选

**接口**：`GET /api/v1/projects/{projectId}/tasks`

**查询参数新增 `keyword`**：按不区分大小写包含匹配 `displayCode`/`title`/`requirement`/需求群名/创建人/绑定仓库展示名与全名；超 100 个 Unicode 字符返回 `422`。

---

## 8. 项目 repositoryCount

`ProjectResponse` 新增 `repositoryCount`：该项目**生效（ACTIVE）仓库绑定数**，用于项目卡/详情展示「绑定 N 个仓库」，避免前端逐卡 N+1 查询仓库列表；`GET /teams/{teamId}/projects` 列表项与 `GET /projects/{projectId}` 详情均返回。

---

## 9. 需求群成员管理写接口

|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/projects/{projectId}/groups/{groupId}/members`|群创建者或 Project Admin|邀请项目成员入群（body `{ "userId": "user-uuid" }`，201）；仅 `REQUIREMENT` 群，主群返回 `422`|
|`DELETE`|`/projects/{projectId}/groups/{groupId}/members/{memberUserId}`|群创建者或 Project Admin|移出群聊（204）；创建者本人不可移出；仅 `REQUIREMENT` 群，主群返回 `422`|

- 被邀请用户必须是该项目成员（`project_members`），否则 `422 GROUP_MEMBER_NOT_PROJECT_MEMBER`。
- 移出后该用户失去该群的消息/任务/SSE 可见性（消息、任务列表、SSE 推送均按群成员收紧）；不影响其在项目内其他群的成员身份。
- 群创建者不可被移出（返回 `409` 或 `422`）。
- 两个接口均需 `Idempotency-Key`（写操作统一约定）。

---

## 10. 团队 / 项目按最后活跃时间排序

为团队选择页与项目选择页按「最近活跃优先」展示新增两个聚合接口，均不分页，返回完整列表。

### 10.1 团队按最后活跃排序 `GET /api/v1/teams/by-last-activity`

|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/api/v1/teams/by-last-activity`|登录用户|返回当前用户加入的**全部**团队，按最后活跃时间倒序|

- **最后活跃口径**：团队最后活跃 = 该团队下所有项目最后活跃的最大值；项目最后活跃 = 该项目下所有群（`PROJECT_MAIN` + `REQUIREMENT`）中**最近消息时间**（`last_message_at`）的最大值；任何群都无消息时为 `null`，在排序中沉底（不兜底创建时间，避免新创建的无活跃团队被「伪造」成最新活跃而排首位）。
- 响应 `data` 为 `TeamResponse[]`，新增字段 `lastActivityAt`（ISO8601 UTC，无活跃时为 `null`）；其余字段（`id/name/role/memberCount/description/createdAt`）与 `GET /teams` 一致。

```JSON
{
  "data": [
    {
      "id": "team-uuid",
      "name": "前端团队",
      "role": "TEAM_OWNER",
      "memberCount": 4,
      "description": null,
      "createdAt": "2026-08-10T08:00:00",
      "lastActivityAt": "2026-08-18T09:30:00Z"
    }
  ],
  "requestId": "req_..."
}
```

### 10.2 项目按最后活跃排序 `GET /api/v1/teams/{teamId}/projects/by-last-activity`

|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/api/v1/teams/{teamId}/projects/by-last-activity`|团队成员|返回某团队下当前用户可见的**全部**项目，按最后活跃时间倒序|

- **最后活跃口径**：项目最后活跃 = 该项目下所有群（`PROJECT_MAIN` + `REQUIREMENT`）中**最近消息时间**（`last_message_at`）的最大值；任何群都无消息时为 `null`，在排序中沉底（不兜底创建时间）。
- 可见性规则与 `GET /teams/{teamId}/projects` 一致（canonical Team Owner 见全部项目，其余成员见 `project_members` 显式成员）。
- 响应 `data` 为 `ProjectResponse[]`，新增字段 `lastActivityAt`（ISO8601 UTC，无活跃时为 `null`）；其余字段（`id/teamId/name/description/role/status/memberCount/repositoryCount`）与现有项目列表一致。

```JSON
{
  "data": [
    {
      "id": "project-uuid",
      "teamId": "team-uuid",
      "name": "Qgents Web",
      "description": null,
      "role": "PROJECT_MEMBER",
      "status": "ACTIVE",
      "memberCount": 3,
      "repositoryCount": 2,
      "lastActivityAt": "2026-08-18T09:30:00Z"
    }
  ],
  "requestId": "req_..."
}
```

> 两个接口均为只读，无需 `Idempotency-Key`；排序完全由服务端完成，客户端无需二次排序。

---

## 11. 注册邮箱验证码

为杜绝假邮箱注册，注册流程改为「先发验证码、再带码注册」两步。验证码为 6 位数字、10 分钟有效、一次性使用；服务端仅存 SHA-256 哈希，不落明文。

### 11.1 发送验证码 `POST /api/v1/auth/register/verification-codes`

|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/api/v1/auth/register/verification-codes`|匿名|发送注册邮箱验证码到指定邮箱|

请求体：

```JSON
{"email": "member@example.com"}
```

响应（202）：

```JSON
{"data": {"message": "验证码已发送到邮箱，10 分钟内有效"}, "requestId": "req_..."}
```

错误：

|code|HTTP|场景|
|---|---|---|
|`EMAIL_ALREADY_REGISTERED`|409|邮箱已注册（不发送邮件）|
|`RATE_LIMITED`|429|同一 IP+邮箱 1 小时内发送超过 5 次|

### 11.2 注册（带验证码）`POST /api/v1/auth/register`

请求体新增必填 `verificationCode`（长度固定 6）：

```JSON
{
  "email": "member@example.com",
  "verificationCode": "483920",
  "passwordKeyId": "rsa-2026-08",
  "password": "Base64(RSA-PKCS1-v1_5(password))",
  "displayName": "Lin"
}
```

错误：

|code|HTTP|场景|
|---|---|---|
|`INVALID_VERIFICATION_CODE`|422|验证码无效/过期/已使用（不区分具体原因，避免枚举邮箱状态）|
|`EMAIL_ALREADY_REGISTERED`|409|邮箱已注册|

> 前端流程：注册页输入邮箱 → 点「发送验证码」（先做邮箱格式校验，可加 60s 倒计时重发）→ 收到邮件填 6 位码 → 提交注册。验证码仅在注册事务内校验一次，注册失败重试需重新获取验证码。

### 11.3 密码重置邮箱验证码（v2.0.6 补充）

忘记密码同样改为 6 位数字验证码（不再发送重置深链）：

- `POST /auth/password-reset-requests`（body `{ "email": "..." }`）→ 向邮箱发送 **6 位数字验证码**（30 分钟有效、一次性；服务端仅存 SHA-256 哈希）。未注册邮箱同样返回 202（规避枚举）；限流同一 IP+邮箱 1 小时 3 次。
- `POST /auth/password-resets`：body `{ "token": "483920", "newPassword": "...", "passwordKeyId": "..." }`——`token` 即邮箱验证码；校验失败返回 `422 INVALID_RESET_TOKEN`。

> 前端忘记密码页保持现有交互（输入邮箱 → 获取验证码 → 填 6 位码 + 新密码提交），无需改动。

---

## 12. 团队头像与成员头像

### 12.1 团队头像上传

|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/api/v1/teams/{teamId}/avatar/credential`|团队成员|签发团队头像直传凭证；对象键 `teams/{teamId}/{uuid}.{ext}`|
|`POST`|`/api/v1/teams/{teamId}/avatar/confirm`|团队成员|确认上传并返回公共读 URL；不写任何用户字段|

- `credential` 请求体 `{ "mediaType": "image/png", "sizeBytes": 20480 }`，响应 `{ objectKey, uploadUrl, method, headers, expiresAt }`（与用户/Agent 头像一致）。
- `confirm` 请求体 `{ "objectKey": "teams/{teamId}/{uuid}.png" }`，响应 `{ avatarUrl }`。
- 对象键前缀必须匹配 `teams/{teamId}/`，否则 `403 AVATAR_OBJECT_KEY_FORBIDDEN`；对象未真实上传返回 `409 AVATAR_NOT_UPLOADED`；OSS 未启用返回 `501 AVATAR_STORAGE_NOT_CONFIGURED`。

### 12.2 头像字段

- `TeamResponse`（`GET /teams`、`GET /teams/{teamId}`、`GET /teams/by-last-activity`）新增 `avatarUrl`（团队头像，可为空）。
- `POST /teams` 创建请求可带 `avatarUrl`；`PATCH /teams/{teamId}` 可带 `avatarUrl`（null 保留原值，空串清空）。
- `GET /teams/{teamId}/members` 成员列表新增 `avatarUrl`（**用户头像** `users.avatar_url`，可为空），通讯录据此展示成员头像；无头像时前端回退首字占位。

### 12.3 前端创建团队页头像流程

创建团队页选图预览 → `POST /teams` 创建（此时无 teamId 无法先传）→ 用返回的 `teamId` 走 `credential → OSS PUT → confirm` 拿到 `avatarUrl` → `PATCH /teams/{teamId}` 回写 → 跳转团队详情。头像上传失败不阻断团队创建（提示后继续跳转）。

### 12.4 团队 / 项目 PATCH 语义（v2.0.6）

`PATCH /teams/{teamId}` 与 `PATCH /projects/{projectId}` 均为**部分更新**：

- `name` / `description` / `avatarUrl` 任一可单独提交（null 保留原值；空串清空 `description`/`avatarUrl`）。
- 团队 PATCH：`{ "avatarUrl": "..." }` 单独更新头像（此前 `name` 必填会报 `INVALID_ARGUMENT`，已修复）。
- 项目 PATCH：`{ "avatarUrl": "..." }` 单独更新头像；全字段缺省返回 `422 INVALID_PROJECT_OPERATION`「至少需要提供 name、description 或 avatarUrl」。

### 12.5 项目头像（v2.0.6）

|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/api/v1/projects/{projectId}/avatar/credential`|项目成员|签发项目头像直传凭证；对象键 `projects/{projectId}/{uuid}.{ext}`|
|`POST`|`/api/v1/projects/{projectId}/avatar/confirm`|项目成员|确认上传并返回公共读 URL；不写任何字段（前端随 `PATCH /projects/{projectId}` 回写）|

- `ProjectResponse`（`GET /teams/{teamId}/projects`、`GET /projects/{projectId}`、`GET /teams/{teamId}/projects/by-last-activity`）新增 `avatarUrl`（可为空）。
- 前端入口：项目群聊/主群聊天页右上角「群聊设置」→「项目头像」区块（上传/更换）。
- 错误码与团队头像一致：`403 AVATAR_OBJECT_KEY_FORBIDDEN` / `409 AVATAR_NOT_UPLOADED` / `501 AVATAR_STORAGE_NOT_CONFIGURED`。
- 前端同步约定：上传确认后 URL 追加版本参数 `?v=<时间戳>` 强制浏览器刷新，并同时失效 `['projects', projectId]` 与 `['teams', teamId, 'projects']` 两类查询。

---

## 13. 自定义 Agent 发布审核

### 13.1 状态机

普通成员创建的自定义 Agent 发布为 `TEAM` 需 **Team Owner 审核**；Team Owner 发布自己创建的 Agent 时直接发布，无需自己审核：

```
普通创建者：
PRIVATE（默认，仅创建者）──publish──▶ PENDING（待审核）
    ▲                                   │
    └────── reject（Team Owner）────────┘
                                        │ approve（Team Owner）
                                        ▼
                                   TEAM（团队共享，不可回私有）──archive──▶ ARCHIVED

Team Owner 自建：
PRIVATE（默认，仅创建者）──publish──▶ TEAM（团队共享，不可回私有）──archive──▶ ARCHIVED
```

- `visibility` 取值：`PRIVATE` / `PENDING` / `TEAM`（DB `CHECK` 已放宽）。
- **已发布 `TEAM` 不可收回为私有**：`unpublish` 废弃，返回 `409 AGENT_UNPUBLISH_DISALLOWED`，只能归档。
- `PENDING` 对创建者与 Team Owner 可见（列表/详情）；拒绝后回到 `PRIVATE`，创建者可修正后重新提交。

### 13.2 接口

|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/api/v1/teams/{teamId}/agents/{agentId}/publish`|创建者|普通创建者提交发布审核（`PRIVATE+ACTIVE` → `PENDING+ACTIVE`）；Team Owner 发布自己创建的 Agent 时直接 `PRIVATE+ACTIVE` → `TEAM+ACTIVE`，并写入本人为 `reviewedBy`；需 `Idempotency-Key`|
|`POST`|`/api/v1/teams/{teamId}/agents/{agentId}/approve`|Team Owner|批准发布（`PENDING+ACTIVE` → `TEAM+ACTIVE`）；需 `Idempotency-Key`|
|`POST`|`/api/v1/teams/{teamId}/agents/{agentId}/reject`|Team Owner|拒绝发布（`PENDING+ACTIVE` → `PRIVATE+ACTIVE`）；body 可选 `{"reason": "..."}`；需 `Idempotency-Key`|
|`POST`|`/api/v1/teams/{teamId}/agents/{agentId}/unpublish`|—|废弃：`409 AGENT_UNPUBLISH_DISALLOWED`|
|`POST`|`/api/v1/teams/{teamId}/agents/{agentId}/archive`|创建者或 Team Owner|归档（`PRIVATE/PENDING/TEAM+ACTIVE` → `ARCHIVED`）|

错误码：

|code|HTTP|场景|
|---|---|---|
|`TEAM_OWNER_REQUIRED`|403|非 Team Owner 调 approve/reject|
|`AGENT_STATE_CONFLICT`|409|状态不满足（如非 PENDING 却 approve/reject）|
|`AGENT_UNPUBLISH_DISALLOWED`|409|对已发布 TEAM Agent 调 unpublish|

`AgentResponse` 新增：`reviewReason`（拒绝原因，仅创建者可见）、`reviewedBy`、`reviewedAt`（ISO8601 UTC）。

### 13.3 交付中心 AGENT 类型

- `GET /api/v1/projects/{projectId}/delivery-items?type=AGENT`（及 `delivery-summary`、export）支持 AGENT 类型。
- 进入范围：`PENDING`（待审核）/`TEAM`（已批准）/`ARCHIVED`（已归档）；`PRIVATE` 与系统预置 Agent 不进入。
- 专属字段：`role`、`descriptionExcerpt`、`isDefault`（恒 false）；**不返回 `prompt` 全文**。
- 展示状态：`PENDING`→`PENDING_REVIEW`、`TEAM`→`ACCEPTED`、`ARCHIVED`→`ARCHIVED`。
- `openTarget = { kind: "AGENT", agentId }`。
- 操作能力：`canApprove`/`canReject` = Team Owner 且 PENDING；`canArchive` = 创建者或 Team Owner 且 TEAM；`canSubmitReview` 恒 false（发布走 Agent 管理页 `publish`，不进交付中心）。Team Owner 直发后直接返回 `TEAM`，因此不会有待本人审批的交付项。

---

## 14. Dry Run 前端实施要求

本节是主文档 §28 的前端落地清单。Dry Run 创建成功返回 `202` 仅代表排队，不代表测试通过、CQ+1、MR 创建或合并完成。

### 14.1 请求与响应类型

创建请求仍为 `repositoryId`、`sourceRef`、`targetBranch`，`taskId` 可选；前端**不要提交 `targetCommit`**。服务端刷新目标分支后返回固定 `targetCommit`，前端应在报告页展示该 SHA。

报告至少消费以下字段：

|字段|用途|
|---|---|
|`id`|当前 Dry Run 标识；重试后替换为新 ID|
|`status`|`QUEUED/RUNNING/PASSED/FAILED/CANCELLED`|
|`headCommit`|本次执行的源提交 SHA|
|`targetBranch`|目标分支名|
|`targetCommit`|本次执行固定的目标提交 SHA|
|`attemptCount`|服务端记录的执行次数|
|`createdAt` / `updatedAt`|创建和最后更新时间|
|`report`|冲突、测试汇总和脱敏失败摘要；排队/运行时可为 `null`|

`report.tests.results[]` 逐项包含 `testsetId`、`status`、`exitCode`、`durationMs`、`failureCode`、`message`。`message` 是脱敏摘要；禁止展示原始 stdout/stderr、命令、凭据或宿主机路径。

### 14.2 重试按钮与错误处理

调用 `POST /api/v1/projects/{projectId}/dry-runs/{dryRunId}/retries`，请求体为空，必须带 `Idempotency-Key`。成功后使用返回的**新** Dry Run ID 重新查询报告，原报告保持只读。

|错误码|前端行为|
|---|---|
|`DRY_RUN_RETRY_NOT_ALLOWED`|隐藏重试按钮；提示该失败需要修复代码或配置|
|`DRY_RUN_RETRY_EXHAUSTED`|提示已达到重试上限|
|`DRY_RUN_RETRY_IN_PROGRESS`|提示已有重试在执行并等待 `dry-run.updated`；错误体不返回已有子运行 ID，不自行猜测|
|`IDEMPOTENCY_KEY_REQUIRED` / `IDEMPOTENCY_KEY_REUSED`|按通用幂等错误处理，不创建第二次业务重试|

仅以下基础设施失败显示重试：`DRY_RUN_TIMEOUT`、`SANDBOX_WORKER_UNAVAILABLE`、`SANDBOX_WORKER_ERROR`、`GITHUB_API_UNAVAILABLE`、`GIT_STORE_FETCH_FAILED`。`GIT_MERGE_CONFLICT`、`TESTSET_FAILED`、上下文不匹配和配置错误不能通过该按钮解决。

当前没有 Dry Run 历史列表接口。正常 `202` 响应中的新 ID 应写入现有本机会话历史；本地历史只用于恢复页面入口，不是服务端权威状态。

### 14.3 状态与 SSE

状态文案为：`QUEUED` 排队中、`RUNNING` 运行中、`PASSED` 通过、`FAILED` 失败、`CANCELLED` 已取消。不要增加顶层 `CONFLICT` 状态；冲突使用 `GIT_MERGE_CONFLICT` 或 `tests.reason=MERGE_CONFLICT` 展示。

`dry-run.updated` 事件必须解析 `dryRunId/status/headCommit/targetBranch/targetCommit`。事件只触发 Query 失效和重新 GET，不直接拼装报告；重复、乱序和断线重连均以 REST 报告为准。SSE 游标过期时处理 `409 EVENT_CURSOR_EXPIRED`，重连后主动刷新当前报告。

### 14.4 MR_FIRST 和验收

- 任一仓库 Dry Run 未 `PASSED` 时，不显示 MR 已创建；CQ+1 也按仓库单独展示。
- 多仓库分别展示 Dry Run、目标 SHA、CQ 和 MR，不能由一个仓库的通过状态推导全部通过。
- 验收覆盖状态流转、SHA 展示、逐 Testset `message/failureCode`、可重试错误按钮、重复点击幂等、SSE 重复/乱序/断线恢复，以及失败时不误报 MR 创建。

---

## 15. Worker 日志定位与数据库初始化（2026-08-19）

本节是 Worker 执行日志的增量接口契约。完整 Worker 日志不属于前端公开数据；前端只能读取
项目权限范围内的 TaskRun 脱敏日志，后端运维或受控诊断程序才能读取 Worker 的完整详情、
`SYSTEM`、`STDOUT` 和 `STDERR`。

### 15.1 前端可调用接口：TaskRun 统一失败诊断

```http
GET /api/v1/projects/{projectId}/task-runs/{taskRunId}/diagnostics
Authorization: Bearer <user-access-token>
```

该接口是任务失败后的统一查询入口。无论失败发生在调用 Worker 之前、Agent 协议阶段、
Worker 工具执行阶段还是测试阶段，均返回 `taskRunId/status/stage/failure/workerExecutions`。
`failure` 为受控主后端失败归因：`failureCode` 仅能是已发布稳定码，`summary` 仅由该码映射生成，
绝不回显持久化异常原文、第三方 HTTP 响应、账户状态、URL、命令或日志内容；没有调用 Worker 时 `workerExecutions` 返回空数组，不会因为
不存在 `executionId` 而返回空诊断或 404。

```json
{
  "data": {
    "taskRunId": "01...",
    "taskId": "01...",
    "status": "FAILED",
    "stage": "CODING",
    "failure": {
      "code": "EXECUTION_FAILED",
      "failureCode": "CODING_NO_ACTUAL_CHANGE",
      "title": "执行失败",
      "summary": "代码步骤未产生实际文件变更",
      "retryable": true,
      "occurredAt": "2026-08-19T12:22:52Z"
    },
    "workerExecutions": []
  }
}
```

`workerExecutions` 至多返回最新一条状态为 `FAILED` 的 Worker 执行；该项只返回
`executionId/tool/status/exitCode/failureCode/failureSummary` 及时间字段，成功、排队中、执行中和更早失败的
Worker 均不暴露 `executionId`。该关联在主后端收到 Worker 入队回执后立即持久化，不能依赖解析日志文本，因而
即使后续轮询超时或主后端线程中断，也能按 `taskRunId` 找到已经创建的 Worker 执行。

### 15.1.1 Task 级统一失败诊断入口（前端首选）

```http
GET /api/v1/projects/{projectId}/tasks/{taskId}/diagnostics
Authorization: Bearer <user-access-token>
```

该接口是任务失败后的首选查询入口。前端只需已登录的 `projectId` 和 `taskId`，不需要先查询
`taskRunId` 或 `executionId`。响应中的 `failure` 是由稳定失败码派生的受控失败原因，`stage` 表示失败阶段。
历史数据即使曾保存上游原文，也必须在读取时按相同规则覆盖：

```json
{
  "data": {
    "taskId": "01...",
    "status": "FAILED",
    "stage": "CODING",
    "failure": {
      "code": "EXECUTION_FAILED",
      "failureCode": "FILE_PATCH_FAILED",
      "title": "执行失败",
      "summary": "补丁无法应用，请重新读取文件后重试",
      "retryable": true,
      "occurredAt": "2026-08-19T12:22:52Z"
    },
    "latestFailedRun": {
      "taskRunId": "01...",
      "stage": "CODING",
      "workerExecutions": [
        { "executionId": "01...", "tool": "file.patch", "status": "FAILED" }
      ]
    }
  }
}
```

失败发生在 TaskRun 创建前时，`latestFailedRun` 为 `null`，但 `failure` 和 `stage` 仍然返回；这覆盖
Planner、编排启动、交付准备和超时回收失败。前端进入
`/app/projects/{projectId}/tasks/{taskId}` 后自动请求本接口，在失败卡片中展示阶段、稳定失败码和摘要；
只有用户需要查看具体运行时，才调用上一节的 TaskRun 级诊断接口。

### 15.2 前端可调用接口：TaskRun 脱敏日志

```http
GET /api/v1/projects/{projectId}/task-runs/{taskRunId}/logs?cursor=0&limit=100
Authorization: Bearer <user-access-token>
```

项目成员权限和 Task 可见性由服务端校验。`WORKER/*` 条目只包含以下脱敏字段：

```text
executionId=<uuid>，tool=<tool>，status=<status>，exitCode=<number>，failureReason=<safe-summary>
```

`executionId` 用于后端运维定位 Worker 执行；前端不得直接请求 `/internal/v1/**`，也不得展示或
保存 Worker 服务令牌。公开 TaskRun 日志不返回工具参数、Patch、文件内容、原始命令、完整
stdout/stderr、Token、密码、私钥或宿主机路径。历史版本可能写过 `WORKER/STDOUT`、
`WORKER/STDERR`，接口层同样过滤，不向项目成员返回。

### 15.3 受控运维接口：Worker 执行详情与完整日志

以下接口只在 Worker 内网暴露，并且必须携带部署时配置的独立服务令牌：

```http
GET /internal/v1/tool-executions/{executionId}
Authorization: Bearer <SANDBOX_BACKEND_SERVICE_TOKEN>
```

返回 `id`、`sandboxId`、`repositoryId`、`tool`、`status`、`exitCode`、`failureReason`、
`createdAt`、`startedAt`、`finishedAt` 等执行元数据。`arguments` 不通过该响应公开。

```http
GET /internal/v1/tool-executions/{executionId}/logs?after=0&limit=1000
Authorization: Bearer <SANDBOX_BACKEND_SERVICE_TOKEN>
```

响应为 `{ "items": [...], "nextCursor": 12 }`。日志项包含 `sequence`、`stream`、`content`、
`createdAt`；`stream` 取 `SYSTEM`、`STDOUT` 或 `STDERR`。客户端使用 `nextCursor` 作为下一次
`after`，直到游标不再推进。单次 `limit` 服务端限制为 `1..1000`，单条内容最多 16000 字符。

缺少令牌、令牌错误或 Worker 未配置令牌时，接口拒绝请求；未配置返回稳定错误码
`INTERNAL_AUTH_NOT_CONFIGURED`，不得降级为匿名访问。运维审计应记录 `taskRunId`、`executionId`、
操作者和时间，不记录令牌或完整日志正文。

### 15.4 Worker 数据库与启动前置条件

Worker 使用独立数据库 `qgents_sandbox_worker`，不是主后端业务库。执行详情和日志分别保存于：

|表|用途|
|---|---|
|`tool_executions`|工具执行状态、退出码、结构化结果和失败原因|
|`tool_execution_logs`|按执行内 `sequence_no` 保存 `SYSTEM/STDOUT/STDERR` 日志|

首次部署由数据库管理员执行：

```bash
mysql --protocol=tcp -h <mysql-host> -u <admin-user> -p \
  < sandbox-worker/src/main/resources/db/sandbox_worker_database.sql
```

该脚本只创建数据库。Worker 启动时通过 `spring.sql.init` 自动、幂等执行
`sandbox-worker/src/main/resources/db/sandbox_worker_schema.sql` 建表；应用账户不需要全局
`CREATE DATABASE` 权限。若未先创建数据库，Worker 无法启动并不能提供执行日志查询。

### 15.5 前端和运维职责

前端进入 `/app/projects/{projectId}/tasks/{taskId}` 后，由请求客户端自动携带用户 Token 调用
上述 `/api/v1` 契约接口；不能把 `/api/v1` 地址当成页面地址粘贴到浏览器地址栏。失败时先显示
`diagnostics` 返回的主后端失败阶段和摘要；如果 `workerExecutions` 非空，后端支持/运维再使用
其中的 `executionId` 调用 Worker 内网接口。不把 `executionId` 当作可直接访问的公网 URL，
也不把 Worker 服务令牌放入前端。Worker 数据库日志不走 `docker logs`，容器销毁不会删除已落库的执行记录。

---

## 16. 群聊最终 Diff 卡预览（2026-08-20）

群聊中的 `DIFF` 卡只允许展开 Task 级最终 Diff。最终 Diff 的判定以 `diff.reviewBatchId` 关联的
`DiffReviewBatch` 为准；TaskRun 过程 Diff、普通 Diff 或上下文不一致的 Diff 不能在群聊中展开。
TaskStep 和 TaskRun 的执行产物不是群聊 Diff 交付物，前端不得把它们渲染为 Diff 卡。

### 16.1 按需获取卡片预览

```http
GET /api/v1/projects/{projectId}/diffs/{diffId}/preview?fileId={diffFileId}
Authorization: Bearer <user-access-token>
```

- 项目成员可调用；服务端继续依据项目成员身份和 Diff 的 `projectId` 校验，不信任客户端传入的任务、群或仓库 ID。
- `diffId` 来自群消息 `type=DIFF` 的 `content.diffId`。前端只能为群聊里的 DIFF 卡调用本接口。
- `fileId` 可选。不传时选择 Diff 内顺序最早的文件；切换文件时带上响应 `files[].fileId` 重新请求。
- 仅接受关联真实 Task 级 `DiffReviewBatch` 的最终 Diff。该 Diff 必须由批次记录的
  `finalCodingTaskRunId` 产生，且对应 TaskRun/TaskStep 均为同一 Task 的 `DEVELOPER` 执行，TaskRun
  状态必须为 `SUCCEEDED`；普通或中间 Diff 返回 `422 DIFF_PREVIEW_FINAL_ONLY`，批次、Task、
  TaskRun、TaskStep、Workspace 或 Project 关联异常返回 `422 DIFF_PREVIEW_CONTEXT_INVALID`。
- 文件不属于指定 Diff 时返回 `404 DIFF_FILE_NOT_FOUND`。

响应示例：

```json
{
  "data": {
    "diffId": "01...",
    "detailPath": "/app/projects/{projectId}/code/diff/01...",
    "previewLineLimit": 200,
    "totalFileCount": 2,
    "filesTruncated": false,
    "files": [
      {
        "fileId": "01...",
        "sequence": 1,
        "path": "src/auth/LoginController.java",
        "fileName": "LoginController.java",
        "extension": "java",
        "changeType": "MODIFIED",
        "additions": 12,
        "deletions": 3,
        "binary": false
      }
    ],
    "selectedFileId": "01...",
    "totalLineCount": 15,
    "lines": [
      { "type": "DELETE", "oldLineNo": 18, "newLineNo": null, "content": "return oldValue;", "contentTruncated": false },
      { "type": "ADD", "oldLineNo": null, "newLineNo": 18, "content": "return newValue;", "contentTruncated": false }
    ],
    "truncated": false,
    "viewDetailsRequired": false
  },
  "requestId": "req_..."
}
```

`lines[].type` 固定为 `CONTEXT`、`DELETE` 或 `ADD`，`content` 不带 unified diff 的空格、`-` 或 `+`
前缀；前端按 `type` 渲染上下文、红色删除和绿色新增行。二进制文件返回 `binary=true`、空 `lines`，
前端不尝试展示二进制正文。单行 `content` 最多返回 4,000 个 Unicode 字符，超出时截断并设置
`contentTruncated=true`。

### 16.2 卡片容量与前端行为

- 单次所选文件最多返回 **200** 条结构化 Diff 行。若该文件解析行数超过 200，`truncated=true`，
  `lines` 仅含前 200 条，`totalLineCount=201` 表示“至少 201 行”，前端显示“查看详情”；
  `truncated=false` 时 `totalLineCount` 才是准确值。
- 文件标签同样最多返回前 100 项；当 `filesTruncated=true` 时，前端显示“查看详情”，不伪造未返回的文件标签。
- `filesTruncated=true` 时，只能切换响应 `files[]` 中已返回的文件；传入第 101 个及之后文件的 `fileId`
  返回 `422 DIFF_PREVIEW_FILE_LIMIT`，前端直接跳转 `detailPath`。
- `viewDetailsRequired = truncated || filesTruncated || lines[].contentTruncated`，是前端显示“查看详情”的唯一机器判断；详情跳转到
  `detailPath`，即 `/app/projects/{projectId}/code/diff/{diffId}`。这是前端页面路由；后端 API 仍以
  `/api/v1` 开头。
- 未截断时，前端仍可将“查看详情”作为普通次级入口，但不能因为显示了 200 行而猜测完整内容已加载。
- 群消息分页、增量拉取和 `message.updated` 的行为不变。卡片折叠时不请求预览；用户展开卡片或切换文件时才调用本接口，避免把 Diff 内容塞进群消息列表响应。

### 16.3 前端实施边界

1. 从已加载的 `DIFF` 消息读取 `content.diffId`，展开时请求本节接口；不要把 Diff 行或文件数组写回消息内容。
2. 使用 `files[].fileName` 作为文件标签文本，`extension` 可作为后缀样式或图标判断；保留 `path` 供同名文件区分。
3. 点击 `files[]` 中另一文件时，以它的 `fileId` 重请求本接口，使用返回的 `selectedFileId` 和 `lines` 替换当前预览。
4. `viewDetailsRequired=true` 时显示“查看详情”，并跳转 `detailPath`；不要继续请求更多行、拼接分页或尝试从 TaskRun 日志恢复完整 Diff。
5. 接口返回 `DIFF_PREVIEW_FINAL_ONLY`、`DIFF_PREVIEW_CONTEXT_INVALID` 或 `DIFF_PREVIEW_FILE_LIMIT` 时关闭卡片预览或直接跳转 `detailPath`；不得退化展示任何内部执行产物。

---

## 17. 质量打回后的执行步骤状态（2026-08-20）

当 TESTING 或 REVIEWING 的一次运行以 `FAILED_QUALITY` 结束，且结构化结果声明
`needsCodingFix=true`、状态机实际决定 `REQUEUE_CODING` 时，后端会进入质量修复闭环。

### 17.1 步骤状态规则

- 从实际回退的最后一个 `MUTATE` 开发步骤开始，到本次流程末尾的所有会重新执行的
  `TaskStep`，统一更新为 `PENDING`；随后开发步骤真正开始执行时再更新为 `RUNNING`。
- 因此 Developer 修复期间，前端应显示例如「Developer：执行中；Tester：待执行；Reviewer：待执行」，
  不得继续把旧代码版本留下的 Test/Review 成功或失败当成当前结果。
- Review 打回时，之前成功的 Test 也会置为 `PENDING`，因为修改代码后必须重新测试。
- `TaskRun`、运行日志与执行产物保持不可变：旧的失败/成功 Run 仍可供诊断，只有 TaskStep 的
  当前展示状态会被重置。

### 17.2 边界与前端行为

- `needsCodingFix=false`、没有可写 `MUTATE` 步骤、质量循环耗尽，或状态机未决定
  `REQUEUE_CODING` 时，不重置步骤状态，保留真实的最终失败结果。
- 不新增接口、字段或状态枚举。本规则作用于既有任务详情/步骤查询响应及
  `task-step.updated` SSE 事件。
- 前端只以服务端返回的 `TaskStep.status` 呈现当前流程状态；可以通过历史 TaskRun 查看上次
  测试或审查失败的具体诊断，但不能用旧 Run 的终态覆盖新的 Step 状态。

## 18. 失败 Run 诊断与执行产物摘要（2026-08-21）

每个 `FAILED`、`FAILED_QUALITY` 或 `FAILED_INFRASTRUCTURE` 的 TaskRun 都必须先持久化一条
不可变的 `task_run_failure_diagnostics` 记录，再持久化 Run 执行产物并发布终态事件。记录必须关联
Task、TaskRun、TaskStep、phase、role 与 executionMode；重试创建新 Run 和新诊断，旧记录不得覆盖。

- `task_run_failure_diagnostics` 是受限后端诊断事实，不通过项目成员公开接口直接返回。它保存稳定公开码、
  内部归一化码、来源、异常类型和限长脱敏结构化上下文。
- TESTING 上下文可包含验证方式、exitCode、是否需要 Coding 修复、失败项数量及限长的
  `name/reason/severity`；不得保存原始命令、stdout、stderr、环境变量、凭据、宿主机路径或异常堆栈。
- `task_execution_artifacts.summary` 仍是项目成员可见的受控摘要。测试失败时可包含
  `testFailure.verificationMode/exitCode/needsCodingFix/failureCount/failures[]`，所有文本均经过脱敏与长度限制。
  `failureCode=PROCESS_EXIT_NONZERO` 时 `message` 固定为“工具进程执行失败”。
- 无法归类的普通失败对外使用稳定 `failureCode=EXECUTION_FAILED`，不返回内部异常原文；基础设施失败继续使用
  已发布的基础设施失败码。
