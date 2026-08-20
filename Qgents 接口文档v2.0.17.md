# Qgents 接口文档v2\.0\.17

# Qgents 接口文档v2\.0\.17



**版本**：v2\.0\.17



**状态**：新增§38，修补§12\.4



**更新日期**：`2026-08-``19`



---



## 1\. 范围



本文定义 Qgents 的账号、团队、项目、群聊、Skill、Memory、Agent、Task、Workspace、受控执行、Diff、测试与 MR 接口。



本轮 P0 闭环为：



```Plain Text
需求群 -> 创建 Task -> Planner 写入 TaskStep -> TaskRun 执行 -> 查看 Diff / MR
```







- 当前执行模型唯一为 `Task -> TaskStep -> TaskRun`；`orchestrationRun`、`workPackage`、`Deliverable`、`TaskDelivery` 等旧模型不属于当前版本。

    

    

    

- 项目总群与需求群统一为 Group，使用 `PROJECT_MAIN` 与 `REQUIREMENT` 区分。

    

    

    

- 客户端只能发起受控执行、读取状态和查看产物；不得直接操作 Workspace、Sandbox、Git 凭据或宿主机文件系统。

    

    

    

- 当前分支已经包含 Task、Workspace repository、TaskRun、Diff、Test Run、Dry Run 和 MR 镜像的持久模型；真实 Git commit/push 与 GitHub PR 接缝已接入受控执行服务，具体状态以交付字段为准。

    

    

    

- 通知中心已按用户维度持久化（§7\\\.1）；离线推送、会话个人偏好（未读数/置顶）、搜索和分支查询不在本轮范围；Test Run / Dry Run 历史列表见第 38 节。

---







## 2\. 通用约定







**基础地址**：`https://`[`api.qgents.dpdns.org`](https://api.qgents.dpdns.org/)`/api/v1`







**请求和响应**：均为 JSON，时间为 UTC RFC 3339，所有资源 ID 为 UUID。







除注册、登录、刷新 Token、重置密码和 GitHub App 回调外，均需在请求头中携带：



```HTTP
Authorization: Bearer <accessToken>
```







**成功响应**统一为：



```JSON
{
  "data": {},
  "requestId": "req_01J..."
}
```







**失败响应**统一为：



```JSON
{
  "error": {
    "code": "PROJECT_ADMIN_REQUIRED",
    "message": "需要项目 Admin 权限",
    "details": [{"field": "role", "reason": "PROJECT_MEMBER"}]
  },
  "requestId": "req_01J..."
}
```







**HTTP 状态码**：







- `400`：参数错误

    

    

    

- `401`：未认证或 Token 失效

    

    

    

- `403`：权限不足

    

    

    

- `404`：资源不存在或不可见

    

    

    

- `409`：幂等/状态冲突

    

    

    

- `422`：业务校验失败

    

    

    

- `429`：限流

    

    

    

- `500`：服务异常

    

    

    

**幂等性**：







- 写操作必须支持 `Idempotency-Key` 头。

    

    

    

- 同一用户在 24 小时内重复提交相同键与请求体时，返回首次结果。

    

    

    

- 相同键但请求体不同时，返回 `409 IDEMPOTENCY_KEY_REUSED`。

    

    

    

**列表分页**：







- 使用 `cursor`、`limit`，默认 `30`、最大 `100`。

    

    

    

- 响应格式：

    

```JSON
{
    "data": [],
    "page": {"nextCursor": "cursor_...", "hasMore": true},
    "requestId": "req_..."
}
```







---







## 3\. 权限与状态







### 3\.1 角色







|角色|作用域|主要权限|
|---|---|---|
|`TEAM_OWNER`|团队|创建/归档项目；邀请、移除团队成员；管理团队级 GitHub App 集成；跨项目兜底管理|
|`TEAM_MEMBER`|团队|仅可访问被添加的项目|
|`PROJECT_ADMIN`|项目|管理项目成员（含其他 `PROJECT_ADMIN`）；绑定仓库；配置分支策略、Testset、门禁；审核 Skill/Memory；合并 MR|
|`PROJECT_MEMBER`|项目|参与需求群聊；创建 Skill/Memory 草稿；使用已发布 Skill 和已批准 Memory；查看项目内配置和 MR 状态|







**注意**：







- 服务端从 Token 和资源归属判断权限，客户端传入的 `userId`、`role` 不得作为授权依据。

    

    

    

- Project Admin 只能将已在项目中的成员提升为 Project Admin。

    

    

    

- 最后一名 Project Admin 不能被移除或降级。

    

    

    

- Project Admin 不能直接邀请团队外用户，团队邀请仅由 Team Owner 完成。

    

    

    

### 3\.2 状态枚举







|资源|状态与约束|
|---|---|
|团队邀请|`PENDING -> ACCEPTED / REVOKED / EXPIRED`|
|项目|`ACTIVE -> ARCHIVED`|
|Skill|`DRAFT -> PENDING_REVIEW -> PUBLISHED / REJECTED / ARCHIVED`|
|Memory|`DRAFT -> PENDING_REVIEW -> APPROVED / REJECTED / ARCHIVED`|
|Testset|`ENABLED / DISABLED`|
|质量检查|`PENDING / RUNNING / PASSED / FAILED`|
|Group|`PROJECT_MAIN` 或 `REQUIREMENT`；仅 `REQUIREMENT` 可归档，`PROJECT_MAIN` 永远保持 `ACTIVE`|
|Task|`PLANNING -> PENDING -> RUNNING -> WAITING_DIFF_CONFIRMATION -> DELIVERING -> SUCCEEDED / DELIVERY_FAILED / FAILED`；取消时 `RUNNING -> CANCELLING -> CANCELLED`|
|TaskStep|`PENDING -> RUNNING -> SUCCEEDED / FAILED / SKIPPED`|
|TaskRun|`QUEUED -> RUNNING -> SUCCEEDED / FAILED`；可进入 `WAITING_INPUT`、`WAITING_APPROVAL`、`BLOCKED`；取消时 `RUNNING -> CANCELLING -> CANCELLED`|
|Diff|`PENDING_REVIEW -> ACCEPTED / REJECTED`|
|试运行|`QUEUED -> RUNNING -> PASSED / FAILED / CANCELLED`|







---







## 4\. 认证与账户



|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/auth/register`|匿名|邮箱注册平台账号|
|`POST`|`/auth/login`|匿名|邮箱密码登录|
|`POST`|`/auth/refresh`|匿名|轮换 refresh token|
|`POST`|`/auth/logout`|登录用户|使当前 refresh token 失效|
|`POST`|`/auth/password-reset-requests`|匿名|发起找回密码邮件|
|`POST`|`/auth/password-resets`|匿名|使用重置令牌设置新密码|
|`GET`|`/me`|登录用户|当前账户、团队和项目角色摘要|
|`PATCH`|`/me`|登录用户|修改昵称和头像|
|`POST`|`/me/avatar/credential`|登录用户|签发头像直传凭证（OSS 未启用时 501）|
|`POST`|`/me/avatar/confirm`|登录用户|确认头像上传并返回公共读长期 URL（OSS 未启用时 501）|







**头像上传流程**：用户选本地图片 → `POST /me/avatar/credential`（body `{mediaType, sizeBytes}`，`mediaType` 必须 `image/*`、大小 ≤5MB）签发直传凭证 → 客户端用返回的 `uploadUrl` 直传 OSS → `POST /me/avatar/confirm`（body `{objectKey}` 原样回传）确认识别。对象键由服务端生成 `avatars/{userId}/{uuid}.{ext}`（扩展名白名单 jpg/jpeg/png/webp），客户端不得自造；confirm 校验对象属于当前用户且已真实上传，写入 `users.avatar_url` 并返回**长期稳定、公共可读**的头像 URL（各上下文通用，不依赖项目鉴权）。OSS 未启用（本地/CI）时两端点返回 `501 AVATAR_STORAGE_NOT_CONFIGURED`，前端应隐藏/提示「头像上传暂不可用」。头像桶（或 `avatars/` 前缀）需设为公共可读，并配置环境变量 `ALIYUN_OSS_PUBLIC_BASE_URL` 作为公共读基础 URL。`PATCH /me` 的 `avatarUrl` 仍接受任意 http$s$ URL（兼容既有客户端），新增头像上传是推荐入口。











**注册请求示例**：



```JSON
{
  "email": "member@example.com",
  "passwordKeyId": "rsa-2026-08",
  "password": "Base64(RSA-PKCS1-v1_5(password))",
  "displayName": "Lin"
}
```







**登录响应示例**：



```JSON
{
  "data": {
    "accessToken": "eyJ...",
    "accessTokenExpiresIn": 900,
    "refreshToken": "rt_...",
    "refreshTokenExpiresIn": 2592000,
    "user": {
      "id": "user-uuid",
      "email": "member@example.com",
      "displayName": "Lin"
    }
  }
}
```







### 4\.1 密码传输与存储







- 当前演示环境注册、登录和重置密码请求中的 `password`、`newPassword` 必须为前端使用平台 RSA 公钥加密后的 Base64 密文。

    

    

    

- 账号、邮箱等非敏感标识可保持明文。

    

    

    

- 客户端不得自行生成密钥。

    

    

    

- 服务端使用与 `keyId` 对应的私钥解密，解密失败、`keyId` 不存在或明文提交均返回 `400 INVALID_ENCRYPTED_PASSWORD`。

    

    

    

- 解密后的明文仅在内存中用于复杂度校验和密码验证，随后使用 Argon2id 或 BCrypt 加盐哈希后存储。

    

    

    

- 密码、私钥、重置令牌和 Token 不得写入日志、群聊内容或后续 Agent 上下文。

    

    

    

- GitHub OAuth 登录不属于本期必需能力。

    

    

    

**安全提醒**：RSA 加密仅降低演示环境中密码明文直接出现在请求体或普通网络日志中的风险，不能替代 HTTPS。部署具备域名与证书条件后，必须迁移到 HTTPS，并保留服务端密码哈希。







---







## 5\. 团队、项目与成员







### 5\.1 团队与邀请







|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/teams`|登录用户|创建团队，创建者成为 `TEAM_OWNER`|
|`GET`|`/teams`|登录用户|获取我加入的团队|
|`GET`/`PATCH`|`/teams/{teamId}`|团队成员/Team Owner|获取/修改团队资料|
|`GET`|`/teams/{teamId}/members`|团队成员|团队成员列表|
|`POST`|`/teams/{teamId}/invitations`|Team Owner|按邮箱创建团队邀请|
|`GET`|`/teams/{teamId}/invitations`|Team Owner|查询邀请状态|
|`GET`|`/team-invitations`|登录用户|获取我收到的待处理团队邀请（分页，不返回明文 token）|
|`POST`|`/team-invitations/{reference}/accept`|登录用户|接受邀请并加入团队（`reference` 为邀请 id 或明文 token）|
|`DELETE`|`/teams/{teamId}/invitations/{invitationId}`|Team Owner|撤销未接受邀请|
|`PATCH`|`/teams/{teamId}/members/{userId}`|Team Owner|调整团队角色|
|`DELETE`|`/teams/{teamId}/members/{userId}`|Team Owner|移除团队成员|
|`GET`|`/teams/{teamId}/activities`|团队成员|团队最近动态（分页，覆盖最近 24 小时）|







**邀请请求示例**：



```JSON
{
  "email": "new-member@example.com",
  "role": "TEAM_MEMBER",
  "expiresInDays": 7
}
```







受邀邮箱尚未注册时，系统发送注册链接和邀请令牌；用户以相同邮箱注册后可接受邀请。



**创建邀请错误**：`400 INVALID_ARGUMENT`（`role` 仅支持 `TEAM_MEMBER`；`expiresInDays` 不能超过 30 天）、`403 TEAM_OWNER_REQUIRED`（非 Team Owner）、`409 ALREADY_TEAM_MEMBER`（该邮箱对应用户已是团队成员）、`409 INVITATION_ALREADY_PENDING`（该邮箱在本团队已有待处理邀请；需重新邀请时先调用撤销接口）。



**撤销邀请错误**：`404 NOT_FOUND`（邀请不存在）、`409 INVITATION_EXPIRED`（邀请已过期，后端先置 `EXPIRED` 再返回）、`409 INVITATION_NOT_PENDING`（邀请已被接受或已撤销，仅能撤销待处理邀请）。







### 5\.2 项目与项目成员







|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/teams/{teamId}/projects`|Team Owner|创建项目|
|`GET`|`/teams/{teamId}/projects`|团队成员|仅返回我有权限访问的项目|
|`GET`/`PATCH`|`/projects/{projectId}`|项目成员/`PROJECT_ADMIN`|获取/修改项目资料|
|`POST`|`/projects/{projectId}/archive`|Team Owner 或 Project Admin|归档项目|
|`POST`|`/projects/{projectId}/restore`|Team Owner 或 Project Admin|恢复项目|
|`GET`|`/projects/{projectId}/members`|项目成员|项目成员与角色|
|`POST`|`/projects/{projectId}/members`|Project Admin|将现有团队成员加入项目|
|`PATCH`|`/projects/{projectId}/members/{userId}`|Project Admin|在 `PROJECT_MEMBER`/`PROJECT_ADMIN` 间调整|
|`DELETE`|`/projects/{projectId}/members/{userId}`|Project Admin|从项目移除成员\<br\>|







**创建项目请求示例**：



```JSON
{
  "name": "Qgents Web",
  "description": "Web client",
  "memberIds": ["user-uuid"]
}
```







添加项目成员时 `userId` 必须已属于该团队。项目创建者自动成为 `PROJECT_ADMIN`；Team Owner 对本团队项目具有兜底管理权限。







---







## 6\. GitHub App 与项目仓库







GitHub App 授权是团队级代码访问授权，不等同于 Qgents 的成员角色。仓库无需属于 `PROJECT_ADMIN`；只要组织/仓库管理员已为团队安装并授权 Qgents GitHub App，`PROJECT_ADMIN` 即可绑定该仓库。







GitHub 集成使用三层不同 ID：







|资源|字段|实际含义|使用位置|
|---|---|---|---|
|Installation|`id`|`github_installations.id`，Qgents 本地 UUID|绑定请求、同步路径、按安装筛选|
|Installation|`providerInstallationId`|GitHub Installation 数字 ID|仅展示或排查|
|Repository|`id`|`github_repositories.id`，Qgents 本地 UUID|绑定请求中的 `repositoryId`|
|Repository|`providerRepositoryId`|GitHub Repository 数字 ID|仅展示或排查|
|ProjectRepository|`id`|`project_repositories.id`，项目仓库绑定 UUID|PATCH、DELETE，以及 Task/Workspace/Diff/MR 等开发链路|



绑定请求不得传 `providerInstallationId` 或 `providerRepositoryId`。绑定成功后，下游接口中名为 `repositoryId` / `repositoryIds` 的字段均表示 `project_repositories.id`。



|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/teams/{teamId}/integrations/github/installations`|Team Owner|生成 GitHub App 安装跳转地址；可通过 `client` 指定回跳端|
|`GET`|`/integrations/github/callback`|GitHub/浏览器|接收安装或授权回调|
|`GET`|`/teams/{teamId}/integrations/github/installations`|Team Owner|团队已安装的 GitHub App 列表|
|`DELETE`|`/teams/{teamId}/integrations/github/installations/{installationId}`|Team Owner|解除团队安装记录|
|`POST`|`/teams/{teamId}/integrations/github/installations/{installationId}/sync`|Team Owner|手动刷新 Installation 与授权仓库元数据|
|`GET`|`/teams/{teamId}/integrations/github/repositories`|Team Owner 或 Project Admin|查询该团队被授权的仓库\<br\>|
|`GET`|`/projects/{projectId}/repositories`|项目成员|获取项目已绑定仓库|
|`POST`\<br\>|`/projects/{projectId}/repositories`|Project Admin|绑定团队已授权仓库|
|`PATCH`|`/projects/{projectId}/repositories/{projectRepositoryId}`|Project Admin|保留的项目绑定更新接口；第一版前端不提供修改默认分支入口|
|`DELETE`|`/projects/{projectId}/repositories/{projectRepositoryId}`|Project Admin|解绑仓库，成功返回 `204`|







### GitHub 枚举与状态



```Plain Text
Installation.status = ACTIVE | SUSPENDED | DELETED
Installation.accountType = USER | ORGANIZATION
Repository.visibility = PUBLIC | PRIVATE | INTERNAL
Repository.authorizationStatus = AUTHORIZED | REVOKED
```







- Installation 不使用 `EXPIRED`；短期 Installation Token 过期不代表 App Installation 过期。

    

    

    

- `archived=true` 表示 GitHub 仓库已归档；`authorizationStatus=REVOKED` 表示 GitHub App 已无权访问。

    

    

    

- 第一版不返回 `authorizedRepositoryCount`，客户端可按授权仓库的 `installationId` 统计。

    

    ### 安装与元数据刷新

    

    发起安装请求必须携带 `Idempotency-Key`。

    

    **安装入口固定为 GitHub \*\*\*\*****`/installations/new`**：接口无条件返回

    

    `https://github.com/apps/{slug}/installations/new?state={签名 state}`，

    

    不查询本地已有 Installation、不根据账号状态改跳 GitHub Configure 或 settings 路径。

    

    若该账号已安装，由 GitHub 自身提示；后端仍返回 `/new`，`state` 继续携带当前 teamId、发起人、client，回调语义不变。

    

    前端只跳转 `data.installationUrl`，不自行拼接 GitHub 链接。

    

    安装请求通过查询参数指定发起端，`client` 只允许以下值：

    

    \|    参数\|    类型\|    必填\|    说明\|

    

    \|\-\-\-\|\-\-\-\|\-\-\-\|\-\-\-\|

    

    \|    `client`\|    `WEB` 或 `MOBILE`\|    否\|    回调成功后的前端类型，省略时默认为 `WEB`\|

    

    Web 端请求：

    

    `HTTP     POST /teams/{teamId}/integrations/github/installations?client=WEB     Idempotency-Key: <unique-key>     Authorization: Bearer <accessToken>     `

    

    移动端请求：

    

    `HTTP     POST /teams/{teamId}/integrations/github/installations?client=MOBILE     Idempotency-Key: <unique-key>     Authorization: Bearer <accessToken>     `

    

    `client` 不是回跳 URL，前端不得传入任意 URL。后端会将该值写入签名 `state`，GitHub 回调时由后端验证并决定回跳地址。

    

    成功返回：

    

    `JSON     {       "data": {         "installationUrl": "https://github.com/apps/qgents/installations/new?state=...",         "expiresAt": "2026-08-13T11:00:00Z"       },       "requestId": "req_01J..."     }     `

    

    Installation 列表项：

    

    `JSON     {       "id": "installation-local-uuid",       "providerInstallationId": 12345678,       "accountLogin": "Yjingwen-svg",       "accountType": "ORGANIZATION",       "status": "ACTIVE",       "installedAt": "2026-08-01T08:00:00Z",       "metadataSyncedAt": "2026-08-13T10:00:00Z"     }     `

    

    callback 固定为：

    

    `HTTP     GET /integrations/github/callback?installation_id=...&setup_action=...&state=...     `

    

    其中 `setup_action` 由 GitHub 回调携带，常见值为 `install` 或 `update`，后端不要求前端额外处理。

    

    后端校验签名 `state`，保存 Installation 并刷新授权仓库元数据。`state` 中包含客户端类型，例如：

    

    `JSON     {       "sub": "<teamId>",       "client": "WEB"     }     `

    

    旧版本没有 `client` 字段的 `state` 默认按 `WEB` 处理。

    

    成功返回：

    

    `HTTP     302 Location: {FRONTEND_URL_WEB}/app/integrations/github?teamId={teamId}&installed=1     `

    

    当安装请求使用 `client=MOBILE` 时，回跳地址为：

    

    `HTTP     302 Location: {FRONTEND_URL_MOBILE}/app/integrations/github?teamId={teamId}&installed=1     `

    

    **归属冲突回跳**：同一 GitHub 账号已绑定到其他团队时，回调不保存本次安装，回跳地址携带 `conflict` 与 `message` 参数（WEB/MOBILE 均同构）：

    

    `HTTP     302 Location: {FRONTEND_URL_WEB}/app/integrations/github?teamId={teamId}&installed=0&conflict=GITHUB_INSTALLATION_TEAM_CONFLICT&message={URL编码的中文提示}     `

    

    前端应检测 `conflict` 参数并展示 `message` 中的提示，然后清理该参数；`installed` 为 `0` 表示安装未完成。

    

    \> **业务规则：一个 GitHub 账号只能授权给一个团队。** GitHub App 的 Installation 是「账号对 App」级别唯一的，一个 GitHub 账号对 qgents App 只有一个安装，因此只能归属一个团队。若该账号已绑定其他团队，从当前团队发起安装时会进入已有的 Installation（GitHub 侧不会新建），无法重复绑定；如需更换团队，须先到原团队解绑或卸载 GitHub App 后重新安装。仓库变更 webhook 不携带来源团队信息，故「在 GitHub 配置页直接增删仓库」作用于原 Installation，后端无法据 webhook 阻断该操作，前端应在安装入口提示该规则。

    

    \>

    

    \>

    

    后端部署配置：

    

    `Plain Text     FRONTEND_URL_WEB=https://qgents.dpdns.org     FRONTEND_URL_MOBILE=https://mobile.qgents.dpdns.org     `

    

    Web 和移动端共用同一个 GitHub App 和同一个 callback 地址：

    

    `Plain Text     https://api.qgents.dpdns.org/api/v1/integrations/github/callback     `

    

    区别只在于创建安装链接时传递的 `client` 参数。

    

    手动刷新请求中的 `{installationId}` 是 Installation 本地 `id`。该操作只刷新 GitHub Installation 与授权仓库元数据，不执行 clone/fetch，也不改变 Workspace 状态；成功返回 `200` 和刷新后的 Installation 对象。客户端随后重新 GET Installation 列表和授权仓库列表。

    

    解除团队 Installation 成功返回 `204 No Content`；仍被项目仓库绑定引用时返回 `409 GITHUB_INSTALLATION_IN_USE`。

    

    **解除团队关联前需先解绑相关项目仓库；存在引用时后端返回 ****`409 GITHUB_INSTALLATION_IN_USE`****，不自动解绑、不删除项目历史。**

    

    解除关联是「解除 Qgents 团队关联」，不是替用户去 GitHub 远程卸载 App；后端不会调用 GitHub 卸载接口。

    

    无项目绑定引用的 Installation：后端会先删除其未绑定的仓库镜像，再删除安装记录。

    

    

    

    

    

### 授权仓库







授权仓库列表项：



```JSON
{
  "id": "repository-local-uuid",
  "installationId": "installation-local-uuid",
  "providerRepositoryId": 987654321,
  "fullName": "Yjingwen-svg/qgents-web",
  "githubUrl": "https://github.com/Yjingwen-svg/qgents-web",
  "defaultBranch": "main",
  "visibility": "PRIVATE",
  "archived": false,
  "authorizationStatus": "AUTHORIZED",
  "metadataSyncedAt": "2026-08-13T10:00:00Z"
}
```







客户端只允许绑定 `authorizationStatus=AUTHORIZED`、`archived=false`、`defaultBranch` 非空且对应 Installation 为 `ACTIVE` 的仓库。`defaultBranch` 缺失时不得回退为固定的 `main`。







### 项目仓库绑定



项目仓库绑定列表项及绑定成功响应：







```JSON
{
  "id": "project-repository-binding-uuid",
  "repositoryId": "repository-local-uuid",
  "installationId": "installation-local-uuid",
  "providerRepositoryId": 987654321,
  "fullName": "Yjingwen-svg/qgents-web",
  "githubUrl": "https://github.com/Yjingwen-svg/qgents-web",
  "defaultBranch": "main",
  "displayName": "qgents-web",
  "authorizationStatus": "AUTHORIZED",
  "metadataSyncedAt": "2026-08-13T10:00:00Z",
  "boundAt": "2026-08-13T10:00:00Z"
}
```



项目绑定 DTO 不返回代码含义的 `syncStatus`、`lastSyncedAt` 或 `syncError`。项目上下文已由路径确定，不重复返回 `boundProjectId` / `boundProjectName`。第一版不提供按授权仓库批量反查所有已绑定项目的接口。



列表默认只返回 `ACTIVE`（生效中）绑定；软解绑（`UNBOUND`）的绑定不出现在列表中。



绑定仓库请求：



一个项目可绑定多个仓库，前端如需批量绑定，可循环调用本接口，每次传入不同的 repositoryId 即可。







```JSON
{
  "installationId": "installation-local-uuid",
  "repositoryId": "repository-local-uuid",
  "defaultBranch": "main",
  "displayName": "qgents-web"
}
```







`defaultBranch` 是可选兼容字段。后端始终以授权仓库元数据中的真实默认分支为可信来源，忽略客户端覆盖值；真实默认分支缺失时拒绝绑定。成功返回 `200` 和完整 ProjectRepository 对象，其中 `id` 是后续 PATCH、DELETE 和 Task 创建使用的项目仓库绑定 ID。







软解绑后重新绑定：同项目、同授权仓库若已存在 `UNBOUND` 绑定记录，重新绑定会将该记录恢复为 `ACTIVE` 并复用原 `project_repositories.id`（保留历史 Task/Workspace/Diff/MR 关联），而不是新建记录。若已存在 `ACTIVE` 绑定，仍返回 `409 PROJECT_REPOSITORY_ALREADY_BOUND`。



PATCH 与 DELETE 路径中的 `{projectRepositoryId}` 均为 ProjectRepository 响应的 `id`，不是授权仓库 `repositoryId` 或 GitHub 数字 ID。第一版前端不调用 PATCH 修改默认分支。



DELETE 采用软解绑：不物理删除绑定记录，仅标记为 `UNBOUND`，保留历史 Task/Workspace/Diff/MR/分支配置等关联。成功返回 `204 No Content`，客户端随后重新 GET 项目绑定列表。重复解绑（已 `UNBOUND`）幂等，仍返回 `204`。若该绑定正被进行中的任务使用，返回 `409 PROJECT_REPOSITORY_IN_USE`（该活动占用校验待后端接入后生效）。







### GitHub 写接口幂等与主要错误







生成安装链接、删除 Installation、手动刷新、绑定项目仓库、PATCH 项目仓库和解绑项目仓库均要求 `Idempotency-Key`。callback 不要求前端传该请求头。







|HTTP|错误码|含义|
|---|---|---|
|`400`|`INVALID_GITHUB_INSTALLATION_STATE`|callback state 无效或过期|
|`400`|`INVALID_REQUEST`|`client` 不是 `WEB` 或 `MOBILE`|
|`400`|`IDEMPOTENCY_KEY_REQUIRED`|缺少幂等键|
|`403`|`GITHUB_REPOSITORY_ACCESS_DENIED`|无团队或项目操作权限|
|`404`|`GITHUB_RESOURCE_NOT_FOUND`|Installation、仓库或项目绑定不存在|
|`409`|`GITHUB_INSTALLATION_IN_USE`|Installation 仍被项目绑定引用|
|`409`|`GITHUB_INSTALLATION_TEAM_CONFLICT`|Installation 已关联其他 Team|
|`409`|`PROJECT_REPOSITORY_ALREADY_BOUND`|项目已绑定该仓库|
|`409`|`PROJECT_REPOSITORY_REFERENCED_BY_BRANCH_CONFIG`|项目绑定仍被分支配置引用|
|`409`|`IDEMPOTENCY_KEY_REUSED`|同一幂等键用于不同请求|
|`422`|`REPOSITORY_NOT_AUTHORIZED_FOR_PROJECT`|仓库不在项目所属 Team 的有效授权范围|
|`502`|`GITHUB_API_UNAVAILABLE`|GitHub API 暂时不可用|







callback 只保存 Installation 元数据和授权仓库范围。GitHub App 私钥、安装访问令牌和用户 PAT 永不通过前端 API 返回；后续代码操作必须使用服务端受控 Git 服务或短期权限，凭据不得进入前端、Agent、日志或 Workspace 配置。







### 6\.1 分支策略与质量门禁

|方法|路径|权限|说明|
|---|---|---|---|
|`GET`/`PUT`|`/projects/{projectId}/repositories/{projectRepositoryId}/branch-policies/{branch}`|项目成员/Project Admin|查询/配置受保护分支策略|
|`GET`/`PUT`|`/projects/{projectId}/repositories/{projectRepositoryId}/quality-gates/{branch}`|项目成员/Project Admin|查询/配置目标分支的门禁|
|**质量门禁示例**：||||



```JSON
{
  "requirePullRequest": true,
  "requiredChecks": ["TESTSET", "AI_REVIEW", "DRY_RUN", "CQ_PLUS_ONE"],
  "requiredTestsetIds": ["testset-uuid"],
  "minimumHumanApprovals": 1,
  "allowDirectPush": false
}
```







`requiredTestsetIds` 是不可被普通成员绕过的强制测试集。门禁规则定义期望条件，检查的实际运行和回写由后续执行/同步服务处理。



`AI_REVIEW` 可由该 Task 的结构化 `REVIEWING` 产物写入质量检查结果；产物缺失时不得伪造通过或失败结果。



`DRY_RUN` 必须来自真实的沙箱合并预演。创建 MR 时，服务端会在短事务内再次核验同一源/目标提交上的已通过 Dry Run 与独立 CQ+1，并将这两项真实预检事实投影为 `quality_check_results`（`source` 分别为 `PREFLIGHT_DRY_RUN:<dryRunId>`、`PREFLIGHT_CQ_PLUS_ONE:<cqReviewId>`），供 MR 门禁列表展示和汇总。GitHub 的 `mergeable`、`mergeableState`、PR 是否存在或远端分支是否可推送，都不是 Dry Run 结果，不能写入 `quality_check_results.checkType=DRY_RUN`。

`TESTSET` 由 MR 前 Dry Run 的真实执行强制校验，不额外伪造 MR 级 `TESTSET` 检查；`DRY_RUN`、`AI_REVIEW` 与 `CQ_PLUS_ONE` 均以各自真实执行或审批事实参与门禁汇总。未配置的检查保持无检查项，未执行的必选检查保持未通过，不得伪造 `PASSED`。

### 6\.2 项目工作分支视图



#### 查询项目工作分支







```HTTP
GET /api/v1/projects/{projectId}/work-branches
```







权限：项目成员。







查询参数：







|参数|类型|必填|说明|
|---|---|---|---|
|`repositoryId`|UUID|否|`project_repositories.id`。传入不属于当前项目的 ID 返回 `404 REPOSITORY_NOT_FOUND`。|
|`requirementGroupId`|UUID|否|REQUIREMENT Group UUID。传入不存在、非当前项目或非 REQUIREMENT 的 Group 返回 `404 REQUIREMENT_GROUP_NOT_FOUND`。未传返回全部；传入时仅返回存在关联 Task 属于该群的工作分支。|
|`cursor`|string|否|上一页返回的 `page.nextCursor`。失效或格式非法返回 `400 INVALID_CURSOR`。|
|`limit`|integer|否|默认 `20`，最大 `100`。|







成功响应使用统一游标分页外壳：







```JSON
{
  "data": [
    {
      "projectRepositoryId": "project-repository-binding-uuid",
      "name": "feat/login-api",
      "workspaceId": "workspace-uuid",
      "lastKnownHead": "a1b2c3d",
      "latestTask": {
        "id": "task-uuid",
        "displayCode": "T-1024",
        "title": "登录接口开发",
        "updatedAt": "2026-08-17T12:00:00Z"
      },
      "requirementGroups": [
        { "id": "group-uuid", "title": "登录功能" }
      ],
      "latestDiff": {
        "id": "diff-uuid",
        "taskId": "task-uuid",
        "status": "PENDING_REVIEW",
        "changeStats": { "additions": 230, "deletions": 12 },
        "createdAt": "2026-08-17T12:00:00Z"
      },
      "openMergeRequest": {
        "id": "merge-request-uuid",
        "number": 42,
        "status": "OPEN"
      },
      "lastVerification": {
        "kind": "TEST_RUN",
        "status": "PASSED",
        "commitSha": "a1b2c3d",
        "completedAt": "2026-08-17T12:05:00Z"
      }
    }
  ],
  "page": { "nextCursor": null, "hasMore": false },
  "requestId": "req_..."
}
```







字段约束：







- 行的逻辑唯一键是 `projectRepositoryId + name`，不新增虚构的“分支记录 UUID”。

    

- `latestTask` 是最近关联任务，不是分支唯一所有者。

    

- `requirementGroups` 是关联 Task 的需求群集合；工作分支不天然归属单个需求群。

    

- `latestDiff` 是该工作分支历史上最近的真实 Diff 快照，包含其 `taskId`。当前最新 Task 无代码变更时，不会新建空 Diff，但不会抹掉该分支已有的历史 `latestDiff`。

    

- `openMergeRequest` 是同一项目仓库绑定和源分支的 Open MR；不存在时为 `null`。创建侧保证避免重复 Open MR。

    

- `lastVerification` 仅在已完成 TestRun 的 `executionSourceRef` 与 `lastKnownHead` 完全一致时返回；无法证明针对当前提交时为 `null`。它不包含 MR `qualityGate`，也不使用 GitHub `mergeable` 伪造 dry\-run。

    

- `latestTask`、`latestDiff`、`openMergeRequest`、`lastVerification` 均可为 `null`。前端显示空状态，不得补演示数据或空 Diff 壳。

    

- 默认分支仅由仓库列表接口的 `defaultBranch` 在仓库卡片展示，不在本接口中虚构工作分支行。

    

- 本接口不返回 GitHub 保护状态、冲突、落后状态、提交总数、构建产物或 MR 总数。

    

    

    

#### SSE 刷新







前端收到以下项目 SSE 事件后，应使当前项目的 `work-branches` 查询失效并重新读取：







- `task.updated`

    

- `diff.created`

    

- `merge-request.updated`

    

- `test-run.updated`

    

    

    

其中 `test-run.updated` 会影响 `lastVerification`；其他事件分别影响最近 Task、Diff 和 Open MR。Diff 详情和 MR 列表仍使用各自既有接口读取。



### 6\.10 GitHub Webhook 接收（公开接口，无 JWT）



新增公开接口，供 GitHub App 事件投递。接口不携带 Qgents JWT，安全依据为 `X-Hub-Signature-256`、`X-GitHub-Event`、`X-GitHub-Delivery` 和 Webhook Secret。



|方法|路径|认证|说明|
|---|---|---|---|
|`POST`|`/api/v1/integrations/github/webhook`|`X-Hub-Signature-256` 验签（无 Qgents JWT）|接收 ping/installation/installation\_repositories/pull\_request 事件，同步本地仓库/MR 镜像并发布项目级 SSE|
|**安全与幂等约定**：||||



- Secret 由 `GITHUB_WEBHOOK_SECRET` 环境变量注入；未配置时接口 fail\-closed 返回 503，不得为联调提供空 Secret 或固定默认值。

    

- 验签使用 HMAC\-SHA256 与常量时间比较，输入为原始 body 字节；Secret 不出现在日志、异常、SSE 或响应中。

    

- 以 `X-GitHub-Delivery` 作为唯一键防重复处理：相同 delivery 已处理返回 200 幂等；`FAILED` 的重投必须重新通过原始 body 验签后才允许再次处理；处理中并发请求返回 503 让 GitHub 重试。

    

- 本轮采用同步处理：业务同步完成后才返回 200；不引入 202、消息队列或未定义的后台消费者。

    

- 事务内不得调用 GitHub、Worker 或其他外部 HTTP；仓库详情补齐在事务外通过受控 GitHub 客户端完成，补齐失败记录 FAILED 等待重投，不把半成品标记为 AUTHORIZED。

    

**返回策略**：



|情况|HTTP|说明|
|---|---|---|
|签名正确并已处理|200|已完成本次同步并记录 PROCESSED/IGNORED|
|相同 `X-GitHub-Delivery` 已处理|200|幂等返回，不重复写入|
|签名缺失或不匹配|401|不落业务数据|
|Header/body 不完整或 JSON 非法|400|不落业务数据|
|Secret 未配置|503|fail\-closed，不当作验签失败|
|请求体超过上限|413|不落投递业务数据|
|暂时无法处理（仓库详情补齐失败等）|500|本次不标记为成功，允许 GitHub 重投|
|**第一版事件白名单**：|||
|GitHub 事件|处理内容|SSE|
|\-\-\-|\-\-\-|\-\-\-|
|`ping`|校验配置可用性，记录成功投递|否|
|`installation`|`created`/`unsuspend`/`new_permissions_accepted` → ACTIVE，`suspend` → SUSPENDED，`deleted` → DELETED|仅能确定团队/项目归属时|
|`installation_repositories`|`added`/`removed` 按 provider repository id upsert 或标记 REVOKED，校验 installation 一致|按受影响项目分别发布|
|`pull_request`|`opened`/`reopened`/`synchronize` → OPEN，`closed`\+merged → MERGED，`closed`\+未 merged → CLOSED；按 provider repository \+ PR number 幂等更新全部项目绑定 MR 镜像|每个成功更新的项目一次|
|**投递审计表 ****`github_webhook_deliveries`****：以 ****`provider_delivery_id`**** 唯一，状态 ****`RECEIVED → PROCESSED/IGNORED/FAILED`****，只存原始 body 的 SHA\-256 摘要；****`attempt_count`**** 记录同一 delivery 实际处理次数；****`RECEIVED`**** 超过 5 分钟视为处理中断，允许后续投递重新领取处理；已完成记录保留 30 天后由定时任务清理，超过 30 天的孤儿 ****`RECEIVED`**** 一并清除。**|||
|**SSE 事件契约补充**：|||



```Plain Text
github-installation.updated  -> { installationId, status }
github-repository.updated    -> { installationId, repositoryId, authorizationStatus, archived }
merge-request.updated        -> { projectId, mergeRequestId, repositoryId, number, status, headCommit, providerUpdatedAt, qualityGateStatus, timestamp }
```



上述 `installationId`/`repositoryId`/`mergeRequestId` 均为 Qgents 本地 UUID；事件只发送给能通过本地绑定关系确定的 Project。`merge-request.updated` 使用 `number`（不另造 `providerNumber`），`headCommit` 为真实完整 SHA；SSE 事件 `id` 仍使用项目内 `sequenceNo`，payload 不伪造 `sequence`。客户端收到事件后重新拉取 MR 详情或列表，不把 SSE payload 当作完整 DTO。



---



## 7\. 统一 Group 与消息















项目可有一个项目主讨论群和多个需求群，统一建模为 Group。创建项目时服务端自动创建唯一的 `PROJECT_MAIN` Group；它不可归档或删除。`REQUIREMENT` Group 是上下文与协作边界，可引用多个仓库；它不代表 Git `main`，也不天然生成或绑定分支。















|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/groups`|项目成员|项目总群与需求群列表，按最近活跃排序|
|`POST`|`/projects/{projectId}/groups`|项目成员|创建 `REQUIREMENT` Group|
|`GET`/`PATCH`|`/projects/{projectId}/groups/{groupId}`|项目成员/创建者或 Project Admin|获取群详情（含 `memberCount` 成员数）、修改需求群标题、描述和关联仓库|
|`GET`|`/projects/{projectId}/groups/{groupId}/members`|项目成员|获取群成员列表（= 项目成员 \+ 参与群聊的 Agent，响应含 `memberType`：USER/AGENT）；群内成员平等，无角色|
|`POST`|`/projects/{projectId}/groups/{groupId}/leave`|项目成员|当前用户退出群聊（即移出本项目成员，移出后失去该项目全部群/消息/资源的访问权限）|
|`POST`|`/projects/{projectId}/groups/{groupId}/archive`|创建者或 Project Admin|归档需求群\<br\>|
|`GET`|`/projects/{projectId}/groups/{groupId}/messages`|项目成员|游标拉取消息|
|`POST`|`/projects/{projectId}/groups/{groupId}/messages`|项目成员|发送文本、代码块、图片、文件或引用\<br\>|
|`POST`|`/projects/{projectId}/attachments`|项目成员|创建对象存储直传凭证\<br\>|
|`GET`|`/projects/{projectId}/groups/{groupId}/context`|项目成员|组装群聊上下文（需求 \+ 近期消息 \+ 关联仓库 \+ 已发布 Skill \+ 已批准 Memory），供 Agent 作为输入；`limit` 参数控制近期消息条数（默认 50，上限 200）|















> **群详情说明**：
> 
> 
> 
> 
> 
> 
> 
> - 群详情与改名已由 `GET`/`PATCH /projects/{projectId}/groups/{groupId}` 覆盖。
> 
>     
> 
>     
> 
>     
> 
> - 详情响应包含 `memberCount`（群成员数 = 项目成员数 \+ 群内 Agent 数）。
> 
>     
> 
>     
> 
>     
> 
> - 群成员 = 项目成员 \+ 参与群聊的 Agent，群内成员平等、无角色区分。
> 
>     
> 
>     
> 
>     
> 
> - Agent 通过服务端内部 `sendAsAgent` 首次回群后自动成为群参与者（`group_agents` 表），成员响应含 `memberType`（USER/AGENT）。
> 
>     
> 
>     
> 
>     
> 
> - `POST .../leave`（退出群聊）即当前用户移出本项目成员，移出后失去对该项目全部群/消息/资源的访问权限；最后一名 Project Admin 不可退群（与 §3\.1 一致）。
> 
>     
> 
>     
> 
>     
> 
>     
> 
>     
> 
>     
> 
> 



**创建需求群请求示例**：



```JSON
{
  "title": "登录功能",
  "description": "讨论账号与登录体验",
  "repositoryIds": ["project-repository-binding-uuid"],
  "type": "REQUIREMENT"
}
```



> `POST /groups` 只接受 `REQUIREMENT` 或省略 `type`；传入 `PROJECT_MAIN` 返回 `422 SYSTEM_GROUP_MANAGED`。
> 
> 
> 
> 
> 
> 







> 项目总群可收发消息，但仅用于项目级讨论和结构化动态；执行任务请求中的 `groupId` 必须指向同一项目下状态为 `ACTIVE` 的 `REQUIREMENT` Group。
> 
> 
> 
> 
> 
> 



**发送消息请求示例**：



```JSON
{
  "type": "TEXT",
  "content": {"text": "登录接口需要支持邮箱和密码。"},
  "mentions": [
    {"type": "USER", "id": "user-uuid"},
    {"type": "AGENT", "id": "agent-uuid"}
  ],
  "replyToId": null,
  "clientMessageId": "cmsg_01J..."
}
```







> `mentions` 为对象数组 `Mention[]`，每项 `{ "type": "USER" | "AGENT", "id": <UUID> }`（type 必填枚举 USER/AGENT，id 为被提及的用户或 Agent ID）；不提及传 `null` 或 `[]`。响应 `MessageResponse.mentions` 同构回显。
> 
> 
> 
> 
> 
> 















**消息类型说明**：



- `type` 为 `TEXT`、`CODE`、`IMAGE`、`FILE`、`DIFF`、`TASK_STATUS`、`SYSTEM` 或 `QUOTE`。

    

    

    

- 服务端写入单调递增 `sequence`；`clientMessageId` 在同一需求群内唯一，断线重试返回原消息。

    

    

    

- `DIFF`（Diff 卡片）content 至少含 `diffId`，如 `{"diffId":"...","title":"实现邮箱登录","additions":12,"deletions":3}`。

    

    

    

    

    

    

    

    

    

- `TASK_STATUS`（任务状态卡片）content 至少含 `taskId`、`status`，如 `{"taskId":"...","status":"RUNNING","node":"DEVELOPER","message":"正在执行测试"}`。

    

    当最终检查未检测到代码变更时，任务状态仍为 `SUCCEEDED`，`message` 必须明确说明未生成 Diff 或 MR。客户端以 `diff-review.skipped.reason=FINAL_DIFF_EMPTY` 作为机器判断依据，不能仅凭 `SUCCEEDED` 推断无代码变更。

    

    默认代码交付工作流的任务进度、终态和 Diff 审核卡片，统一由任务所属 Team 的 `ORCHESTRATOR` Agent 发送。该 Agent 只是卡片发送身份，不表示其实际执行了 `TaskStep`；客户端不得据此推断 Developer、Tester 或 Reviewer 的执行者。

    

    正常情况下，卡片消息的 `senderType=AGENT`，`senderId` 为该 Team 的 `ORCHESTRATOR` Agent ID。首次以该身份在群内发消息时，后端可能额外发布既有 `group.member.updated` 事件。

    

    若 Team 暂无可用 `ORCHESTRATOR` Agent，后端仍会写入同一内容的 `TASK_STATUS` 卡片，但消息为 `senderType=SYSTEM`，`senderId` 与 `senderName` 均为 `null`。该降级不新增 SSE 事件，客户端仍通过既有 `message.created` 刷新消息列表。

    

    

    

- `IMAGE`/`FILE`（图片/文件消息）content 至少含 `url`（展示/下载地址），如 `{"url":"/projects/{projectId}/attachments/{attachmentId}/content"}`。`url` 填附件稳定展示地址（服务端强校验 url 必填），获取方式见 §18\.5。

    

    

    

    

    

    

    

- 消息响应 `senderType` 为 `USER`/`AGENT`/`SYSTEM`：用户发送为 USER，Agent 通过服务端内部 `sendAsAgent` 回群为 AGENT，系统消息为 SYSTEM。

    

    

    

    

    

    

    

- Agent 可参与项目群聊（回群消息），但私聊与 Agent 好友不在本期范围。

    

    

    

    

    

    **从消息触发任务**：

    

    

    

    - **自动触发**：发送消息时 `mentions` 含 `type=AGENT` 项（如 @AgentOrchestrator）→ 服务端自动从该消息创建 Task（`triggerMessageId` = 本次消息 ID；同一消息只建一次，幂等）。前置条件：群为 ACTIVE REQUIREMENT 且已绑定至少一个仓库（未绑仓库则跳过并记录 warn，不阻塞消息发送）；`agentId` 仅作调度偏好，不绕过后端角色/并发/项目可见性/仓库授权校验。任务创建后 Task 状态为 `PLANNING`，由编排自动推进（Planner → Developer → Tester → Reviewer）。

        

    - Task 创建事务提交后，后端异步启动编排。客户端不调用 `orchestrate`、`start` 或任何“推进任务”的接口。

        

        Sandbox、工作流图等启动阶段发生意外失败时，Task 会置为 `FAILED`。客户端通过既有 `task.updated`、`message.created`（失败 `TASK_STATUS` 卡片）和 `notification.created`（`kind=TASK_FAILED`）获知结果；断线重连或收到乱序事件后，仍应以 Task、消息和通知查询接口返回的数据为准。

        

    - **显式触发**：`POST /projects/{projectId}/groups/{groupId}/messages/{messageId}/trigger-task`，对已发送消息显式创建 Task（项目成员；body 为 `TaskTriggerRequest`，缺省字段由服务端从消息文本/群信息提取，需 `Idempotency-Key`）。

        

        

        

\- **引用 DIFF 卡续作（增量修改）**：当发送（或显式触发）的消息 `replyToId` 指向一条 `type=DIFF`



消息时，服务端从该 DIFF 卡 `content.diffId` 定位源 Task 与源 Workspace，自动创建\**复用同一 Workspace*



\*、`continuationOfTaskId=源Task` 的增量任务，仓库范围由该 Workspace 继承。客户端**不得**提交 \`workspa



ceId`/`continuationOfTaskId\`，这两个字段只能由服务端从被引用的 DIFF 卡推导。追问文本（消息正文）作为



新任务 `requirement`/`defaultRequirement` 进入；被引用的 DIFF 卡若在最近 50 条群消息内，其内容也随群



聊上下文注入。DIFF 卡与用户追问消息的 `replyToId` 关系是续作判定的唯一依据（非消息正文文本）。







**群列表 DTO 补充**（`GET /projects/{id}/groups`，A 联调约定 §2）：















- `latestActivityAt`：后端返回，ISO8601 UTC 字符串，作为列表排序依据；从未发言时以创建时间兜底。

    

    

    

    

    

    

    

- `latestMessage`：后端返回，列表摘要对象 `{ "senderName": string|null, "text": string|null, "type": string|null }`；`senderName` 为用户昵称或 Agent 名称（SYSTEM 消息为空），`text` 为最新消息文本（仅 TEXT/QUOTE 等含 `$.text` 的类型可取到，其余为空），`type` 为最新消息类型，取值与消息类型枚举一致（`TEXT`/`CODE`/`IMAGE`/`FILE`/`SYSTEM`/`QUOTE`/`DIFF`/`TASK_STATUS`），客户端可据此对 `IMAGE`/`FILE` 等无文本消息展示 `[图片]`/`[文件]` 摘要。

    

    

    

    

    

    

    

- `unreadCount`、`isPinned`：本轮后端不返回，由前端 localStorage 兜底（会话个人偏好不在本轮）。

    

    

    

    

    

    

    

- 归档判断：使用 `status`（`ACTIVE`/`ARCHIVED`），不设独立 `isArchived` 字段。

    

    

    

    

    

    

    

**消息 DTO 补充**（`GET/POST .../messages`，A 联调约定 §3）：















- `senderId`：后端返回；USER 消息 = `userId`，AGENT 消息 = `agentId`，前端据此判断是否本人发送。

    

    

    

    

    

    

    

- `senderName`：后端不返回，前端用 `senderId` 反查群成员列表（`GET .../members`）获取 `displayName`。

    

    

    

    

    

    

    

- `sequence`：后端返回，单调递增，作为分页游标。

    

    

    

    

    

    

    

- `clientMessageId`：前端发送时携带，服务端在同一需求群内幂等去重，断线重试返回原消息。

    

    

    

    

    

**群列表与详情：****`mentionedUnread`****（未读「@ 我」数）**



`GET /projects/{projectId}/groups`、`GET /chat/main-groups`（主群聚合）与 `GET /projects/{projectId}/groups/{groupId}`（详情）的 `GroupResponse` 均返回 `mentionedUnread`：



- 类型 `number`，恒 ≥ 0，无未读 @ 时为 0。

- 口径（与 `unreadCount` 同源，是其子集）：`sequence_no > 已读游标` 且 `mentions` 含当前用户（`JSON_CONTAINS(mentions, {"type":"USER","id":<当前用户>})`）且 `sender_id != 当前用户` 的消息数。

- 按当前登录用户计：A 被 @ 不影响 B。

- 前端仅在 `typeof mentionedUnread === 'number' && mentionedUnread > 0` 时显示「有人@你」角标/提示。

    

    

    

**标记已读：****`POST /projects/{projectId}/groups/{groupId}/read`**



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

- 需 `Idempotency-Key`（写操作统一约定）。

    

    

    

**单条消息定位：****`GET /projects/{projectId}/groups/{groupId}/messages/{messageId}`**



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/groups/{groupId}/messages/{messageId}`|群成员|按消息 ID 拉取单条群消息（通知直达被 @ 消息：目标消息较旧、不在前端已加载分页窗口时调用，拉取后合并进本地列表再滚动高亮）|



响应 `data` 为完整 `MessageResponse`（含 `senderName`/`sequence`/`mentions`/`replyToId`），与列表项结构一致。



错误：



|code|HTTP|场景|
|---|---|---|
|`GROUP_NOT_FOUND`|404|群不存在或无权限|
|`MESSAGE_NOT_FOUND`|404|消息不存在或不属于该群|







**QUOTE 引用消息（回复/引用）字段冻结**



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

- 引用 DIFF 卡时 `quotedText` 为该 DIFF 卡摘要；**引用续作判定只看 ****`replyToId`**** 指向的父消息类型（DIFF 与否），与正文无关**（见 §7「引用 DIFF 卡续作」）。

- 前端展示约定：气泡内只显示回复正文（`replyText`），被引用原消息以「竖线 \+ 灰色小字」挂载在气泡下方；嵌套引用（引用的是 QUOTE 消息）取 `replyText ?? quotedText` 显示，避免叠加成 `[引用][引用]…`。

    

    

    

### 7\.1 通知中心















通知中心按用户维度持久化已读状态与历史列表；SSE（§12\.1）只负责「实时提醒」（新事件铃铛提醒），不承担历史列表与已读状态。任务类通知由事件触发写入，接收人为任务发起人；团队/项目成员类通知（`INVITED`/`TEAM_JOINED`/`PROJECT_ADDED`）由对应业务接口直接写入，接收人为被邀请用户 / 邀请者 / 被加入项目的成员。















|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/notifications`|登录用户|返回当前用户通知列表（含 `isRead`），按 `createdAt` 倒序；演示阶段不分页，一次性返回全量|
|`POST`|`/notifications/{id}/read`|登录用户|标记单条已读（幂等）|
|`POST`|`/notifications/read-all`|登录用户|全部已读（幂等）|















**通知字段（****`Notification`****）：**















|字段|类型|说明|
|---|---|---|
|`id`|string|通知 id|
|`kind`|string|`TASK_COMPLETED` / `TASK_FAILED` / `AGENT_INPUT_REQUIRED` / `DELIVERABLE_PENDING` / `MR_PENDING` / `INVITED` / `TEAM_JOINED` / `PROJECT_ADDED` / `MESSAGE_MENTION`|
|`title`|string|一行标题|
|`description`|string?|补充说明|
|`isRead`|boolean|是否已读|
|`createdAt`|string|ISO8601 UTC|
|`projectId`|string?|所属项目，点击跳转用|
|`groupId`|string?|来源需求群|
|`resourceId`|string?|关联资源 id（taskId / mrId / diffId），跳转定位用|















**通知数据来源（后端在对应事件或业务路径发生时写入通知表）：**







|触发来源|通知 kind|
|---|---|
|`task.updated`（SUCCEEDED / FAILED）|`TASK_COMPLETED` / `TASK_FAILED`|
|`delivery.completed`|`TASK_COMPLETED`|
|`delivery.failed` / `task.diff-review.failed`|`TASK_FAILED`|
|`input-required` / `approval-required`|`AGENT_INPUT_REQUIRED`|
|`diff.created`|`DELIVERABLE_PENDING`|
|`merge-request.updated`|`MR_PENDING`|
|团队邀请创建 `POST /teams/{teamId}/invitations`（被邀请邮箱已注册）|`INVITED`|
|团队邀请接受 `POST /team-invitations/{reference}/accept`|`TEAM_JOINED`|
|项目加成员 `POST /projects/{projectId}/members`|`PROJECT_ADDED`|
|群聊发送消息 `POST .../messages` 且 `mentions` 含某用户（排除发送者本人）|`MESSAGE_MENTION`|







> 成员类通知无对应 SSE 事件，由业务接口直接写入：`INVITED` 接收人为被邀请用户（未注册邮箱无 `userId`，仅走邮件邀请）；`TEAM_JOINED` 接收人为邀请者；`PROJECT_ADDED` 接收人为被加入项目的成员。标题示例：「你被邀请加入团队 \{团队名\}」「\{用户\} 已加入团队 \{团队名\}」「你被加入项目 \{项目名\}」，`resourceId` 分别为团队 ID / 团队 ID / 项目 ID。
> 
> 



> **`MESSAGE_MENTION`****（群聊 @ 提及）**：发送消息 `mentions` 含某用户（`type=USER` 且非发送者本人）时，给每个被 @ 用户写一条通知。字段约定：
> 
> 
> 
> - `title`：「有人在群聊中提到了你」；`description`：`发送者 在群「群名」中提到了你：消息文本摘要`（摘要为空时省略冒号后内容）。
> 
> - `projectId` = 消息所属项目，`groupId` = 来源需求群（点击跳转该群）。
> 
> - `resourceId` **必填** = 被 @ 的那条消息 ID：前端跳群后据它自动滚动高亮到该消息（`ChatPanel` 调用 `GET .../messages/{messageId}` 分页外定位，见 §7）；`resourceId` 缺失时前端兜底跳到该群最上面一条被 @ 的消息。
> 
> - 通知写入独立于 SSE：`notification.created` 事件驱动铃铛刷新（既有机制）；`@AGENT` 不写用户通知，走自动触发任务链路。
> 
>     
> 
>     
> 
> 







### 7\.2 群搜索（第三批，暂不实现）















|方法|路径|权限|说明|
|---|---|---|---|
|`GET`\<br\>|`/search?q={q}&type=groups`|登录用户|按关键字搜索当前用户可访问的群（群名匹配），返回群摘要列表；该能力属于后续批次，本期不实现|















---







## 8\. 共享 Skill







Skill 是项目需求群内可复用的能力片段，例如规范、提示词、操作指引或工具调用约束。成员可创建 `PRIVATE` Skill（仅自己可用，创建即发布，不进交付中心，不支持转共享）；需要项目共享时创建或转为 `PROJECT_SHARED`，由 Project Admin 审核后发布，供该项目成员的 Agent 使用。Skill 不是 Memory，不能承载未经确认的客观事实。



**免审批规则（v2\.0\.6 补充）：**



- `PRIVATE` Skill：任何人创建即 `PUBLISHED`，无需审核；仅创建者自己可见/使用；**不进入交付中心**（交付中心 `skillItems` 过滤 `PRIVATE`）；**不支持转共享审核**（`submit-review` 对 PUBLISHED 状态返回状态冲突）。

- `PROJECT_SHARED` Skill：Project Admin 自建直接 `PUBLISHED`（免审批）；普通成员创建为 `DRAFT`，提交审核后由 Admin 发布。

    

    

    

|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/skills`|项目成员|查询 Skill，支持状态、标签过滤；仅返回 `PROJECT_SHARED` 或自己创建的|
|`POST`|`/projects/{projectId}/skills`|项目成员|创建 Skill（`PRIVATE` 创建即发布；`PROJECT_SHARED` 按免审批规则定初始状态）|
|`GET`/`PATCH`|`/projects/{projectId}/skills/{skillId}`|项目成员/创建者或 Project Admin|获取/编辑草稿或审核中内容；他人 `PRIVATE` 视为不可见（404）|
|`POST`|`/projects/{projectId}/skills/{skillId}/submit-review`|创建者或 Project Admin|提交审核（仅 `DRAFT`/`REJECTED` 可提交；PRIVATE 已发布不可提交）|
|`POST`|`/projects/{projectId}/skills/{skillId}/approve`|Project Admin|发布为 `PROJECT_SHARED`（仅 `PENDING_REVIEW`）|
|`POST`|`/projects/{projectId}/skills/{skillId}/reject`|Project Admin|拒绝并给出原因|
|`POST`|`/projects/{projectId}/skills/{skillId}/archive`|Project Admin|下线已发布 Skill|







**创建 Skill 请求示例**：



```JSON
{
  "name": "Java API 规范",
  "content": "Controller 仅处理 HTTP 协议，业务逻辑放入 Service。",
  "tags": ["java", "backend"],
  "visibility": "PRIVATE"
}
```







被共享的 Skill 在后续执行时可由 Agent 卡片或工作流节点引用；Agent 仅获得其当前项目中有权使用的 Skill，归档后不再装配到新执行中。







---







## 9\. 共享 Memory







Memory 是经人工确认后供项目复用的知识，不是原始聊天记录。AI 可以根据群聊消息生成草稿，但不得直接批准或发布（AI 沉淀草稿即使 Admin 也保持 `DRAFT`）；后续 Agent 只能按相关性和标签检索已批准的 Memory，不能默认注入全部内容。



**免审批规则（v2\.0\.6 补充）：** Project Admin **手动创建**的 Memory 直接 `APPROVED` 上架（免审批）；普通成员手动创建为 `DRAFT`。AI 沉淀草稿（`POST /memories/drafts`）始终为 `DRAFT`，任何人均需审核。







|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/memories`|项目成员|查询 Memory，默认仅 `APPROVED`（非 APPROVED 仅创建者或 Admin 可见），支持状态、标签过滤|
|`POST`|`/projects/{projectId}/memories`|项目成员|手动创建（Admin 直接 `APPROVED`，成员 `DRAFT`）|
|`POST`|`/projects/{projectId}/memories/drafts`|项目成员|按群自动检索最近聊天，AI 生成草稿（`DRAFT`，AI 不得直接批准）|
|`GET`/`PATCH`|`/projects/{projectId}/memories/{memoryId}`|项目成员/创建者或 Project Admin|获取/编辑草稿或审核中内容|
|`POST`|`/projects/{projectId}/memories/{memoryId}/submit-review`|创建者或 Project Admin|提交审核|
|`POST`|`/projects/{projectId}/memories/{memoryId}/approve`|Project Admin|批准并发布|
|`POST`|`/projects/{projectId}/memories/{memoryId}/reject`|Project Admin|拒绝并给出原因|
|`POST`|`/projects/{projectId}/memories/{memoryId}/archive`|Project Admin|归档 Memory|







**手动创建草稿请求示例**：



```JSON
{
  "title": "密码存储约定",
  "content": "密码仅存储 bcrypt 哈希，登录时使用 bcrypt.compare 校验。",
  "category": "ENGINEERING_DECISION",
  "tags": ["auth", "security"]
}
```







**群聊生成草稿请求示例**（v2\.0\.6：改为按群自动检索，客户端不再勾选消息）：



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

    

    

    

Memory 响应必须包含 `creator`、`reviewer`、`reviewedAt`、`category`、`tags` 与 `sources`。当前支持 `MANUAL`、`MESSAGE` 来源；未来任务/Diff 来源可扩展，但不在本版创建接口范围。







---







## 10\. Testset







Testset 是项目自建、可复用的测试配置，而不是特殊语法。它可表示单元、接口或集成测试；本期只管理配置，实际执行由后续系统承担。







|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/testsets`|项目成员|查询 Testset，支持仓库与启用状态过滤|
|`POST`|`/projects/{projectId}/testsets`|Project Admin|创建 Testset|
|`GET`/`PATCH`|`/projects/{projectId}/testsets/{testsetId}`|项目成员/Project Admin|获取/修改配置|
|`POST`|`/projects/{projectId}/testsets/{testsetId}/enable`|Project Admin|启用|
|`POST`|`/projects/{projectId}/testsets/{testsetId}/disable`|Project Admin|禁用|
|`DELETE`|`/projects/{projectId}/testsets/{testsetId}`|Project Admin|删除未被门禁引用的 Testset|







**创建 Testset 请求示例**：



```JSON
{
  "name": "后端单元测试",
  "repositoryId": "project-repository-binding-uuid",
  "scopeTags": ["backend", "unit"],
  "command": "./mvnw test",
  "timeoutSeconds": 900,
  "passRule": {"type": "EXIT_CODE", "expected": 0},
  "acceptanceNotes": "登录成功、错误密码和不存在用户均需覆盖。"
}
```







Project Member 可查看并在未来任务计划中选择 `ENABLED` Testset；受保护分支的必选 Testset 以质量门禁配置为准，成员不能替换或跳过。







---







## 11\. Agent、团队工作流与任务拆分







系统内置默认代码交付工作流：`PLANNER → DEVELOPER → TESTER → REVIEWER`。用户不配置自定义 Agent 时，步骤可由对应的内置执行 Agent 兜底；配置了符合角色和权限的团队/个人 Agent 时，系统按步骤分配并执行。一个 `TaskStep` 只绑定一个执行 Agent，任务的计划、开发、测试和审查仍是独立步骤，不能把完整交付伪装为单个 Agent 的执行结果。







每个 Team 还拥有一个 `ORCHESTRATOR` Agent（展示名称为“编排助手”）。它仅负责在需求群发送任务进度、终态和 Diff 审核卡片，不参与 `TaskStep` 分配，也不替代 `PLANNER`、`DEVELOPER`、`TESTER` 或 `REVIEWER` 的执行职责。其资源 ID 是 Team 级数据，客户端不得硬编码；缺失时任务状态卡按 §7 的 `SYSTEM` 消息规则降级。







团队成员可创建仅自己可用的 Agent；发布后成为团队可用资源。产品界面至少展示每张 Agent 身份卡的昵称、头像、角色、能力标签、可用状态、创建者和可访问的 Skill 摘要；不得向其他成员泄露私有提示词或凭据。







### 11\.1 个人与团队 Agent







|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/teams/{teamId}/agents`|团队成员|查询系统 Agent、本人私有 Agent 和团队已发布 Agent|
|`POST`|`/teams/{teamId}/agents`|团队成员|创建自己的 `PRIVATE` Agent|
|`GET`/`PATCH`|`/teams/{teamId}/agents/{agentId}`|可见成员/创建者|获取 Agent 卡；创建者编辑自己的 Agent|
|`POST`|`/teams/{teamId}/agents/{agentId}/publish`|创建者|发布为 `TEAM`（团队共享），供团队成员使用|
|`POST`|`/teams/{teamId}/agents/{agentId}/unpublish`|创建者或 Team Owner|收回为私有 Agent|
|`POST`|`/teams/{teamId}/agents/{agentId}/archive`|创建者或 Team Owner|下线 Agent，已运行任务不受影响|
|`GET`|`/projects/{projectId}/agent-skill-bindings/{agentId}`|项目成员|读取指定 Agent 在当前项目的 Skill 绑定集|
|`PUT`|`/projects/{projectId}/agent-skill-bindings/{agentId}`|Agent 创建者或 Project Admin|全量替换绑定集；空数组清空（幂等，无需 Idempotency\-Key）|







**创建 Agent 请求示例**：



```JSON
{
  "name": "Java 后端 Agent",
  "avatar": "https://cdn.example.com/avatars/java.png",
  "role": "DEVELOPER",
  "description": "负责开发实现需求中的代码改动，按计划修改工作区文件并完成自检",
  "prompt": "遵循项目 API 规范和测试要求。"
}
```







**Agent 卡响应示例**（`GET`/`PATCH /teams/{teamId}/agents/{agentId}` 返回）：



```JSON
{
  "id": "agent-uuid",
  "name": "Java 后端 Agent",
  "avatar": "https://cdn.example.com/avatars/java.png",
  "role": "DEVELOPER",
  "description": "负责开发实现需求中的代码改动，按计划修改工作区文件并完成自检",
  "prompt": "遵循项目 API 规范和测试要求。",
  "visibility": "PRIVATE",
  "status": "ACTIVE",
  "createdBy": "user-uuid"
}
```







身份卡字段由 `agents` 表持久化（产品需求 §2\.3）：`name`（昵称）、`avatar`（头像）、`role`（角色标签



）、`description`（用途描述）、`prompt`（系统提示词）。私有提示词与私有可见性仅创建者可见，不得向其他成员泄露。







`role` 为 `ORCHESTRATOR`、`PLANNER`、`DEVELOPER`、`TESTER`、`REVIEWER` 或 `GENERAL`。角色与用途描述是身份卡上的调度线索，不是权限绕过手段；工作流节点可按角色选择 Agent，决策 Agent 依据角色与用途描述从可用 Agent 中选用实际执行者。



**自定义 Agent 管理接口补充（v2\.0\.6）：**



|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/teams/{teamId}/agents`|团队成员|创建自定义 Agent（`PRIVATE`\+`ACTIVE`）；创建后 `isDefault=false`；需 `Idempotency-Key`（同一 Key 重试返回首次结果，不同请求体复用同一 Key 返回 409）|
|`PATCH`|`/teams/{teamId}/agents/{agentId}`|创建者|编辑自定义 Agent（系统预置 `isDefault=true` 不可编辑）；至少一个字段；`visibility/status/createdBy/isDefault` 不允许客户端修改|
|`POST`|`/teams/{teamId}/agents/{agentId}/publish`|创建者|发布为 `TEAM`（`PRIVATE+ACTIVE` → `TEAM+ACTIVE`）；需 `Idempotency-Key`|
|`POST`|`/teams/{teamId}/agents/{agentId}/unpublish`|创建者或 Team Owner|收回为私有（`TEAM+ACTIVE` → `PRIVATE+ACTIVE`）；需 `Idempotency-Key`|
|`POST`|`/teams/{teamId}/agents/{agentId}/archive`|创建者或 Team Owner|归档（`PRIVATE/TEAM+ACTIVE` → `ARCHIVED`），已运行任务不受影响；需 `Idempotency-Key`|



- 自定义 Agent 的 `role` 取值与身份卡一致（含 `GENERAL`）；`name` 必填且去空白后非空，`prompt` 必填，`avatar`/`description` 可选（空转 `null`）。

- 创建响应为 `AgentResponse`（含 `visibility=PRIVATE`、`status=ACTIVE`、`isDefault=false`）。

- `PATCH` 支持修改 `name/avatar/role/description/prompt`；`role` 变更只影响后续新分配的 TaskStep，不改变已定型的 `TaskStep.assignedAgentId`。

    

    

    

**Agent 头像上传（v2\.0\.6，OSS 直传 \+ 公共读 URL）：**



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

    

    

    

#### 11\.1\.1 Agent\-Skill 绑定







绑定将项目内可用 Skill 装配到 Team 级 Agent，同一 Agent 在不同项目可绑定不同技能集；绑定按项目隔离。可绑定 Skill：本人 `PRIVATE`（未归档）或已发布的 `PROJECT_SHARED`。修改权限：Agent 创建者或 Project Admin；读取权限：项目成员。







**请求体（PUT，全量替换，空数组清空）**：



```JSON
{"skillIds": ["skill-uuid-1", "skill-uuid-2"]}
```





**响应体**：



```JSON
{
  "agentId": "agent-uuid",
  "skillIds": ["skill-uuid-1"],
  "skills": [
    {"id": "skill-uuid-1", "name": "Java API 规范", "visibility": "PROJECT_SHARED", "status": "PUBLISHED"}
  ],
  "updatedAt": "2026-08-12T10:00:00Z"
}
```







|状态码|错误码|条件|
|---|---|---|
|`403`|`AGENT_BINDING_FORBIDDEN`|修改他人 `PRIVATE` Agent 且非 Project Admin|
|`404`|`AGENT_NOT_FOUND`/`SKILL_NOT_FOUND`|Agent/Skill 不存在|
|`409`|`AGENT_SKILL_DUPLICATE`|请求体 `skillIds` 存在重复|
|`422`|`AGENT_NOT_IN_PROJECT_TEAM`|Agent 不属于当前项目的 Team|
|`422`|`AGENT_NOT_ACTIVE`|Agent 未启用|
|`422`|`SKILL_NOT_IN_PROJECT`|Skill 不属于当前项目|
|`422`|`SKILL_NOT_BINDABLE`|Skill 已归档/他人 `PRIVATE`/`PROJECT_SHARED` 未发布|







### 11\.2 团队工作流模板







本版本仅提供不可修改的 `system-default-code-delivery` 工作流。自定义团队工作流模板及其配置接口不在本版本范围。







### 11\.3 Task、Workspace 与执行计划







系统必须将代码实现、测试和审查分派给不同 Agent 角色。当前公开模型以用户可见的 `Task` 为顶层任务，Planner 将计划写入 `TaskStep`，每个步骤可产生多次 `TaskRun`；禁止重新引入 WorkPackage、Deliverable 或 orchestration 兼容层。







|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/projects/{projectId}/tasks`|项目成员|从当前项目的 ACTIVE REQUIREMENT 群创建 Task；可创建新 Workspace 或显式复用前序 Task 的 Workspace|
|`GET`|`/projects/{projectId}/tasks`|项目成员|查询当前项目可见的 Task，支持 `groupId`、`status`、`createdBy` 过滤与游标分页|
|`GET`|`/projects/{projectId}/tasks/{taskId}`|项目成员|获取 Task、Workspace 与 repository 范围|
|`POST`|`/projects/{projectId}/tasks/{taskId}/steps`|发起人或 Project Admin|写入 Planner 生成的有依赖 TaskStep 计划|
|`GET`|`/projects/{projectId}/tasks/{taskId}/steps`|项目成员|查询 Task 的 TaskStep 计划列表|
|`POST`|`/projects/{projectId}/tasks/{taskId}/steps/{stepId}/replace-agent`|发起人或 Project Admin|仅在步骤仍为 `PENDING` 时更换 Agent|
|`POST`|`/projects/{projectId}/tasks/{taskId}/cancel`|发起人或 Project Admin|取消整个 Task（202 异步受理）：`PLANNING`/`PENDING` 同步置 `CANCELLED`，`RUNNING` 置 `CANCELLING` 由执行器在安全检查点终止；终态返回 `409 TASK_NOT_CANCELLABLE`|







**创建 Task 请求示例**：



```JSON
{
  "requirementGroupId": "group-uuid",
  "title": "实现邮箱登录",
  "requirement": "前后端都要支持，并完成测试。",
  "repositoryIds": ["project-repository-binding-uuid"],
  "baseRef": "main",
  "deliveryMode": "DIFF_FIRST"
}
```







创建 Task 返回 `201 Created`。新 Task 至少绑定一个 repository，并只关联一个 Workspace；继续任务必须同时提交 `workspaceId` 与 `continuationOfTaskId`，且二者与当前 Task 属于同一 Project。新 Workspace 可包含多个 repository worktree，客户端不能直接操作 Workspace 或 Sandbox。







**重要说明**：创建请求中的 `repositoryIds` 与 baseRef 属于随之创建的 **Workspace**（worktree），不属于 Task 本身；`tasks` 表不存这两列。`repositoryIds` 每一项必须是第 6 节 ProjectRepository 响应的 `id`，即 `project_repositories.id`，不得传 `github_repositories.id` 或 GitHub provider 数字 ID。前端创建 Task 时带上它们是告诉系统用哪些项目仓库绑定、以哪个分支为基准初始化 Workspace。







**启动方式**：新 Task 模型**不再有** `startMode`（AUTO/MANUAL），也没有工作包的 `start/pause/resume` 接口；Task 创建后由 Planner 写入 TaskStep 并自动执行，取消统一走 `POST /tasks/{taskId}/cancel`。默认工作流节点定义（Planner → Developer → Tester → Reviewer）由 Agent 编排引擎（后端1）内置的 `system-default-code-delivery` 提供，前端只消费节点状态，不定义节点。`POST /tasks/{taskId}/steps`（发起人或 Project Admin）是**手动编排入口**：默认由 Planner（系统 Agent）自动写入步骤计划，也保留用户手动写入依赖、角色与验收条件的 TaskStep 的能力（该完整手动编排能力暂未实现，接口先预留）；调度器仍按步骤 `role` 分配可用 Agent。







一个 Workspace 同一时刻只能有一个有效写入者；复用 Workspace 的后续 Task 必须显式引用前序 Task，不能仅凭聊天上下文复用。



#### 11\.3\.1交付模式与任务状态







创建 Task 和群聊触发 Task 的请求体可选传入 `deliveryMode`。



`deliveryReason` 由服务端生成，客户端不得提交或覆盖。







```JSON
{
  "deliveryMode": "MR_FIRST",
  "deliveryReason": "涉及 2 个仓库，按规则判定 MR_FIRST"
}
```



或者如果示例表示尚未完成判定：



```Plain Text
{
"deliveryMode": null,
"deliveryReason": null,
}
```







|规则|字段|类型|取值|
|---|---|---|---|
|用户显式指定优先；未指定时由 Planner 判定；Planner 缺失、非法或不确定时由服务端硬规则兜底。非法值返回 `400 INVALID_DELIVERY_MODE`。续作 Task 沿用源 Task 的已定型模式。|`deliveryMode`|string，可选|`DIFF_FIRST` / `MR_FIRST`|
|由服务端写入（Planner `scaleReason` 或规则依据），客户端不得提交或覆盖。|`deliveryReason`|string，只读，可空|判定理由|







`deliveryMode` 在计划物化后定型，执行期间不得变更。`TaskDetailResponse` 与 `TaskListItemResponse` 均返回以下字段：







```JSON
{
  "deliveryMode": "MR_FIRST",
  "deliveryReason": "涉及 2 个仓库，按规则判定 MR_FIRST"
}
```







Task 状态仍为 `PLANNING/PENDING/RUNNING/WAITING_DIFF_CONFIRMATION/DELIVERING/SUCCEEDED/DELIVERY_FAILED/FAILED/CANCELLING/CANCELLED`。交付相关状态语义如下：







|状态|含义|
|---|---|
|`WAITING_DIFF_CONFIRMATION`|仅 `DIFF_FIRST` 使用：最终 Diff 已生成，等待用户确认或拒绝。|
|`DELIVERING`|交付已获授权，正在逐仓库执行真实 Commit、Push 与 PR 创建。此状态不代表 PR 已创建。|
|`SUCCEEDED`|所有目标仓库均已真实创建 PR；PR 是否通过质量门禁、是否被合并由 MR 资源表达。|
|`DELIVERY_FAILED`|至少一个仓库交付失败；已成功仓库不回滚，允许重试失败仓库。|







`MR_FIRST` 在 REVIEWER 成功后直接创建系统授权的最终 Diff 批次并进入 `DELIVERING`，不会进入 `WAITING_DIFF_CONFIRMATION`。



**Task 响应示例**（创建/详情/列表项均为此结构）：



```JSON
{
  "id": "task-uuid",
  "projectId": "project-uuid",
  "requirementGroupId": "group-uuid",
  "triggerMessageId": "message-uuid",
  "title": "实现邮箱登录",
  "requirement": "前后端都要支持，并完成测试。",
  "status": "RUNNING",
  "deliveryMode": "DIFF_FIRST",
  "workspaceId": "workspace-uuid",
  "workspaceStatus": "READY",
  "continuationOfTaskId": null,
  "repositoryIds": ["project-repository-binding-uuid"],
  "repositories": [
    {
      "repositoryId": "project-repository-binding-uuid",
      "workspacePath": "workspaces/workspace-uuid",
      "baseCommit": "abc123",
      "sourceBranch": "feat/login-api",
      "headCommit": null
    }
  ],
  "createdBy": "user-uuid",
  "createdAt": "2026-08-12T10:00:00Z",
  "updatedAt": "2026-08-12T10:00:00Z"
}
```







**TaskStep 响应示例**（`GET /projects/{projectId}/tasks/{taskId}/steps` 列表项）：



```JSON
{
  "id": "step-uuid",
  "taskId": "task-uuid",
  "role": "DEVELOPER",
  "agentId": "agent-uuid",
  "repositoryId": "project-repository-binding-uuid",
  "baseRef": "main",
  "dependencies": ["step-uuid-2"],
  "testsetIds": ["testset-uuid"],
  "status": "PENDING",
  "acceptanceNotes": "登录成功、错误密码和不存在用户均需覆盖。"
}
```







**关联字段说明**（前端展示用）：







- `TaskRun.agentId` 指向实际执行该步骤的 Agent（agents 表，§11\.1）。

    

    

    

- `TaskStep.role` 声明所需工作流角色（ORCHESTRATOR/PLANNER/DEVELOPER/TESTER/REVIEWER），调度器按角色挑选可用 Agent。

    

    

    

- Agent 可装配的 Skill 通过 `PUT /projects/{projectId}/agent-skill-bindings/{agentId}` 维护。

    

    

    

- `TaskStep.testsetIds` 与质量门禁 `requiredTestsetIds` 引用 Testset（§6\.1/§10）。

    

    

    

Workspace 是 Project 内持久化的 Git 工作目录，Workspace repository 必须记录真实 base commit、source branch 和 head commit；Sandbox 仅是临时执行环境，销毁后未提交 Workspace 修改仍需保留。







---







## 12\. 受控执行、Diff 与实时事件







本节记录 TaskStep 的实际执行尝试为 `TaskRun`。TaskRun 必须关联 Task 与 TaskStep；它不是新的顶层任务，也不单独向用户产出 Diff 或 MR。用户可发起重试、读取受控日志和查看 Task 级结果，但不能直接创建、进入或操作 Workspace/Sandbox。







所有本节 `POST` 接口均要求 `Idempotency-Key`，成功受理时返回 `202 Accepted` 和资源摘要；列表接口复用第 2 节的 `cursor` 与 `limit`。服务端必须校验路径中的 `projectId`、关联资源与当前用户在同一项目中，禁止仅通过 UUID 查询资源。







### 12\.1 实时事件流



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/events`|Project Member|建立项目级 SSE 连接，接收状态和产物事件|
|该接口的 `Content-Type` 为 `text/event-stream`，不套用 JSON 成功响应。客户端可通过 `Last-Event-ID` 断线续传；服务端每 15 秒发送心跳，并至少保留 24 小时事件。续传点过期时返回 `409 EVENT_CURSOR_EXPIRED`，客户端应重新拉取相关资源。||||



**SSE 事件示例**：



```Plain Text
id: evt_01J...
event: task-run.step.progress
data: {"projectId":"project-uuid","taskId":"task-uuid","stepId":"step-uuid","taskRunId":"task-run-uuid","node":"DEVELOPER","sequence":12,"content":"正在执行测试","timestamp":"2026-08-10T12:00:00Z"}
```



**事件类型**：



- `task.updated`

    

- `task-step.updated`

    

- `task-run.updated`

    

- `task-run.step.progress`

    

- `input-required`

    

- `approval-required`

    

- `test-run.updated`

    

- `dry-run.updated`

    

- `diff.created`

    

- `task.artifact.created`

    

- `task-run.artifact.created`

    

- `diff-review.created`

    

- `task.awaiting-diff-confirmation`

    

- `diff-review.confirmed`

    

- `diff-review.rejected`

    

- `delivery.repository.updated`

    

- `delivery.failed`

    

- `delivery.completed`

    

- `task.diff-review.failed`

    

- `diff-review.skipped`

    

- `merge-request.updated`

    

- `delivery.started`

    

事件仅用于刷新界面；客户端恢复连接或收到乱序事件后必须以相应的查询接口为准。受控日志不得包含 Token、密码、GitHub 安装令牌、私钥或未脱敏的环境变量。



SSE 事件 `id` 即项目内单调递增 `sequenceNo`，作为 `Last-Event-ID` 续传游标；输入与审批事件必须包含 `inputRequestId`。



#### 12\.1\.1 MR\_FIRST 开始交付事件



`delivery.started` 是项目 SSE 事件，表示 MR\_FIRST 的交付意图已与 Task 状态、最终 Diff 快照和交付租约一并落库。它仅用于前端刷新展示；前端不得将 SSE 当作命令通道。



```JSON
{
  "event": "delivery.started",
  "data": {
    "projectId": "project-uuid",
    "taskId": "task-uuid",
    "reviewBatchId": "review-batch-uuid",
    "deliveryMode": "MR_FIRST",
    "reason": "涉及 2 个仓库，按规则判定 MR_FIRST",
    "operationId": "operation-uuid"
  }
}
```



|字段|必填|说明|
|---|---|---|
|`projectId`、`taskId`、`reviewBatchId`、`deliveryMode`、`operationId`|是|本次已持久化交付意图的脱敏标识。`operationId` 仅用于前端关联同一次交付状态，不代表凭证或可由客户端调用的操作令牌。|
|`reason`|否|`deliveryReason` 非空时返回。|







收到该事件后，前端应将 Task 展示为“开始自动交付”，并通过 Task 详情、`GET /diff-review`、后续 `delivery.repository.updated` 与 `merge-request.updated` 刷新真实状态。SSE 断线或乱序时，以查询接口为准。



**项目级新增事件（前端 SSE 需求清单 ①，同一项目流）：**







- `message.created`：有人/Agent 发群消息；payload `{ projectId, groupId, messageId }`

    

- `group.created` / `group.updated` / `group.archived`：群创建/改名/归档；payload `{ projectId, groupId }`

    

- `group.member.updated`：成员进出、Agent 首次进群；payload `{ projectId, groupId }`

    

- `memory.submit-review` / `memory.approved` / `memory.rejected` / `memory.archived`：Memory 审批流转；payload `{ projectId, resourceType: "MEMORY", resourceId, eventVersion, updatedAt }`（§20\.4；前端 eventParser 映射到 memories query，不另发 `memory.updated`）

    

    

    

**团队级事件流（前端 SSE 需求清单 ②，新增端点）：**







- 端点：`GET /api/v1/teams/{teamId}/events`（**团队成员**可订阅；`Content-Type: text/event-stream`）

    

- 事件信封同 §12\.1：`id`=团队内单调递增 `sequenceNo`（`Last-Event-ID` 续传，游标过期 `409 EVENT_CURSOR_EXPIRED`），15 秒心跳

    

- 事件：

    

    - `project.member.added`：成员被拉进项目；payload `{ teamId, projectId }`

        

    - `team.member.updated`：成员加入（接受邀请）/移出团队；payload `{ teamId, userId }`

        

    - `activity.created`：团队动态产生（**暂未单独发布**——团队动态由项目事件聚合，前端收项目流 `task.updated`/`diff.created`/`merge-request.updated` 后刷新 `GET /teams/{teamId}/activities` 即可）

        

        

        

**通知级事件流（前端 SSE 需求清单 ③，新增端点）：**







- 端点：`GET /api/v1/notifications/events`（**当前登录用户**维度；`Content-Type: text/event-stream`）

    

- 事件信封同 §12\.1：`id`=用户内单调递增 `sequenceNo`（`Last-Event-ID` 续传）

    

- 事件：

    

    - `notification.created`：新通知产生（含 `INVITED` 邀请）；payload `{ notificationId, kind }`

        

        

        

事件仅用于刷新界面；客户端恢复连接或收到乱序事件后必须以相应的查询接口为准。受控日志不得包含 Token、密码、GitHub 安装令牌、私钥或未脱敏的环境变量。







SSE 事件 `id` 即项目内单调递增 `sequenceNo`，作为 `Last-Event-ID` 续传游标；输入与审批事件必须包含 `inputRequestId`。







**各事件 Payload 示例**：



`task.updated`（Task 状态变化）：



```JSON
{
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "requirementGroupId": "group-uuid",
  "status": "RUNNING",
  "workspaceId": "workspace-uuid",
  "timestamp": "2026-08-12T10:00:00Z"
}
```



`task-step.updated`（TaskStep 状态变化）：



```JSON
{
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "taskStepId": "step-uuid",
  "sequenceNo": 1,
  "status": "RUNNING",
  "timestamp": "2026-08-12T10:00:00Z"
}
```



`task-run.updated`（TaskRun 状态变化）：



```JSON
{
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "taskStepId": "step-uuid",
  "taskRunId": "task-run-uuid",
  "status": "RUNNING",
  "sequence": 0,
  "timestamp": "2026-08-12T10:00:00Z"
}
```



`input-required` / `approval-required`（人机输入/审批）：



```JSON
{
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "taskStepId": "step-uuid",
  "taskRunId": "task-run-uuid",
  "inputRequestId": "request-uuid",
  "kind": "INPUT",
  "status": "PENDING",
  "prompt": "请补充验收说明",
  "timestamp": "2026-08-12T10:00:00Z"
}
```



其中 `kind` 为 `INPUT`（`input-required`）或 `APPROVAL`（`approval-required`）；`inputRequestId` 即事件 `resourceId`。



`diff.created`（Diff 快照创建）：



```JSON
{
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "diffId": "diff-uuid",
  "repositoryId": "project-repository-binding-uuid",
  "sourceBranch": "feat/login-api",
  "headCommit": "abc123",
  "status": "PENDING_REVIEW",
  "timestamp": "2026-08-12T10:00:00Z"
}
```



Diff 尚未产生真实提交时 `headCommit` 键省略（payload 中不出现该键）。



### 12\.2 任务运行与执行上下文



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/tasks/{taskId}/task-runs`|Project Member|查询 Task 的执行记录|
|`GET`|`/projects/{projectId}/task-runs/{taskRunId}`|Project Member|获取单次运行的状态、关联步骤和产物摘要|
|`POST`|`/projects/{projectId}/task-runs/{taskRunId}/retry`|发起人或 Project Admin|为失败或已取消的运行创建一次新的 `TaskRun`|
|`POST`|`/projects/{projectId}/task-runs/{taskRunId}/cancel`|发起人或 Project Admin|取消未完成运行，服务端仅在安全检查点终止|
|`GET`|`/projects/{projectId}/task-runs/{taskRunId}/logs`|Project Member|游标读取已脱敏的执行日志|
|`GET`|`/projects/{projectId}/task-runs/{taskRunId}/execution-context`|Project Member|读取 Workspace 与 Sandbox 的只读状态摘要|
|`GET`|`/projects/{projectId}/task-runs/{taskRunId}/input-requests`|Project Member|查询运行期间发起的人机输入请求|
|`POST`|`/projects/{projectId}/task-runs/{taskRunId}/input-requests/{requestId}/reply`|发起人或 Project Admin|回答 `WAITING_INPUT` 请求|
|`POST`|`/projects/{projectId}/task-runs/{taskRunId}/input-requests/{requestId}/approve`|Project Admin|批准 `WAITING_APPROVAL` 请求|
|`POST`|`/projects/{projectId}/task-runs/{taskRunId}/input-requests/{requestId}/reject`|Project Admin|拒绝 `WAITING_APPROVAL` 请求|
|`retry` 只接受状态为 `FAILED`、`CANCELLED` 或 `BLOCKED` 的运行；原运行不可重置。响应中的新运行应包含 `retryOfTaskRunId`。||||
|输入请求最小响应包含 `id`、`taskRunId`、`kind`、`status`、`prompt`、可选 `options` 与 `createdAt`；回复请求为 `{"answer":{"value":"main"}}`，批准或拒绝请求为 `{"reason":"允许在受控 Sandbox 内执行测试"}`。回复或批准后服务端才可恢复 `RUNNING`；拒绝后服务端进入 `BLOCKED` 或安全取消，客户端不得直接改写运行状态。||||
|`execution-context` 仅返回 `workspaceId`、`sandboxStatus`、`repositoryId`、`baseRef`、`headRef`、`startedAt` 与 `expiresAt`，不得返回宿主机路径、容器控制入口或任何凭据。||||
|**TaskRun 响应示例**（`GET /tasks/{taskId}/task-runs` 列表项 / `GET /task-runs/{taskRunId}` 详情）：||||



```JSON
{
  "id": "task-run-uuid",
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "taskStepId": "step-uuid",
  "agentId": "agent-uuid",
  "role": "DEVELOPER",
  "status": "RUNNING",
  "retryOfTaskRunId": null,
  "artifactSummary": {"diffs": {"count": 0, "byStatus": {}}},
  "startedAt": "2026-08-12T10:00:00Z",
  "finishedAt": null,
  "durationMs": 10234,
  "createdAt": "2026-08-12T10:00:00Z",
  "updatedAt": "2026-08-12T10:00:00Z"
}
```



`GET /tasks/{taskId}/task-runs` 列表项只含摘要字段（`id`/`projectId`/`taskId`/`taskStepId`/`agentId`/`role`/`status`/`retryOfTaskRunId`/`createdAt`/`updatedAt`），不含执行时序与产物；详情（`GET /task-runs/{taskRunId}`）才含 `startedAt`/`finishedAt`/`durationMs`/`artifactSummary`。`durationMs` 由 `finishedAt-startedAt` 派生，任一端为空时为 `null`（未开始运行勿用 0 兜底）。



TaskRun 执行步骤查询方式：



- **实时进度**：通过 SSE `task-run.step.progress` 事件（§12\.1，含 `node`/`sequence`/`content`）推送。

    

- **历史步骤**：通过 `GET /task-runs/{taskRunId}/logs` 游标读取脱敏日志。

    

**日志条目（****`LogEntryResponse`****）示例**：



```JSON
{
  "id": "log-uuid",
  "sequence": 12,
  "node": "git",
  "content": "checkout base",
  "timestamp": "2026-08-12T10:00:00Z"
}
```



日志分页游标取上页最后一条的 `sequence`；`node` 为产生日志的执行节点名，单节点运行为空。如需步骤级清单回看，`TaskRun` 详情可附 `steps` 数组（节点状态 `PENDING/RUNNING/PASSED/FAILED/SKIPPED/CANCELLED`，含 `node`/`status`/`startedAt`/`finishedAt`/`durationMs`/可选 `errorCode`），由执行服务提供。



步骤响应中的节点状态为 `PENDING`、`RUNNING`、`PASSED`、`FAILED`、`SKIPPED` 或 `CANCELLED`，并至少包含 `node`、`status`、`startedAt`、`finishedAt`、`durationMs` 与可选的 `errorCode`。运行中的 Task 收到取消请求时，服务端仅在安全检查点停止；不可中断步骤结束前状态保持 `RUNNING` 或 `CANCELLING`。



### 12\.3 Diff 与审查意见



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/diffs`|项目成员|项目级 Diff 列表，支持 `taskId` 过滤与游标分页|
|`GET`|`/projects/{projectId}/diffs/{diffId}`|项目成员|查询 Diff 的变更统计和关联运行摘要|
|`GET`|`/projects/{projectId}/diffs/{diffId}/files`|项目成员|读取文件、hunk 和二进制文件摘要|
|`GET`/`POST`|`/projects/{projectId}/diffs/{diffId}/comments`|项目成员|查询或添加 Diff 审查意见|
|`POST`|`/projects/{projectId}/diffs/{diffId}/accept`|发起人或 Project Admin|接受普通单 Diff；属于 Diff Review Batch 时返回 `409 DIFF_BATCH_REVIEW_REQUIRED`|
|`POST`|`/projects/{projectId}/diffs/{diffId}/reject`|发起人或 Project Admin|拒绝普通单 Diff；属于 Diff Review Batch 时返回 `409 DIFF_BATCH_REVIEW_REQUIRED`|
|`GET`|`/projects/{projectId}/tasks/{taskId}/diff-review`|项目成员|查询 Task 级最终 Diff Review Batch|
|`GET`|`/projects/{projectId}/tasks/{taskId}/diff-review/diffs/{diffId}/patch`|项目成员|读取最终 Diff 批次中指定 Diff 的不可变 Patch|
|`POST`|`/projects/{projectId}/tasks/{taskId}/diff-review/confirm`|Task 发起人或 Project Admin|确认整个最终 Diff 批次并开始逐仓库交付|
|`POST`|`/projects/{projectId}/tasks/{taskId}/diff-review/reject`|Task 发起人或 Project Admin|拒绝整个最终 Diff 批次|
|`POST`|`/projects/{projectId}/tasks/{taskId}/diff-review/retry-delivery`|Task 发起人或 Project Admin|重试尚未成功交付的仓库|



最终 Diff 使用 Task 级 Diff Review Batch 作为审核入口。



属于批次的 Diff 不得调用单 Diff 的 accept/reject 接口。



三个批次写接口都要求携带 Idempotency\-Key。



创建 Diff 由受控执行服务完成，客户端不得伪造其关联的测试结果或文件状态。



reject 请求为 \{"reason":"请补充错误密码场景测试"\}。



行级评论应包含 path、side、line 或 hunkId、body，并绑定 Diff 快照，避免 Diff 更新后评论指向错误代码。



accept 接受 Diff 时由受控 Git 执行器基于被审查快照创建真实 Git 提交，不绕过目标分支的质量门禁，也不等同于合并。



Diff 列表项（GET /diffs，DiffListItemResponse）：



```JSON
{
  "id": "diff-uuid",
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "taskRunId": "task-run-uuid",
  "taskStepId": "step-uuid",
  "requirementGroupId": "group-uuid",
  "workspaceId": "workspace-uuid",
  "repositoryId": "project-repository-binding-uuid",
  "baseCommit": "abc123",
  "sourceBranch": "feat/login-api",
  "headCommit": null,
  "status": "PENDING_REVIEW",
  "changeStats": {"files": 2, "additions": 10, "deletions": 2},
  "createdAt": "2026-08-12T10:00:00Z"
}
```



**Diff 详情**（`GET /diffs/{diffId}`，`DiffResponse`）在列表项基础上增加 `workingTreeHash`、`snapshotKey`、`reviewedBy`、`reviewReason`、`reviewedAt`、`updatedAt`。`taskRunId`/`taskStepId` 记录产出该 Diff 的运行与步骤，`requirementGroupId` 由其所属 Task 派生。



### 12\.4 Test Run 与 Dry Run



|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/projects/{projectId}/test-runs`|Project Member|对指定提交或 Task 发起已启用 Testset 的受控运行|
|||||
|`GET`|`/projects/{projectId}/test-runs/{testRunId}`|Project Member|获取测试运行状态、用例摘要和产物引用|
|`POST`|`/projects/{projectId}/dry-runs`|Project Member|针对源分支和目标分支发起合并前试运行|
|`GET`|`/projects/{projectId}/dry-runs/{dryRunId}/report`|Project Member|获取试运行报告和冲突、测试摘要|

\|`GET`\|`/projects/{projectId}/test-runs`\|Project Member\|按筛选条件查询项目 Test Run 历史列表，支持游标分页\|

\|`GET`\|`/projects/{projectId}/dry-runs`\|Project Member\|按筛选条件查询项目 Dry Run 历史列表，支持游标分页\|



`test-runs` 请求必须提供 `repositoryId`，并且提供 `taskId` 或 `ref` 之一；`testsetIds` 必须属于该仓库且为 `ENABLED`。



`dry-runs` 请求必须提供 `repositoryId`、`sourceRef`、`targetBranch`，可选关联 `taskId`。



受保护分支所需的 Testset 由第 6\.1 节质量门禁决定，调用方不能通过传入较少的 `testsetIds` 跳过它们。

#### 12\.5 TestRun 与 Dry Run 的测试集执行语义（冻结）



普通 `TestRun` 是项目成员主动发起的诊断运行，请求中的 `testsetIds` 决定本次执行哪些已启用 Testset；它不是 Dry Run 的前置步骤。



`POST /projects/{projectId}/dry-runs` 不接收 `testsetIds`。服务端根据 `repositoryId + targetBranch` 查询该目标分支绑定的必选 Testset，并在合并预演成功后自动执行这些 Testset。用户无需先手动运行 TestRun。



Dry Run 报告中的测试结果位于 `report.tests`，结构如下：



```JSON
{
  "status": "PASSED",
  "resolvedHeadCommit": "target-commit-sha",
  "results": [{
    "testsetId": "testset-uuid",
    "status": "PASSED",
    "exitCode": 0,
    "durationMs": 69,
    "failureCode": null
  }]
}
```



普通 TestRun 详情中的同类结构位于 `summary`。无必选 Testset 时返回 `report.tests.status = NOT_REQUIRED` 且 `results = []`；合并冲突时返回 `report.tests.status = SKIPPED`，且不得创建 MR。







受保护分支所需的 Testset 由第 6\.1 节质量门禁决定，调用方不能通过传入较少的 `testsetIds` 跳过它们。

---







## 13\. MR、审查与质量状态







MR 属于仓库，Qgents 将其镜像为项目内记录。创建、同步、人工 CQ 审查和合并均通过本节受控接口触发服务端操作；客户端不持有 GitHub 凭据，也不能直接写入检查结果或绕过分支策略。







|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/merge-requests`|Project Member|查询项目关联 MR，支持仓库、需求群、状态过滤|
|`POST`|`/projects/{projectId}/merge-requests`|Project Member|基于已接受并在远端核验的 Diff 创建 MR|
|`GET`|`/projects/{projectId}/merge-requests/{mergeRequestId}`|Project Member|查询 MR、关联需求群、检查与审查摘要|
|`GET`|`/projects/{projectId}/merge-requests/{mergeRequestId}/checks`|Project Member|查询门禁检查详情|
|`GET`|`/projects/{projectId}/merge-requests/{mergeRequestId}/reviews`|Project Member|查询人工与 AI 审查摘要|
|`POST`|`/projects/{projectId}/merge-requests/{mergeRequestId}/sync`|Project Member|触发从 GitHub 同步 MR 最新状态|
|`POST`|`/projects/{projectId}/merge-requests/{mergeRequestId}/cq-approvals`|Project Member（非作者）|提交一次 CQ\+1 审查|
|`POST`|`/projects/{projectId}/merge-requests/{mergeRequestId}/cq-rejections`|Project Member（非作者）|拒绝 CQ 并给出修改意见|
|`POST`|`/projects/{projectId}/merge-requests/{mergeRequestId}/merge`|Project Admin|通过质量门禁后执行合并|







**创建 MR 请求示例**：



```JSON
{
  "taskId": "task-uuid",
  "repositoryId": "project-repository-binding-uuid",
  "targetBranch": "main",
  "title": "实现邮箱登录"
}
```







服务端从 Task 的持久化 Workspace repository 取得源分支和提交 SHA，并校验源已提交推送、分支仍存在且调用方有项目访问权；不接受客户端提交的 GitHub Token、提交 SHA 或门禁结果。







`sync`、`merge` 和两个 CQ 写操作同样需要 `Idempotency-Key`。







CQ 审查者不得是 MR 作者、Diff 创建者或代表其执行的 Agent；服务端须记录审查者、时间、所审查的 `headCommit` 和理由。新提交推送到 MR 后，旧 CQ\+1 是否失效由目标分支门禁配置决定，并反映到 `qualityGate`。







**MR 详情的最小响应示例**：



```JSON
{
  "data": {
    "id": "mr-uuid",
    "repositoryId": "project-repository-binding-uuid",
    "groupIds": ["group-uuid"],
    "provider": "GITHUB",
    "number": 42,
    "sourceBranch": "feature/login-api",
    "targetBranch": "main",
    "status": "OPEN",
    "headCommit": "abc123",
    "qualityGate": {"status": "PENDING", "requiredChecks": ["TESTSET", "AI_REVIEW", "DRY_RUN", "CQ_PLUS_ONE"]}
  }
}
```







正式合并前的业务规则为：关联 Diff/提交通过指定 Testset，AI 审查与 dry\-run 通过，至少获得一次有效人工 `CQ+1`，且由 `PROJECT_ADMIN` 按仓库策略确认合并。是否满足这些规则由 `qualityGate.status` 表示。







`merge` 在条件不满足时返回 `409 QUALITY_GATE_NOT_PASSED`；接口不存在跳过门禁、伪造检查结果或以手工结果覆盖自动检查的能力。







---







## 14\. 后续接口边界







以下增强能力暂不提供公开接口，客户端不得假定其路径或字段：







- 自定义团队工作流模板及其节点配置；

    

    

    

- 用户直接操作 Workspace、Docker Engine、Sandbox 生命周期、文件读取/写入；

    

    

    

- Git 分支创建、推送和 Patch 应用的直接操作；

    

    

    

- 绕过质量门禁或手工改写 Testset、AI Review、Dry Run 结果；

    

    

    

- WebSocket、离线同步与移动端推送（SSE 见第 12\.1 节）。

    

    

    

这些能力接入时必须复用本文件的项目、需求群、仓库、Testset、Memory、Skill、Task/TaskStep 和质量门禁资源模型，并遵循“不得由单一 Agent 独自完成完整交付”的产品约束。







## 15\. v1\.3\.0 更新：任务级总 Diff 确认与多仓库交付







### 



### 15\.1 Task 状态流转更新







任务在 REVIEWER 成功后按已定型的 `deliveryMode` 分叉：







```Plain Text
DIFF_FIRST:
RUNNING -> WAITING_DIFF_CONFIRMATION -> DELIVERING -> SUCCEEDED / DELIVERY_FAILED

MR_FIRST:
RUNNING -> DELIVERING -> SUCCEEDED / DELIVERY_FAILED
```







`MR_FIRST` 的 `DELIVERING` 前必须已经持久化最终 Diff 快照、Task 级 `DiffReviewBatch`、交付操作标识和租约；不存在“先显示已交付、后异步补建快照”的窗口。







### 15\.2 交付模式与授权来源







`reviewStatus=ACCEPTED` 的统一语义是“该批次已获准进入交付”；**谁授权交付**由只读字段 `confirmationSource` 表达：







|交付模式|批次状态|`confirmationSource`|用户体验|
|---|---|---|---|
|`DIFF_FIRST`|初始为 `PENDING_CONFIRMATION`；用户确认后为 `ACCEPTED`|`USER`|展示 Diff 确认/拒绝操作。|
|`MR_FIRST`|创建即为 `ACCEPTED`|`SYSTEM`|不展示 Diff 确认/拒绝操作，展示“自动交付”与 MR 交付进度。|







`SYSTEM` 表示系统依据 MR\_FIRST 规则自动授权，不表示用户已经确认 Diff。前端、群聊卡片、审计记录和文案不得将 `confirmationSource=SYSTEM` 展示为“用户已确认”。







系统只允许 MR\_FIRST 内部流程写入 `SYSTEM`；客户端请求体不包含该字段。历史批次和 DIFF\_FIRST 用户确认批次均为 `USER`。







两种模式都使用真实的 Task 级 Diff 快照。交付开始前，服务端必须完成所有目标仓库的 `baseCommit`、`headCommit`、`diffHash`、Project 归属和 Workspace 写入租约预检；任一仓库不满足预检时，本轮不得提交任何仓库。跨仓库 Commit、Push 和 PR 创建不是分布式事务，允许后续出现部分交付成功，并支持只重试失败仓库。



### 15\.3 总 Diff 查询与审核接口







#### 15\.3\.1 获取任务级最终 Diff







**GET /api/v1/projects/\{projectId\}/tasks/\{taskId\}/diff\-review**







- **响应**: `200 OK`，返回 `DiffReviewBatchResponse`，字段和空批次行为见 §15\.6。

    

    

    

#### 15\.3\.2 确认总 Diff







**POST /api/v1/projects/\{projectId\}/tasks/\{taskId\}/diff\-review/confirm**







- **Headers**：必须携带 `Idempotency-Key`。

    

- **适用范围**：仅 `reviewStatus=PENDING_CONFIRMATION` 且 `confirmationSource=USER` 的 DIFF\_FIRST 批次。

    

- **响应**：`200 OK`，返回 `reviewStatus=ACCEPTED`、`confirmationSource=USER` 的 `DiffReviewBatchResponse`。服务端完成全部仓库预检后进入 `DELIVERING`，再逐仓库交付。

    

- **错误**：MR\_FIRST 的 `ACCEPTED + SYSTEM` 批次不接受用户确认，返回 `409 DIFF_REVIEW_NOT_DECIDABLE`。

    

    

    

#### 15\.3\.3 拒绝总 Diff







**POST /api/v1/projects/\{projectId\}/tasks/\{taskId\}/diff\-review/reject**







- **Headers**：必须携带 `Idempotency-Key`。

    

- **请求体**：`{"reason":"请补充错误密码场景测试"}`；`reason` 必填，最多 4000 个字符。

    

- **适用范围**：仅 `PENDING_CONFIRMATION + USER` 的 DIFF\_FIRST 批次。

    

- **响应**：`200 OK`，返回 `reviewStatus=REJECTED` 的 `DiffReviewBatchResponse`。拒绝后不进行 Commit、Push 或 PR 创建。

    

- **错误**：MR\_FIRST 的 `ACCEPTED + SYSTEM` 批次不接受用户拒绝，返回 `409 DIFF_REVIEW_NOT_DECIDABLE`。

    

    

    

#### 15\.3\.4 重试失败的交付







**POST /api/v1/projects/\{projectId\}/tasks/\{taskId\}/diff\-review/retry\-delivery**







- **Headers**：必须携带 `Idempotency-Key`。

    

- **适用范围**：`reviewStatus=ACCEPTED` 且 `deliveryStatus` 为 `PARTIALLY_DELIVERED` 或 `FAILED` 的批次，适用于 `confirmationSource=USER` 和 `confirmationSource=SYSTEM`。

    

- **响应**：`200 OK`，返回重试后的 `DiffReviewBatchResponse`。已经为 `MR_CREATED` 的仓库不会重复 Commit、Push 或创建 Open PR。

    

#### 15\.3\.5 读取单仓库原始 Patch







**GET /api/v1/projects/\{projectId\}/tasks/\{taskId\}/diff\-review/diffs/\{diffId\}/patch**







- **权限**: 项目成员。

    

    

    

- **响应**: `200 OK`，返回 `{"diffId":"...","repositoryId":"...","patch":"..."}`。调用方只能读取当前任务最终 Diff 批次内的 Patch。

    

    

    

### 15\.4 SSE 事件补充







以下事件的 Payload 均为脱敏摘要，格式如：







`projectId`、`taskId` 和事件对应资源 ID 是所有新增事件的最小定位字段；具体 payload 见 §15\.6。







- diff\-review\.created: 总 Diff 快照生成完毕

    

    

    

- task\.awaiting\-diff\-confirmation: 任务等待用户确认 Diff

    

    

    

- diff\-review\.confirmed: 用户确认了总 Diff

    

    

    

- diff\-review\.rejected: 用户拒绝了总 Diff

    

    



- delivery\.repository\.updated: 某仓库已完成交付，状态为 `MR_CREATED`

    

    

    

- delivery\.failed: 交付失败

    

    

    

- delivery\.completed: 所有仓库交付完成

    

    

    

### 15\.5 单仓库 Diff 接口调整







- 如果单 Diff 属于某个 Diff Review Batch，那么调用原有的 `POST /diffs/{diffId}/accept` 或 `POST /diffs/{diffId}/reject` 将返回 `409 DIFF_BATCH_REVIEW_REQUIRED`，要求用户走总 Diff 确认接口。

    

    

    

### 15\.6 前端对接契约







#### 15\.6\.1 执行产物时间线







|方法|路径|权限|响应|
|---|---|---|---|
|`GET`|`/api/v1/projects/{projectId}/tasks/{taskId}/artifacts`|项目成员|`200 OK`，按 `sequenceNo` 升序返回 Artifact 数组|







Artifact 响应结构：







\{







"id": "artifact\-uuid",







"taskId": "task\-uuid",







"taskRunId": "task\-run\-uuid",







"taskStepId": "step\-uuid",







"sequenceNo": 2,







"artifactType": "CODING",







"summary": \{"title": "完成登录接口实现"\},







"createdAt": "2026\-08\-14T10:00:00Z"







\}







`artifactType` 取值为 `PLAN`、`CODING`、`TESTING`、`REVIEWING`。`PLAN` 只关联 `taskId`，因此 `taskRunId` 和 `taskStepId` 为 `null`；其余类型同时关联 Task、TaskRun 与 TaskStep。`summary` 是已脱敏的展示摘要，不包含命令原文、凭据、环境变量或宿主机绝对路径。PLAN 产物的 `summary.steps` 每步可含 `acceptanceNotes`（该步骤的自然语言验收标准，可选）与 `machineAssertions`（可选的结构化预期断言数组，元素为 `{"type":"EXISTS|EMPTY|LINES_EQ|LINES_GT|LINES_LT|CONTAINS|NOT_CONTAINS","file":"相对路径","value":"…"}`）；二者为预期信号而非最终裁决，不参与服务端通过/失败判定。







#### 15\.6\.2 最终 Diff 批次







`GET /api/v1/projects/{projectId}/tasks/{taskId}/diff-review` 在最终 Diff 尚未生成时返回 `404 DIFF_REVIEW_NOT_FOUND`，不是空数组或 `204`。







|字段|取值|前端含义|
|---|---|---|
|`reviewStatus`|`PENDING_CONFIRMATION`、`ACCEPTED`、`REJECTED`|批次是否已获准进入交付。`ACCEPTED` 不等同于“用户已确认”。|
|`confirmationSource`|`USER`、`SYSTEM`|授权来源。`USER` 表示用户确认；`SYSTEM` 表示 MR\_FIRST 自动授权。|
|`deliveryStatus`|`NOT_STARTED`、`DELIVERING`、`DELIVERED`、`PARTIALLY_DELIVERED`、`FAILED`|总体交付结果。|
|单仓库持久化状态|`NOT_STARTED`、`COMMITTED`、`MR_CREATED`、`FAILED`|通过 `repositoryDeliveries[]` 返回，用于交付进度与重试。|







`DiffReviewBatchResponse`：







```JSON
{
  "id": "review-batch-uuid",
  "taskId": "task-uuid",
  "reviewStatus": "ACCEPTED",
  "confirmationSource": "SYSTEM",
  "deliveryStatus": "DELIVERING",
  "aggregateHash": "sha256-...",
  "reviewReason": "MR_FIRST 自动交付（REVIEWER 通过）",
  "diffs": [],
  "repositoryDeliveries": []
}
```







`confirmationSource` 为只读字段。前端展示规则：







- `PENDING_CONFIRMATION + USER`：显示确认和拒绝操作；

    

- `ACCEPTED + USER`：显示“已由用户确认”；

    

- `ACCEPTED + SYSTEM`：显示“自动交付”，不得显示确认/拒绝操作或“用户已确认”；

    

- 任意来源的 `PARTIALLY_DELIVERED/FAILED`：根据 `repositoryDeliveries[]` 展示失败仓库和重试入口。

    

    

    

批次内 Diff 与普通单 Diff 的关联由服务端 `reviewBatchId` 持久化字段表达。该字段当前不在公开 `DiffResponse` 或 `DiffListItemResponse` 中；前端应以 Task 级 `diff-review` 接口作为最终 Diff 与交付状态入口。



#### 15\.6\.3 确认、拒绝与重试







三个写接口都由全局幂等过滤器要求 `Idempotency-Key`。缺失时返回 `400 IDEMPOTENCY_KEY_REQUIRED`；同一键但请求体不同返回 `409 IDEMPOTENCY_KEY_REUSED`；同一键与相同请求会回放首次成功响应。







|接口|成功响应|主要业务错误|
|---|---|---|
|`POST .../confirm`|`200` \+ `DiffReviewBatchResponse`|`403 DIFF_REVIEW_FORBIDDEN`、`404 DIFF_REVIEW_NOT_FOUND`、`409 DIFF_REVIEW_NOT_DECIDABLE`、`409 DIFF_SNAPSHOT_STALE`|
|`POST .../reject`|`200` \+ `DiffReviewBatchResponse`|`400 DIFF_REJECT_REASON_REQUIRED`、`403 DIFF_REVIEW_FORBIDDEN`、`409 DIFF_REVIEW_NOT_DECIDABLE`|
|`POST .../retry-delivery`|`200` \+ `DiffReviewBatchResponse`|`403 DIFF_REVIEW_FORBIDDEN`、`409 DIFF_DELIVERY_NOT_RETRYABLE`|







`confirm` 成功表示 `confirmationSource=USER` 的用户已接受整个快照；MR\_FIRST 的自动交付以 `confirmationSource=SYSTEM` 表示，不经过 `confirm` 接口。无论授权来源是什么，`ACCEPTED` 都不表示所有 PR 已创建；前端必须结合 `deliveryStatus`、`repositoryDeliveries[]` 和后续 SSE 展示真实交付结果。







#### 15\.6\.4 新增 SSE 事件与 payload







SSE 事件仍使用 §12\.1 的事件信封；以下为 `data` 中的业务 payload 字段。事件到达后可重新请求对应的 Task、MR 或 Diff Review 资源。







|事件|payload 字段|推荐刷新资源|
|---|---|---|
|`delivery.started`|`projectId`、`taskId`、`reviewBatchId`、`deliveryMode`、`operationId`，可选 `reason`|Task、Task Diff Review|
|`diff.created`|`projectId`、`taskId`、`diffId`、`repositoryId`、`status`|Task Diff Review|
|`diff-review.created`|`projectId`、`taskId`、`reviewBatchId`、`reviewStatus`、`aggregateHash`|Task、Task Diff Review|
|`task.awaiting-diff-confirmation`|`projectId`、`taskId`、`reviewBatchId`|Task、Task Diff Review|
|`diff-review.confirmed`|`projectId`、`taskId`、`reviewBatchId`|Task、Task Diff Review|
|`diff-review.rejected`|`projectId`、`taskId`、`reviewBatchId`|Task、Task Diff Review|
|`delivery.repository.updated`|`projectId`、`taskId`、`diffId`、`deliveryStatus`|Task Diff Review、MR 列表|
|`delivery.completed`|`projectId`、`taskId`、`reviewBatchId`、`deliveryStatus`|Task、Task Diff Review、MR 列表|
|`delivery.failed`|`projectId`、`taskId`、`reviewBatchId`、`deliveryStatus`|Task、Task Diff Review、MR 列表|
|`task.diff-review.failed`|`projectId`、`taskId`、`reviewBatchId`、`reason`|Task、Task Diff Review、错误提示|
|`diff-review.skipped`|`projectId`、`taskId`、`reason`|Task|



`diff-review.skipped` 的 `reason=FINAL_DIFF_EMPTY` 表示任务已成功完成但没有未提交代码变更：后端不会创建空 Diff 或 DiffReviewBatch，Task 状态为 `SUCCEEDED`。客户端应刷新 Task 并展示“已完成，无代码变更”空态；此后查询 Task Diff Review 返回 `404 DIFF_REVIEW_NOT_FOUND` 是正常业务结果，不得显示为系统错误、交付失败或重试入口。



`delivery.started` 仅表示交付已开始，不表示 Commit、Push 或 PR 已成功。`delivery.repository.updated` 的 `deliveryStatus=MR_CREATED` 才表示该仓库已存在真实 PR；最终以 `delivery.completed`、`delivery.failed` 和查询结果为准。







## 16\. v1\.4\.0 更新：任务中心与任务详情展示字段







基线 v1\.3\.0。本小节补充任务中心/任务详情/执行流程/总 Diff 交付摘要的展示契约，







使前端一次拿到卡片所需摘要，避免逐条 N\+1 查询。所有摘要 DTO 均为只读展示，不携带敏感信息。







### 16\.1 任务列表（增强）







**GET /api/v1/projects/\{projectId\}/tasks**







新增查询参数（原有参数沿用）：







|参数|类型|说明|
|---|---|---|
|`groupId`|UUID|按需求群筛选（可选）|
|`status`|string|按任务状态筛选（可选）|
|`createdBy`|UUID|按发起人筛选（可选）|
|`repositoryId`|UUID|按项目仓库绑定 ID 筛选（SQL 层 IN 子查询，避免只过滤当前页漏数据）|
|`keyword`|string|按不区分大小写包含匹配 `displayCode`/`title`/`requirement`/需求群名/创建人/绑定仓库展示名与全名（v2\.0\.6 补充；超 100 字符返回 422）|
|`cursor`|string|游标分页（上一页最后一项 `id`）|
|`limit`|int|每页条数，默认 20，最大 100|







响应改为分页结构 `{ data, page, requestId }`，`data` 为 `TaskListItemResponse[]`：



```JSON
{
  "id": "task-uuid",
  "displayCode": "T-1024",
  "projectId": "project-uuid",
  "title": "登录接口实现",
  "requirementSummary": "实现账号密码登录并签发 JWT",
  "status": "RUNNING",
  "priority": null,
  "deliveryMode": "DIFF_FIRST",
  "requirementGroup": { "id": "group-uuid", "name": "登录功能", "status": "ACTIVE" },
  "createdByUser": { "id": "user-uuid", "displayName": "陈同学", "avatarUrl": null },
  "repositories": [
    { "repositoryId": "binding-uuid", "name": "auth-service", "fullName": "qgents/auth-service",
      "provider": "GITHUB", "defaultBranch": "main", "baseRef": "main", "baseCommit": "abc123",
      "sourceBranch": "feat/login-api", "headCommit": null }
  ],
  "executionSummary": { "totalSteps": 4, "pendingSteps": 1, "runningSteps": 1, "waitingSteps": 0,
    "blockedSteps": 0, "succeededSteps": 2, "failedSteps": 0, "currentStage": "DEVELOPER",
    "currentStageTitle": "后端接口开发", "requiresUserAction": false },
  "attention": null,
  "createdAt": "2026-08-14T10:00:00Z",
  "updatedAt": "2026-08-14T10:42:00Z"
}
```







字段约定：







- `displayCode`：项目内唯一、创建后不可变的展示编号（`T-<序号>`）；所有 API 关联仍使用 `id`。

    

    

    

- `priority`：**后端明确不提供**，恒为 `null`，前端应移除该展示。

    

    

    

- `requirementSummary`：服务端截断的纯文本摘要（≤200 字符，不含 HTML）。

    

    

    

- `executionSummary`：由 TaskStep 真实状态聚合；`waitingSteps`/`blockedSteps` 取每步骤最新 TaskRun 状态

    

    

    

（WAITING\_INPUT/WAITING\_APPROVAL→等待，BLOCKED→阻塞）；`currentStage` 使用正式 TaskStep role；







`requiresUserAction = attention != null`。不返回伪造的进度百分比。







- `attention`：待处理事项，无则 `null`。`kind` 枚举：

    

    

    

`INPUT_REQUIRED/APPROVAL_REQUIRED/BLOCKED/EXECUTION_FAILED/DIFF_CONFIRMATION_REQUIRED/DELIVERY_FAILED`。







- `repositories[].repositoryId` 恒为 `project_repositories.id`；`baseRef` 为 Workspace 初始化基准分支

    

    

    

（当前以项目默认分支表示）；`headCommit` 未提交前为 `null`。







### 16\.2 任务详情







**GET /api/v1/projects/\{projectId\}/tasks/\{taskId\}** 返回 `TaskDetailResponse`（在列表项基础上补充）：







- `requirement`：完整需求文本。

    

    

    

- `acceptanceCriteria[]`：Task 级验收标准 `{ id, title, description, status }`，status 枚举

    

    

    

`PENDING/SATISFIED/UNSATISFIED/NOT_APPLICABLE`；验收状态由后端结果或检查资源决定。







**当前尚无验收标准生产者，返回空数组（契约先行，不伪造）。**







- `workspace`：`{ id, status, repositories[] }`，Workspace 生命周期状态 \+ 仓库摘要。

    

    

    

- `capabilities`：当前用户对当前任务的操作能力（后端派生，前端不猜测）：

    

    

    

`canCancel`、`canReplacePendingStepAgent`、`canConfirmDiffReview`、`canRejectDiffReview`、`canRetryDelivery`，







各能力可携带 `xxxDisabledReason` 错误码（如 `TASK_NOT_CANCELLABLE`、`DIFF_REVIEW_NOT_DECIDABLE`）。







- `artifactSummary`：`{ total, byType }`，按产物类型（PLAN/CODING/TESTING/REVIEWING）计数。

    

    

    

- `deliveryMode`：`DIFF_FIRST` 或 `MR_FIRST`。任务交付模式由服务端判定并在计划物化后固定，前端只展示，不自行修改。

    

    

    

- `deliveryReason`：服务端判定交付模式的理由；任务尚未完成模式判定时为 `null`。

    

    

    

- `diffReviewSummary`：`{ available, diffId, reviewStatus, deliveryStatus, repositoryCount, filesChanged, additions, deletions }`。

    

    

    

`diffReviewSummary` 不重复返回 `confirmationSource`。当 `available=true` 时，前端必须通过：







`GET /api/v1/projects/{projectId}/tasks/{taskId}/diff-review`







获取完整的 `DiffReviewBatchResponse`，包括 `confirmationSource`、`repositoryDeliveries[]` 和真实 MR 信息。







`diffId` 为可空字段，表示批次内代表性 Diff 的 ID，不是 `DiffReviewBatch` 的 ID；无批次或无 Diff 时为 `null`。







- `sourceMessage`：`{ id, sender: UserSummary, textExcerpt, createdAt }`，由 `triggerMessageId` 派生；

    

    

    

仅返回脱敏文本摘要，附件走现有消息/附件权限接口。







- `triggerMessageId`：来源消息 ID，无则 `null`。

    

    

    

### 16\.3 任务步骤列表（新增）







**GET /api/v1/projects/\{projectId\}/tasks/\{taskId\}/steps** 返回 `TaskStepListItemResponse[]`：



```JSON
{
  "id": "step-uuid", "taskId": "task-uuid", "sequenceNo": 2, "title": "后端接口开发",
  "description": "实现登录接口、JWT 签发和错误处理", "role": "DEVELOPER",
  "agent": { "id": "agent-uuid", "name": "Backend Developer Agent", "role": "DEVELOPER",
    "avatarUrl": null, "status": "ACTIVE" },
  "repository": { "repositoryId": "binding-uuid", "name": "auth-service", "sourceBranch": "feat/login-api" },
  "dependencies": ["step-uuid-1"], "status": "RUNNING",
  "acceptanceNotes": "覆盖成功、错误密码和用户不存在场景",
  "latestRun": { "id": "run-uuid", "status": "RUNNING", "startedAt": "…", "finishedAt": null, "durationMs": null },
  "runCount": 1,
  "startedAt": "2026-08-14T10:16:00Z", "finishedAt": null,
  "createdAt": "2026-08-14T10:05:00Z", "updatedAt": "2026-08-14T10:20:00Z"
}
```







- `description` 为步骤指令的脱敏展示；`acceptanceNotes` 为步骤验收条件。

    

    

    

- `latestRun` 为最新一次 TaskRun 摘要；`runCount` 为该步骤累计执行次数。

    

    

    

- `startedAt/finishedAt` 由该步骤所有 TaskRun 的最早开始/最晚结束时间派生，无运行时为 `null`。

    

    

    

- **结构化 checkpoints 后端明确不提供**（执行器未持久化步骤内部检查项），前端只展示 `acceptanceNotes`。

    

    

    

### 16\.4 任务运行列表（增强）







**GET /api/v1/projects/\{projectId\}/tasks/\{taskId\}/task\-runs** 返回 `TaskRunListItemResponse[]`（较旧摘要新增）：







- `taskStepTitle`：所属步骤标题。

    

    

    

- `agent`：执行 Agent 摘要（未分配为 `null`）。

    

    

    

- `statusSummary`：脱敏状态摘要（如“等待用户补充输入”），不返回日志原文/Prompt/Token/环境变量。

    

    

    

- `statusReason`：等待/阻塞/失败原因 `{ code, title, summary, retryable, occurredAt }`，无等待或失败时为 `null`。

    

    

    

    - `code` 枚举：`INPUT_REQUIRED/APPROVAL_REQUIRED/BLOCKED/EXECUTION_FAILED/STARTUP_FAILED/CANCELLED`。

        

        

        

    - `retryable` 由后端按状态机判断（FAILED/CANCELLED/BLOCKED 可重试）。

    - 启动失败（任务尚未创建 TaskRun 即中止）时 `code=STARTUP_FAILED`，`failureCode` 返回稳定失败码（如 `GIT_BRANCH_NOT_FOUND`、`GIT_BASE_REF_NOT_FOUND`、`SANDBOX_WORKER_UNAVAILABLE`）；`GIT_BRANCH_NOT_FOUND` 的 `summary` 会携带仓库与基线分支名（如「仓库 CloudPlayerBaby/test01 不存在基线分支 develop」），前端可据此提供「修改基线分支后重新发起任务」入口。

        

        

        

- `startedAt/finishedAt/durationMs`：执行时间。

    

    

    

- `artifactSummary`：`{ total, diffCount }`，该运行自身产出的执行产物与 Diff 数量。

    

    

    

**GET /api/v1/projects/\{projectId\}/task\-runs/\{taskRunId\}**（详情）同样增加 `statusReason`。







### 16\.5 任务执行产物（增强）







**GET /api/v1/projects/\{projectId\}/tasks/\{taskId\}/artifacts** 在保留 `summary` 的同时增加结构化展示字段：







- `title`：由产物类型派生的稳定标题（PLAN→“计划”/CODING→“代码编写”/TESTING→“测试”/REVIEWING→“代码审查”）。

    

    

    

- `status`：由执行结果 `outcome` 派生（SUCCEEDED→SUCCEEDED，其余→FAILED；无 outcome 时为 `null`）。

    

    

    

- `description`：产物摘要中的脱敏说明（截断 ≤200 字符），无则 `null`。

    

    

    

- `resources`：受权限保护的内部资源引用列表 `{ resourceType, resourceId, title }`；

    

    

    

当前无校验过的内部资源引用时返回空数组，不伪造。







### 16\.6 总 Diff 逐仓库交付详情（增强）







**GET /api/v1/projects/\{projectId\}/tasks/\{taskId\}/diff\-review** 响应新增 `repositoryDeliveries[]`：



```JSON
{
  "repositoryId": "binding-uuid",
  "repositoryName": "auth-service",
  "diffId": "diff-uuid",
  "deliveryStatus": "MR_CREATED",
  "failureCode": null,
  "failureReason": null,
  "mergeRequest": { "id": "mr-uuid", "number": 128, "title": "feat: implement login API",
    "status": "OPEN", "webUrl": "https://github.com/qgents/auth-service/pull/128" },
  "updatedAt": "2026-08-14T11:00:00Z"
}
```







- `deliveryStatus` 枚举：`NOT_STARTED/COMMITTED/MR_CREATED/FAILED`。

    

    

    

- `failureCode` 预留（当前为 `null`）；`failureReason` 为脱敏失败原因，成功时为 `null`。

    

    

    

- `mergeRequest` 仅在 `MR_CREATED` 且存在真实 MR 记录时返回；`webUrl` 由 GitHub 仓库镜像与真实 PR 编号构造，

    

    

    

不可可靠构造时为 `null`。MR 完整审查与合并仍由 MR 模块负责，此处仅展示摘要与入口。







- 批次 `deliveryStatus` 为 `PARTIALLY_DELIVERED` 时，通过 `repositoryDeliveries` 指出成功与失败仓库。

    

    

    

### 16\.7 明确不提供项







- `discussionSummary`（无“消息→Task”归属关系，仅提供来源消息 `sourceMessage`）。

    

    

    

- 决策记录、执行时 Skill/Memory 版本快照、TaskStep checkpoints、人工 Reviewer 分配（无真实数据源）。

    

    

    

- 进度百分比（禁止伪造）；`priority`（产品暂无优先级业务）。

    

    

    

## 17\. v1\.6\.0 补充：消息 senderName 与 Testset 列表







### 17\.1 消息列表新增 senderName







`GET /projects/{projectId}/groups/{groupId}/messages` 返回的 `MessageResponse` 新增 `senderName` 字段：







- 用户消息：`displayName`（账号注销等异常场景返回稳定占位“已注销用户”，不返回空字符串）；

    

    

    

- Agent 消息：`Agent.name`；

    

    

    

- SYSTEM 消息：`null`。

    

    

    

后端在消息列表接口一次性批量加载发送者名称，不引入逐条消息 N\+1。







### 17\.2 Testset 列表接口已实现







`GET /projects/{projectId}/testsets`（可选 `repositoryId` 过滤，项目成员）已实现，返回 `TestsetResponse[]`：



```JSON
{
  "id": "testset-uuid",
  "name": "后端单元测试",
  "repositoryId": "project-repository-binding-uuid",
  "status": "ENABLED",
  "enabled": true,
  "definition": { "command": "./mvnw test", "timeoutSeconds": 900, "passRule": { "type": "EXIT_CODE", "expected": 0 } },
  "createdBy": "user-uuid",
  "createdAt": "2026-08-14T10:00:00Z",
  "updatedAt": "2026-08-14T10:00:00Z"
}
```







创建/修改/启停/删除仍由 Testset 管理功能后续提供（契约 §10 已声明）。







---







## 18\.v1\.6\.0 补充： 附件直传与下载接口（请求/响应契约补充）







前端图片/文件链路需要：直传 URL → 上传 → 得到最终展示 URL → 填入 `content.url`。本小节给出三个接口的完整请求/响应。







### 18\.1 创建直传凭证 `POST /projects/{projectId}/attachments`（201）







**请求体**



```JSON
{
  "fileName": "login.png",      // 必填，≤512
  "contentType": "image/png",   // 可空，≤255
  "sizeBytes": 204800           // 必填，>0，≤ app.attachment-max-size-bytes（默认 50MB）
}
```







**响应体**



```JSON
{
  "attachmentId": "uuid",
  "uploadUrl": "https://qgents.oss-cn-guangzhou.aliyuncs.com/...?...签名...",  // 预签名 PUT 地址，直接把文件字节 PUT 到此
  "method": "PUT",
  "expiresAt": "2026-08-15T10:15:00Z",      // 上传凭证过期（默认 900 秒）
  "headers": {}                              // 当前为空（预签名 PUT 不要求固定请求头）
}
```







### 18\.2 确认上传完成 `POST /projects/{projectId}/attachments/{attachmentId}/confirm`（200）



```JSON
{ "attachmentId": "uuid", "status": "READY" }
```







服务端校验对象已真实存在于 OSS 后置 READY。







### 18\.3 获取临时下载地址 `GET /projects/{projectId}/attachments/{attachmentId}/download-url`（200）



```JSON
{ "attachmentId": "uuid", "downloadUrl": "https://...预签名 GET...", "expiresAt": "2026-08-15T10:15:00Z" }
```







`downloadUrl` 默认 900 秒过期。







### 18\.4 展示 URL（content\.url）说明







- `uploadUrl` 是**预签名 PUT 地址，不是展示 URL**。

    

    

    

- 对象键（objectKey）= `projects/{projectId}/attachments/{attachmentId}`。

    

    

    

- 展示 URL 二选一：

    

    

    

    1. **桶公共读**：`https://{bucket}.{endpoint}/{objectKey}`（稳定，适合聊天图片/文件长期展示）；

        

        

        

    2. **临时地址**：调 §18\.3 `download-url`（15 分钟过期，不适合长期引用）。

        

        

        

- **建议**：聊天图片/文件引用用**公共读稳定地址**；若保持私有桶，需要后端补充长期展示地址机制（待定，见 §19）。

    

    

    

### 18\.5 稳定展示地址（已实现：鉴权下载代理）







`GET /projects/{projectId}/attachments/{attachmentId}/content`







- 鉴权后流式返回附件内容（项目成员 \+ 附件归属校验），**长期稳定、不过期**；

    

    

    

- 图片（`image/*`）走 `Content-Disposition: inline`（浏览器直接渲染），其余走 `attachment`（可下载）；

    

    

    

- **`content.url`**\*\* 直接填本路径\*\*：`/projects/{projectId}/attachments/{attachmentId}/content`（前端自行拼绝对地址）；

    

    

    

- 相比 §18\.3 download\-url（15 分钟过期）与公共读（无鉴权），本方式保持项目隔离、不依赖桶 ACL。

    

    

    

---







## 19\. v1\.7\.0 补充：团队邀请（收件人视角）与团队最近动态







落地前端清单的「我的团队邀请」与「团队首页最近动态」。团队最近动态基于项目 `events` 表聚合，**只覆盖最近 24 小时**（events 保留期）。







### 19\.1 接口总览







|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/team-invitations`|登录用户|我收到的待处理团队邀请（分页）|
|`POST`|`/team-invitations/{reference}/accept`|登录用户|接受团队邀请（`reference`=邀请 id 或明文 token）|
|`GET`|`/teams/{teamId}/activities`|团队成员|团队最近动态（分页，覆盖最近 24 小时）|







### 19\.2 我的团队邀请 `GET /team-invitations`







**请求参数**：`cursor`（分页游标，首次不传）、`limit`（默认 30，范围 1\.\.100）。







**响应（****`ReceivedInvitationResponse`****）**：







|字段|类型|说明|
|---|---|---|
|`id`|string|邀请记录 id（UUIDv7），**接受时用此值**|
|`teamId`|string|邀请来源团队|
|`teamName`|string|团队名（展示用）|
|`role`|string|恒为 `TEAM_MEMBER`（邀请创建仅支持该角色）|
|`inviterDisplayName`|string|邀请人显示名|
|`status`|string|`PENDING` / `EXPIRED`（PENDING 但已过期的按 EXPIRED 展示）|
|`expiresAt`|string|ISO8601 UTC 过期时间|
|`createdAt`|string|ISO8601 UTC|







**响应示例**：



```JSON
{
  "data": [
    {
      "id": "01975b3e-...-0000000000a1",
      "teamId": "01975b3e-...",
      "teamName": "Qgents 平台组",
      "role": "TEAM_MEMBER",
      "inviterDisplayName": "林66",
      "status": "PENDING",
      "expiresAt": "2026-08-16T10:00:00Z",
      "createdAt": "2026-08-15T10:00:00Z"
    }
  ],
  "page": { "nextCursor": "", "hasMore": false },
  "requestId": "req_..."
}
```







**不返回明文 token**：数据库仅存邀请令牌的 SHA\-256 哈希，安全底线禁止回明文；接受走 19\.3 的按 id 分支。



错误：`404 NOT_FOUND`（当前用户不存在或无邮箱）、`400 INVALID_CURSOR` / `400 INVALID_PAGE_LIMIT`。







### 19\.3 接受邀请 `POST /team-invitations/{reference}/accept`







路径变量 `reference` 兼容**邀请 id（UUIDv7）或明文 token**，后端按「能否被解析为 UUID」区分：明文 token 为 base64url、无连字符，永远不会被解析成 UUID，两者天然互斥，存量 token 流程完全兼容。







- **幂等**：携带 `Idempotency-Key`，重复调用返回首次结果；已接受且已是成员时幂等返回成员视图。

    

- **安全**：无论按 id 还是 token，均校验当前用户邮箱与被邀请邮箱一致，防越权。

    

    

    

**响应（****`TeamMemberResponse`****）**：



```JSON
{
  "data": {
    "userId": "user-uuid",
    "role": "TEAM_MEMBER",
    "displayName": "王五",
    "email": "wang@example.com"
  },
  "requestId": "req_..."
}
```







错误：`404 NOT_FOUND`（reference 非法/邀请不存在/邮箱不匹配）、`409 INVITATION_EXPIRED`（已过期，后端先置 EXPIRED 再返回）、`409 INVITATION_NOT_PENDING`（已接受或已撤销）。







### 19\.4 团队最近动态 `GET /teams/{teamId}/activities`







**请求参数**：







|参数|类型|必填|说明|
|---|---|---|---|
|`type`|string|否|动态类型过滤，逗号分隔、前缀匹配（如 `TASK,MR`；`MR` 命中 `MR_CREATED`\+`MR_MERGED`）；不传=全部 6 类|
|`cursor`|string|否|分页游标，首次不传|
|`limit`|integer|否|每页条数，默认 20，最大 50|







**动态类型**（本期产出，基于项目事件聚合）：







|type|含义|title|target|actor|
|---|---|---|---|---|
|`TASK_COMPLETED`|任务完成|`任务「{title}」已完成`|`{TASK, taskId, 任务标题}`|任务创建人|
|`TASK_FAILED`|任务失败|`任务「{title}」已失败`|`{TASK, taskId, 任务标题}`|任务创建人|
|`DIFF_CREATED`|Diff 待验收|`任务「{title}」的 Diff 待验收`|`{DIFF, diffId, 任务标题}`|任务创建人|
|`MR_CREATED`|MR 创建|`{MR标题} MR #{n} 已创建`|`{MR, mergeRequestId, "#{n} {MR标题}"}`|null|
|`MR_MERGED`|MR 已合并|`{MR标题} MR #{n} 已合并`|`{MR, mergeRequestId, "#{n} {MR标题}"}`|null|
|`TEST_RUN_FAILED`|测试运行失败|`测试运行失败`|有 taskId→`{TASK, taskId, 任务标题}`；无→`{PROJECT, projectId, 项目名}`|null|







**响应（****`ActivityResponse`****）**：







|字段|类型|说明|
|---|---|---|
|`id`|string|动态条目 id（=事件 id）|
|`type`|string|上表 6 类之一|
|`title`|string|已生成的展示文案（前端当文本展示，勿当机器字段解析）|
|`summary`|string?|本期恒为 null|
|`actor`|object?|`{id, displayName, avatar}`；无可靠来源时为 null，`avatar` 恒为 null|
|`target`|object|`{type, id, title}`；type ∈ `TASK/DIFF/MR/PROJECT`|
|`link`|string?|恒为 null（前端按 `target.type+id` 自行拼前端路由）|
|`createdAt`|string|ISO8601 UTC，按此倒序|







**响应示例**：



```JSON
{
  "data": [
    {
      "id": "01975b3e-...",
      "type": "TASK_COMPLETED",
      "title": "任务「实现登录功能」已完成",
      "summary": null,
      "actor": { "id": "user-uuid", "displayName": "林66", "avatar": null },
      "target": { "type": "TASK", "id": "task-uuid", "title": "实现登录功能" },
      "link": null,
      "createdAt": "2026-08-15T10:00:00Z"
    }
  ],
  "page": { "nextCursor": "...", "hasMore": false },
  "requestId": "req_..."
}
```







**边界**：



- 只覆盖最近 24 小时（events 保留期），超窗数据会消失。

    

- `MESSAGE` / `GROUP_CREATED` / `MEMBER_JOINED` / `TASK_CREATED` 无事件发布源，本期不产出，`type` 传它们返回空 data。

    

- 错误：`404 NOT_FOUND`（团队不存在）、`403 FORBIDDEN`（非团队成员）、`400 INVALID_CURSOR` / `400 INVALID_PAGE_LIMIT`。

    

    

    

## 20\. v1\.8\.0 更新：DeliveryCenter 聚合接口与 Agent 展示摘要







> 基线 v1\.7\.0。本小节落地前端成员 B 的 DeliveryCenter 聚合、Agent 展示摘要需求，并冻结 6 项契约歧义（N01–N06）与权限映射。所有新接口均为只读展示，写操作继续复用对应正式资源接口。
> 
> 
> 
> 
> 
> 







### 20\.1 DeliveryCenter 聚合列表 `GET /api/v1/projects/{projectId}/delivery-items`







**查询参数**：







|参数|类型|必填|说明|
|---|---|---|---|
|`groupId`|UUID|否|CODE 按 Task 的需求群来源匹配；\<br\>MEMORY 按来源消息所属需求群匹配；\<br\>SKILL 当前无来源数据，不匹配 groupId。|
|`type`|string|否|资源类型：`CODE` / `MEMORY` / `SKILL`；省略返回全部三类|
|`status`|string|否|按展示状态 `displayStatus` 筛选（枚举见 §20\.3）|
|`repositoryId`|UUID|否|按项目仓库绑定 ID 筛选（仅 CODE 匹配）|
|`createdBy`|UUID|否|按创建者筛选|
|`keyword`|string|否|按不区分大小写包含匹配 title/summary/resourceId/来源任务展示码与标题/创建人/提交人/CODE 仓库名/MEMORY·SKILL 摘要字段（v2\.0\.6 补充）|
|`cursor`|string|否|游标分页（上一页 `nextCursor`）|
|`limit`|int|否|每页条数，默认 30，最大 100|







**响应**：统一 cursor envelope `{ data, page: { nextCursor, hasMore }, requestId }`。







**DeliveryItem union**：以 `resourceType` 为 discriminator，`CODE` / `MEMORY` / `SKILL` 三类各自携带专属字段，不把三类字段堆叠为公共可选字段。公共字段：







|字段|类型|说明|
|---|---|---|
|`id` / `resourceId`|string|资源 ID：CODE=批次 ID、MEMORY=memoryId、SKILL=skillId|
|`projectId`|string|项目 ID|
|`resourceType`|string|`CODE` / `MEMORY` / `SKILL`|
|`title`|string|展示标题|
|`summary`|string?|脱敏摘要（≤200 字符）|
|`version`|string?|当前恒为 null|
|`displayStatus`|string|后端统一派生的展示状态（前端不维护映射）|
|`resourceStatus`|string|真实资源状态（reviewStatus / Memory\.status / Skill\.status）|
|`requirementGroup`|object?|`{id, name}`；无群来源时 null|
|`source`|object|`{taskId, taskDisplayCode, taskTitle, taskRunId, taskStepId, messageId, artifactId}`；无对应关联时字段为 null|
|`creator` / `submitter` / `reviewer`|object?|`UserSummary{id, displayName, avatarUrl}`；|
|`reviewReason`|string?|驳回/拒绝原因|
|`createdAt` / `submittedAt` / `reviewedAt` / `updatedAt`|string?|ISO8601 UTC；|
|`capabilities`|object|服务端派生的操作能力（§20\.3）|
|`openTarget`|object|显式打开目标（§20\.3）|







**CODE 专属字段**：`repositories[]`（`{repositoryId, name, branch}`）、`diffReviewId`、**`diffId`****（v2\.0\.6 补充：批次内按 ****`projectRepositoryId`**** 升序第一条的代表性 Diff ID，与 Task 详情 ****`diffReviewSummary`**** 同源；交付中心「查看 Diff」据此直接跳转 Diff 查看页 ****`GET /diffs/{diffId}`****，无需经任务中心中转）**、`reviewStatus`、`deliveryStatus`、`filesChanged`、`additions`、`deletions`、`repositoryDeliveries[]`（`{repositoryId, repositoryName, deliveryStatus, failureCode, failureReason, mergeRequest, updatedAt}`）、`mergeRequest`（`{id, number, title, status, webUrl}`，无 MR 时 null）。







**MEMORY 专属字段**：`category`、`tags[]`、`visibility`（当前统一返回 `PROJECT_SHARED`）、`sources[]`、`contentExcerpt`。







**SKILL 专属字段**：`tags[]`、`visibility`（`PRIVATE`/`PROJECT_SHARED`）、`capabilitySummary`（当前恒为 null）、`contentExcerpt`。







**约束**：集合无数据返回 `[]` 不返回 `null`；真实不存在的关联返回 `null`，不得用空字符串或 Mock ID 代替；聚合列表不返回完整 Memory/Skill 内容、Prompt、Token、凭据、环境变量或代码 Patch。







### 20\.2 DeliveryCenter 聚合统计 `GET /api/v1/projects/{projectId}/delivery-summary`







**查询参数**（与 `delivery-items` 一致，用户切换筛选后统计同步变化）：







|参数|类型|必填|说明|
|---|---|---|---|
|`groupId`|UUID|否|按需求群筛选（CODE 按 Task 群来源、MEMORY 按来源消息群匹配；SKILL 无来源不匹配）|
|`type`|string|否|资源类型：`CODE` / `MEMORY` / `SKILL`；省略统计全部三类|
|`status`|string|否|按展示状态 `displayStatus` 筛选|
|`repositoryId`|UUID|否|按项目仓库绑定 ID 筛选（仅 CODE 匹配）|
|`createdBy`|UUID|否|按创建者筛选|







**响应**：







```JSON
{
  "data": {
    "total": 12,
    "countsByType": { "CODE": 4, "MEMORY": 5, "SKILL": 3 },
    "countsByStatus": { "DRAFT": 2, "PENDING_REVIEW": 2, "PROCESSING": 1, "ACCEPTED": 3,
      "REJECTED": 0, "DELIVERED": 3, "FAILED": 1, "ARCHIVED": 0 },
    "pendingForCurrentUser": 2,
    "repositorySummaries": [ { "repositoryId": "...", "repositoryName": "...", "total": 2,
      "accepted": 1, "pending": 1, "failed": 0, "deliveryStatus": "MR_CREATED",
      "mergeRequest": { "id": "...", "number": 128, "title": "...", "status": "OPEN", "webUrl": "..." } } ],
    "requirementGroupSummaries": [ { "requirementGroupId": "...", "name": "...", "total": 3, "pending": 1 } ],
    "updatedAt": "2026-08-15T08:00:00Z"
  },
  "requestId": "req-uuid"
}
```







**规则**：



- 统计针对**完整筛选数据集**计算，不由当前分页推导；

    

- `countsByType` 恒含 `CODE/MEMORY/SKILL` 三 key（值为 0 也返回）；`countsByStatus` 恒含 8 个正式大写枚举 key（`DRAFT/PENDING_REVIEW/PROCESSING/ACCEPTED/REJECTED/DELIVERED/FAILED/ARCHIVED`）；

    

- **统计 key 统一使用正式大写枚举**，不使用 `code`/`memory`/`pendingReview` 等小写或 camelCase key；

    

- `pendingForCurrentUser` 由后端按「当前用户 \+ capabilities」计算；

    

- **不返回 \*\*\*\*****`pendingItems[]`**（N06 结论），前端只消费数量；

    

- `repositorySummaries` / `requirementGroupSummaries` 无数据时返回 `[]`。

    

    

    

### 20\.3 Delivery DTO 语义冻结（N04/N05）



`FINAL_DIFF_EMPTY` 任务没有 `DiffReviewBatch`，因此不生成 `CODE` DeliveryCenter 交付项，也不计入 `FAILED`、`PROCESSING` 或 `DELIVERED`。客户端仅在 Task 视图中以无代码变更完成态展示，不得用空 Diff、空批次或虚假交付项补齐列表。



**openTarget 四态**（`resourceId` 不再被多义解释为 Diff 或批次）：







```TypeScript
type DeliveryOpenTarget =
  | { kind: "TASK_DIFF_REVIEW"; taskId: string; diffReviewBatchId: string }
  | { kind: "DIFF"; taskId: string; diffId: string }
  | { kind: "MEMORY"; memoryId: string }
  | { kind: "SKILL"; skillId: string };
```







**displayStatus 映射（后端派生）**：







|resourceType|真实状态|displayStatus|
|---|---|---|
|CODE|reviewStatus=PENDING\_CONFIRMATION|`PROCESSING`|
|CODE|reviewStatus=ACCEPTED 且 deliveryStatus=DELIVERED|`DELIVERED`|
|CODE|reviewStatus=ACCEPTED 且 deliveryStatus=PARTIALLY\_DELIVERED/FAILED|`FAILED`|
|CODE|reviewStatus=ACCEPTED 且其他交付状态|`PROCESSING`|
|CODE|reviewStatus=REJECTED|`REJECTED`|
|MEMORY|status=DRAFT / PENDING\_REVIEW / APPROVED / REJECTED / ARCHIVED|`DRAFT` / `PENDING_REVIEW` / `ACCEPTED` / `REJECTED` / `ARCHIVED`|
|SKILL|status=DRAFT / PENDING\_REVIEW / PUBLISHED / REJECTED / ARCHIVED|`DRAFT` / `PENDING_REVIEW` / `ACCEPTED` / `REJECTED` / `ARCHIVED`|







**capabilities（后端派生）**：`canSubmitReview` / `canApprove` / `canReject` / `canArchive` / `canRetryDelivery` / `canOpenResource` \+ 对应的 `disabledReasons`（稳定错误码，可操作时为 null）。规则与正式资源接口一致：



- CODE：confirm/reject 仅任务发起人或 Project Admin 且批次为

    

PENDING\_CONFIRMATION \+ confirmationSource=USER；MR\_FIRST 的 ACCEPTED \+ SYSTEM 不显示确认/拒绝操作；retry\-delivery 仅接受后交付未完成（`PARTIALLY_DELIVERED`/`FAILED`）；



- MEMORY/SKILL：submit 仅创建者或 Admin 且 `DRAFT`/`REJECTED`；`confirm/reject` 仅 Admin 且 `PENDING_REVIEW`；archive 仅 Admin 且 `APPROVED`（MEMORY）/`PUBLISHED`（SKILL）。

    

    

    

**写操作复用**：聚合页不新建写接口——CODE 走 `POST /tasks/{taskId}/diff-review/confirm | reject | retry-delivery`（需 `Idempotency-Key`）；MEMORY/SKILL 走各自 `submit-review | approve | reject | archive`。







**Memory/Skill 提交审核信息**（成员B 最终契约 §三）：







- 普通项目成员可创建 Memory/Skill 并提交审核；Project Admin/TEAM\_OWNER 批准或拒绝；通过后成为项目共享资源。

    

- 进入过审核流程的聚合项必须返回 `submitter`（`UserSummary`）与 `submittedAt`（ISO8601 UTC）：

    

    - 从未提交审核的 `DRAFT`：允许为 `null`；

        

    - `PENDING_REVIEW`：`submitter`、`submittedAt` 必须非空；

        

    - `APPROVED`/`PUBLISHED`/`REJECTED`/`ARCHIVED`：保留**最后一次**提交审核的申请人和时间；

        

    - `creator` 与 `submitter` 语义不同，不可相互替代（创建者本人提交时两者相同，但仍同时返回）；

        

    - `reviewer`、`reviewedAt` 在审批完成后按真实审核记录返回。

        

- 数据来源：`memories`/`skills` 表新增 `submitted_by`、`submitted_at` 列（迁移 `V20260815_02__memory_skill_submission_fields.sql`），`submit-review` 时写入。

    

    

    

**Memory/Skill 来源关系**（成员B 最终契约 §四）：







- Memory 由需求群消息产生（`memory_message_sources`）：`source.messageId` 返回真实来源消息 ID；存在消息来源时同时返回 `requirementGroup {id, name}`（由来源消息的群派生）；`sources[]` 返回全部来源消息引用 `{groupId, messageId}`；

    

- Skill 当前无来源数据源：`source` 各字段与 `requirementGroup` 返回 `null`；

    

- `groupId` 筛选匹配**所有**具有该需求群来源的资源：CODE 按 Task 群来源匹配、MEMORY 按来源消息群匹配；SKILL 无来源不匹配；

    

- 没有来源关系时返回 `null`，不得填充 Mock ID 或展示文案。

    

    

    

**CODE openTarget 固定**（成员B 最终契约 §五）：







- CODE 是 Task 级多仓库总 Diff 聚合项，列表项固定返回 `openTarget = { kind: "TASK_DIFF_REVIEW", taskId, diffReviewBatchId }`；

    

- 固定 `resourceId = diffReviewBatchId`、`diffReviewId = diffReviewBatchId`；

    

- `diffId` 只表示单个仓库 Diff，**不得与批次 ID 混用**；当前聚合列表不返回 `openTarget.kind = "DIFF"`；

    

- 单 Diff 入口由 Task 级 DiffReview 详情中的仓库明细提供；`DIFF` openTarget 类型保留给其他页面/未来能力。

    

    

    

### 20\.4 Delivery 相关 SSE 冻结



新增事件（沿用 §12\.1 项目级单连接 / Bearer / `Last-Event-ID` / `EVENT_CURSOR_EXPIRED`；前端收到事件只失效 Query，不写入实体缓存）：



|事件|覆盖场景|
|---|---|
|`memory.submit-review` / `memory.approved` / `memory.rejected` / `memory.archived`|MEMORY 审批流转|
|`skill.submit-review` / `skill.published` / `skill.rejected` / `skill.archived`|SKILL 审批流转|
|统一 payload 基座（CODE 事件保持 §15\.6\.4 现有 payload 不动）：||



```TypeScript
interface DeliveryEventPayload {
  projectId: string;
  resourceType: "CODE" | "MEMORY" | "SKILL";
  resourceId: string;          // memoryId / skillId / diffReviewBatchId
  taskId?: string;
  diffId?: string;
  diffReviewBatchId?: string;
  repositoryId?: string;
  eventVersion: number;        // 首版为 1
  updatedAt: string;           // RFC 3339 UTC
}
```



`merge-request.updated`：完整 payload 见 §6\.10（`projectId, mergeRequestId, repositoryId, number, status, headCommit, providerUpdatedAt, qualityGateStatus, timestamp`，不含 `webUrl`；MR 链接由 MR 列表、详情或 Diff/MR 交付卡提供）；`diff-review.skipped`：reason 固定为 `FINAL_DIFF_EMPTY`（§15\.6\.4）。



### 20\.5 Agent 分配列表与运行时摘要







**`GET /api/v1/projects/{projectId}/agents/{agentId}/assignments`**（B04）







|参数|类型|必填|说明|
|---|---|---|---|
|`type`|string|否|`REQUIREMENT_GROUP` / `WORKFLOW`；省略返回全部|
|`cursor` / `limit`|string/int|否|统一 cursor envelope，默认 30 最大 100|







响应项：`{ type, resourceId, resourceName, status: "ACTIVE"|"INACTIVE" }`。`REQUIREMENT_GROUP` 数据源为 `group_agents`（Agent 参与的需求群，按项目隔离）；`WORKFLOW` 当前无数据源（团队工作流模板非本版本范围，§14），返回空列表不伪造。







**`GET /api/v1/projects/{projectId}/agents/{agentId}/runtime`**（B06）







```JSON
{
  "data": {
    "status": "IDLE",
    "activeRunCount": 0,
    "concurrencyLimit": null,
    "assignmentUsage": {
      "requirementGroups": { "assignedCount": 2, "assignableCount": 5 },
      "workflows": { "assignedCount": 0, "assignableCount": 0 }
    },
    "skillAccessScope": "PROJECT",
    "memoryAccessScope": "PROJECT"
  },
  "requestId": "req-uuid"
}
```







说明：`status` 由 `activeRunCount > 0` 派生；`concurrencyLimit` 当前无配置恒为 null；`skillAccessScope`/`memoryAccessScope` 当前均为项目维度隔离，返回 `PROJECT`。不返回 Prompt、工具凭据或 Memory 完整内容。







### 20\.6 项目级按 Agent 查询 TaskRun（B05）







**`GET /api/v1/projects/{projectId}/task-runs`**







|参数|类型|必填|说明|
|---|---|---|---|
|`agentId`|UUID|是|执行 Agent ID|
|`status`|string|否|TaskRun 状态过滤|
|`cursor` / `limit`|string/int|否|统一 cursor envelope，默认 20 最大 100|







列表项在 §12\.2 `TaskRunListItemResponse` 基础上补充：`taskDisplayCode`、`taskTitle`、`taskStepRole`、`requirementGroup`（`{id, name, status}`）、`repository`（`RepositorySummary`，取任务首个 worktree 对应仓库，无则 null）。







### 20\.7 Task `attention` 扩展（B07）







`attention` 增加正式关联字段（无对应关联返回 null，前端不按标题/数组位置/Mock ID 推断跳转）：







```TypeScript
interface TaskAttention {
  kind: string;
  title: string;
  summary: string | null;
  taskRunId: string | null;
  inputRequestId: string | null;
  diffReviewBatchId: string | null;   // DIFF_CONFIRMATION_REQUIRED/DELIVERY_FAILED 时非 null
  repositoryId: string | null;        // DELIVERY_FAILED 时为首个失败仓库绑定 ID
  createdAt: string | null;
}
```







### 20\.8 N01–N06 契约歧义冻结结论







|编号|待确认项|结论|
|---|---|---|
|N01|TaskStep 列表响应|统一为 cursor envelope `{data, page:{nextCursor, hasMore}, requestId}`（已实现，单页返回全部）|
|N02|TaskRun `artifactSummary`|正式 shape 为 `{total, diffCount}`；旧 `{diffs:{count,byStatus}}` 废弃，不保留兼容|
|N03|DiffReview nullable|批次不存在时查询返回 `404 DIFF_REVIEW_NOT_FOUND`；批次内 `diffId` 恒非空；`requirementGroupId` 由 Task 派生（防御性允许 null）；`reviewReason` 仅 REJECTED 时非 null|
|N04|Delivery 资源 ID|采纳显式 `openTarget`（§20\.3）；`resourceId` = 批次 ID / memoryId / skillId，与 `diffId` 严格区分|
|N05|`displayStatus` 归属|后端统一派生并返回（映射见 §20\.3），前端不维护映射|
|N06|待处理统计|只返回 `pendingForCurrentUser` 数量，不返回 `pendingItems[]`|





### 20\.9 权限与状态规则确认







|产品术语|后端结论|
|---|---|
|"Owner 批准 Memory/Skill"|= `PROJECT_ADMIN`（`requireProjectAdmin`）|
|`TEAM_OWNER` 是否自动继承项目审批权|是：`ProjectAccessService` 对 canonical Team Owner 做项目内 Admin 兜底（`isOwnerOrAdmin` / `requireProjectAdmin` 已覆盖）|
|普通项目成员|只能创建/提交审核，不能 approve/reject/archive；可见性遵循 PRIVATE / 已发布可见规则|
|Memory 审批后共享状态|`APPROVED`（项目共享，无独立 visibility 字段，聚合项统一返回 `PROJECT_SHARED`）|
|Skill 审批后正式状态|`PUBLISHED`（前端按 `PUBLISHED` 消费；旧 `APPROVED` 不作为 Skill 状态）|
|前端如何判断操作权|不解析成员角色，统一消费后端返回的 `capabilities`|







### 20\.10 P2：DeliveryCenter 导出 `GET /api/v1/projects/{projectId}/delivery-items/export`（已实现）















`GET /delivery-items/export`（CSV \+ `Content-Disposition`）已实现。导出范围仍限列表摘要，不含完整 Memory/Skill 内容、Prompt、Token、凭据或代码 Patch。















**查询参数**（与 `delivery-items` 一致）：















|参数|类型|必填|说明|
|---|---|---|---|
|`groupId`|UUID|否|按需求群筛选（CODE 按 Task 群来源、MEMORY 按来源消息群匹配；SKILL 无来源不匹配）|
|`type`|string|否|资源类型：`CODE` / `MEMORY` / `SKILL`；省略导出全部三类|
|`status`|string|否|按展示状态 `displayStatus` 筛选（枚举见 §20\.3）|
|`repositoryId`|UUID|否|按项目仓库绑定 ID 筛选（仅 CODE 匹配）|
|`createdBy`|UUID|否|按创建者筛选|















**响应**：`200 text/csv; charset=UTF-8`，`Content-Disposition: attachment; filename="delivery-items-<yyyyMMdd-HHmmss>.csv"`；正文为 UTF\-8 CSV（含 BOM，便于 Excel 识别中文），首行为表头，之后每行一个交付项。空数据集只返回表头行。















**CSV 列**（脱敏摘要，与列表 DTO 一致）：















|列|来源|说明|
|---|---|---|
|类型|`resourceType`|`CODE` / `MEMORY` / `SKILL`|
|标题|`title`|展示标题|
|摘要|`summary`|≤200 字符脱敏摘要|
|展示状态|`displayStatus`|后端派生（§20\.3）|
|资源状态|`resourceStatus`|真实资源状态|
|需求群|`requirementGroup.name`|无群来源时为空|
|来源任务编号|`source.taskDisplayCode`|仅 CODE|
|来源任务标题|`source.taskTitle`|仅 CODE|
|创建人|`creator.displayName`||
|审核人|`reviewer.displayName`||
|驳回原因|`reviewReason`|未拒绝时为空|
|创建时间 / 审核时间 / 更新时间|`createdAt` / `reviewedAt` / `updatedAt`|ISO8601 UTC|
|变更文件数 / 新增行数 / 删除行数|`filesChanged` / `additions` / `deletions`|仅 CODE，其他类型为空|
|仓库|`repositories[].name`|仅 CODE，分号连接，去重|















**CSV 转义**：遵循 RFC 4180（字段含逗号/双引号/换行时用双引号包裹，内部引号翻倍转义）。权限：项目成员可导出。











## 21\. v1\.9\.0 补充：Diff / MR / Testset 页面缺口确认







> 依据前端 `docs/temp/diff-mr-testset-backend-gaps.md`，后端基于现状逐项确认。以下「已符合」项为冻结契约；「需补」项待开发排期后落地（不阻塞首轮联调）；「本轮不做」项前端按空态处理。
> 
> 
> 
> 
> 
> 







### 21\.1 五个问题的直接结论







|编号|问题|结论|
|---|---|---|
|Q1|`GET /merge-requests/{id}/checks` 能否按 `{status, requiredChecks, items[]}` 冻结？|语义一致、**形状不同**：现状返回扁平数组 `MergeRequestCheckResponse[]`（`id/type/status/attemptNo/testsetId/commitSha/source/startedAt/completedAt`）。`type` 枚举冻结为 `TESTSET/AI_REVIEW/DRY_RUN/CQ_PLUS_ONE`，`status` 冻结为 `PENDING/PASSED/FAILED`；`qualityGate.requiredChecks` 只含四项。**待定**：后端包装为 `{status, requiredChecks, items[]}`（推荐），或前端适配扁平数组|
|Q2|MR 能否直接返回 `diffId`？|现状**无**。数据可推导（MR 的 taskId \+ projectRepositoryId \+ sourceBranch ↔ Diff 同字段 \+ reviewBatchId→taskId）。**需补（推荐）**：MR 详情返回该仓库该任务已 `ACCEPTED` 的 Diff 的 `diffId`，无则 `null`；备选：`GET /diffs` 增加 `repositoryId` \+ `sourceBranch` 过滤|
|Q3|Testset 列表是扁平字段还是 `definition`？|**扁平字段（§10 形状）已实现**：`TestsetResponse` 顶层含 `command/timeoutSeconds/passRule/acceptanceNotes`；`status` 只用 `ENABLED/DISABLED`（无 `enabled` 布尔）；enable/disable/delete 已实现。**缺 \*\*\*\*****`scopeTags`**（创建请求有、响应未返回，需补）|
|Q4|`cases[]`、`reportUrl`、`pdfUrl` 本轮给不给？|**本轮不给**：`TestRunResponse` 详情为 `id/projectId/repositoryId/ref/testsetIds/status/summary/createdBy/createdAt`（无 caseSummary/cases/reportUrl/pdfUrl/sandboxId/startedAt/finishedAt）；`DryRunReportResponse` 为 `id/status/createdAt`。执行器未收集逐用例结果、报告产物未接存储。测试结果由 `summary.results`（TestRun）或 `report.tests.results`（Dry Run）提供（结构见 §12\.4 冻结语义），`caseSummary` 缺失不代表没有测试结果。前端用例详情/报告 Tab 保持空态|
|Q5|Test Run / Dry Run 历史列表本轮做不做？|**已实现（第 38 节）：提供 ****`GET /test-runs`**** 与 ****`GET /dry-runs`****，支持项目成员按项目仓库、Task、状态、发起人（Dry Run 另支持目标分支）筛选，并使用 ****`cursor`****/****`limit`**** 游标分页；本机 localStorage 只能作为缓存或离线入口，不能替代服务端列表**|







### 21\.2 Diff / MR / Testset 字段现状（冻结）







**Diff 评审页**：







|字段/能力|现状|结论|
|---|---|---|
|`GET /diffs/{diffId}/files`|返回 `DiffFileResponse[]`：`id/sequence/path/changeType/additions/deletions/binary`，**无 hunk/line 结构**|需补：解析 patch 为 `hunks[].lines[]`（Git 领域），或冻结「前端用 patch 接口自行解析」——待定|
|文件变更类型|字段名 `changeType`（前端称 `status`）|枚举冻结为 `ADDED/MODIFIED/DELETED`；字段名待统一|
|`GET/POST /diffs/{diffId}/comments`|`DiffCommentResponse`：`id/diffId/path/side/line/hunkId/commitSha/body/authorUserId/createdAt`|`createdAt` 已符合；**`authorName`****\*\*\*\* 需补**（join users）或前端用 `authorUserId` 查用户|
|Diff 详情 `targetBranch` / 仓库名|无（仅 `sourceBranch`；无 repositoryName）|建议：`targetBranch` 明确使用仓库默认分支；仓库名用 `binding.displayName` 或前端自行拼|
|评论已解决 / 回复串 / 文件下载|无|本轮不做（书面确认）|







**MR 列表 / 详情**：







|字段|现状|结论|
|---|---|---|
|`title`|列表、详情均有 ✓|已符合|
|`qualityGate`|列表、详情均有（`{status, requiredChecks}`）✓|已符合|
|`status`|`OPEN/MERGED/CLOSED` ✓|已符合（「进行中」即 OPEN 文案）|
|`webUrl`|无|需补：GitHub repo \+ `providerNumber` 构造，构造不出给 `null`|
|`description`|无（未镜像 GitHub body）|返回 `null`/`""`|
|`groupIds`|列表无|需补（按群标识时）|
|`diffId`|无|需补（Q2 推荐方案）|







**Testset / TestRun / DryRun / SSE**：







|项|现状|结论|
|---|---|---|
|`GET /testsets` 过滤|`repositoryId` ✓；`status` 前端已传|`status` 过滤若未支持则需补（实现时确认）|
|`POST /test-runs` / `POST /dry-runs` 立即响应|含 `id` ✓|前端可接着 GET 详情/report|
|SSE `test-run.updated`|payload 含 `testRunId` \+ projectId/repositoryId/taskId/ref/status ✓|已符合|
|SSE `dry-run.updated`|payload 含 `dryRunId` \+ projectId/repositoryId/taskId/headCommit/targetBranch/status ✓|已符合|
|TestRun / DryRun 历史列表|`GET /test-runs`、`GET /dry-runs`（§38）|已实现；用例详情和报告产物 URL 仍不在本轮范围|







### 21\.3 待补工作清单（后续实现，非当前冻结契约）







1. Diff files：补 hunk/line 结构化，或冻结「前端自行解析 raw patch」；

    

2. Diff / MR comments：响应补 `authorName`（join users）；

    

3. MR：详情补 `diffId`（推荐）、列表/详情补 `webUrl`、详情补 `description`（允许 null）、列表补 `groupIds`；

    

4. MR checks：包装为 `{status, requiredChecks, items[]}`（或前端适配扁平数组）；

    

5. Testset：响应补 `scopeTags`；

    

6. （P1\+）逐用例结果详情、报告产物 URL（前端按空态处理，不阻塞联调）

    

    

    

### 22\.4 Agent 单卡详情（前端联调，已实现）







`GET /teams/{teamId}/agents/{agentId}`（原仅有列表）新增单 Agent 详情，支持**可选 ****`projectId`**** 参数**：







```HTTP
GET /api/v1/teams/{teamId}/agents/{agentId}?projectId={projectId}
```







- `projectId` 传了：校验该 Agent 属于此项目的 Team（`agents.team_id == projects.team_id`）且调用者为项目成员；不传：仅团队可见性校验

    

- 响应为 `AgentResponse`（`id/name/avatar/role/description/prompt/visibility/status/createdBy`），PRIVATE Agent 仅创建者可见（`prompt` 仅创建者返回）

    

- 错误：`404 TEAM_RESOURCE_NOT_FOUND` / `404 PROJECT_NOT_FOUND` / `404 AGENT_NOT_FOUND`

    

### 22\.5 创建项目时绑定仓库 / 自动新建仓库（前端额外清单 §四，已实现）



`POST /teams/{teamId}/projects` 请求体新增可选字段：



|字段|类型|说明|
|---|---|---|
|`repositoryIds`|string\[\] $UUID$|GitHub 授权仓库 id 列表（**`github_repositories.id`**，授权仓本地 UUID，非绑定记录 id）|
|`newRepository`|object|自动新建一个 GitHub 仓库并绑定；与 `repositoryIds` 互斥（二选一）|



自动新建仓库：后端建仓时固定传 auto\_init: true，GitHub 会自动生成初始 README 提交并据此建立默认分支（一般是 main）。建仓返回的元数据中带有真实 defaultBranch，并直接写入本地仓库镜像，因此新建仓库立即可绑定、可克隆，不会出现空仓库缺默认分支的问题。前端无需额外处理。



绑定已有仓库时后端逐个校验并绑定（等价于创建后逐个调 `POST /projects/{projectId}/repositories`）：



- 校验：仓库属于该团队 `ACTIVE` 安装、`authorizationStatus=AUTHORIZED`、未归档、`defaultBranch` 非空；

    

- 重复绑定同一仓库返回 `409 PROJECT_REPOSITORY_ALREADY_BOUND`（整体失败回滚，项目不创建）；

    

- 不传或传空：按原行为创建项目，之后单独绑定。

    

`newRepository` 自动建仓（题目 §2\.1「项目创建时自动新建一个 git 仓库并绑定」）：



- 建仓在事务外执行（不持有数据库事务或行锁，AGENTS §3\.4），用团队 `ACTIVE` 安装账号发起；`auto_init=true` 使仓库创建即带默认分支（`main`），避免空仓库缺少 `defaultBranch`；

    

- 建仓成功后事务内落 `github_repositories` 镜像 \+ `project_repositories` 绑定，与项目落库同事务原子提交；

    

- 团队有多个 `ACTIVE` 安装时须指定 `newRepository.installationId`，否则返回 `422 GITHUB_INSTALLATION_REQUIRED`；

    

- 仓库名冲突返回 `409 GITHUB_REPOSITORY_CREATE_CONFLICT`。

    

`newRepository` 字段：



|字段|类型|说明|
|---|---|---|
|`name`|string|仓库名，必填，符合 GitHub 命名约束|
|`description`|string|仓库描述，可选|
|`isPrivate`|boolean|是否私有，默认 `true`|
|`installationId`|string $UUID$|建仓使用的安装记录 ID，团队仅一个 ACTIVE 安装时可省略|
|`displayName`|string|项目内显示名称，默认取仓库名|







```JSON
{
  "name": "Qgents Web",
  "description": "Web client",
  "memberIds": ["user-uuid"],
  "newRepository": {
    "name": "qgents-web",
    "description": "Web client repository",
    "isPrivate": true,
    "displayName": "Web 前端"
  }
}
```



# v2\.0\.3 增量：MR 创建前预检门禁



### 1\. 规则







创建 MR 前，服务端必须基于当前 Task Workspace 的 `sourceCommit` 和当前目标分支的 `targetCommit` 校验：







1. 存在同一 Task、仓库、源提交、目标分支、目标提交的真实 `PASSED` Dry Run；

    

2. 存在同一 Dry Run 的独立人工 CQ\+1，且最新决定为 `APPROVED`；

    

3. 审查者不得是 Task 发起人；

    

4. 任一提交变化后，旧 Dry Run/CQ 自动不再匹配，不可复用。

    

    

    

GitHub `mergeable`、前端本地状态、历史提交上的通过记录均不是预检事实。







### 2\. 查询当前预检







`GET /api/v1/projects/{projectId}/tasks/{taskId}/repositories/{repositoryId}/preflight?targetBranch=main`







权限：Project Member。







响应：







```JSON
{
  "data": {
    "taskId": "task-uuid",
    "repositoryId": "project-repository-uuid",
    "sourceCommit": "source-sha",
    "targetBranch": "main",
    "targetCommit": "target-sha",
    "status": "PENDING",
    "blockers": ["CQ_PLUS_ONE_MISSING"],
    "dryRun": {
      "id": "dry-run-uuid",
      "status": "PASSED",
      "sourceCommit": "source-sha",
      "targetCommit": "target-sha",
      "completedAt": "2026-08-17T14:00:00Z"
    },
    "cqPlusOne": {
      "status": "PENDING",
      "reviewerUserId": null,
      "reason": null,
      "reviewedAt": null
    }
  }
}
```







`status` 枚举：`PENDING | PASSED | FAILED`。







常用 `blockers`：`DRY_RUN_MISSING`、`DRY_RUN_QUEUED`、`DRY_RUN_RUNNING`、`DRY_RUN_FAILED`、`CQ_PLUS_ONE_MISSING`、`CQ_PLUS_ONE_REJECTED`。







### 3\. 预检 CQ\+1







写请求均须携带 `Idempotency-Key`。







|方法|路径|说明|
|---|---|---|
|`POST`|`/api/v1/projects/{projectId}/dry-runs/{dryRunId}/cq-approvals`|对通过且未失效的 Dry Run 给出 CQ\+1|
|`POST`|`/api/v1/projects/{projectId}/dry-runs/{dryRunId}/cq-rejections`|拒绝 CQ，`reason` 必填|







请求体：







```JSON
{"reason": "已核对认证逻辑与测试结果"}
```







服务端校验 Dry Run 为 `PASSED`、其源提交仍为当前 Task Workspace HEAD、其目标基准仍为当前目标分支；否则返回 `409 PREFLIGHT_CONTEXT_STALE`。Task 发起人为自己审批返回 `403 PREFLIGHT_CQ_AUTHOR_FORBIDDEN`。







### 4\. 创建 MR 的拦截







`POST /api/v1/projects/{projectId}/merge-requests` 保持原请求体，但新增创建前预检校验。







未通过时返回：







```JSON
{
  "error": {
    "code": "MR_PREFLIGHT_NOT_PASSED",
    "message": "MR 创建前预检未通过",
    "details": [{"code": "DRY_RUN_MISSING"}, {"code": "CQ_PLUS_ONE_MISSING"}]
  },
  "requestId": "request-id"
}
```







HTTP 状态为 `409`。前端可以依据 `details[].code` 渲染操作引导，但不得将按钮禁用作为唯一防线。







### 5\. SSE







新增 `preflight.updated`：







```JSON
{
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "repositoryId": "project-repository-uuid",
  "dryRunId": "dry-run-uuid",
  "targetBranch": "main",
  "decision": "APPROVED"
}
```







收到该事件后，前端重新请求第 2 节的预检接口。Dry Run 结果变化仍通过既有 `dry-run.updated` 通知，前端同样应刷新预检状态。



## 新增任务状态卡与 Diff 卡更新契约



### 23\.1 设计目标



为避免同一个 Task 在需求群中不断产生重复的任务状态消息，本版本对自动化卡片消息采用“单消息持续更新”机制。



每个 Task 在需求群中最多维护两条自动化消息：





- 一条 TASK\_STATUS 任务状态卡；

    

- 一条 DIFF Diff 卡。

    

本机制不新增数据库表、不新增消息类型、不新增卡片查询接口，继续复用现有群消息查询接口：



GET /api/v1/projects/\{projectId\}/groups/\{groupId\}/messages



TASK\_STATUS 和 DIFF 是消息类型；task\-card\-\{taskId\} 和 diff\-card\-\{taskId\} 是服务端内部用于定位消息的 clientMessageId，不是消息类型。



### 23\.2 卡片唯一定位





服务端使用 messages\.client\_message\_id 定位自动化卡片：



```Plain Text
TASK_STATUS：
client_message_id = task-card-{taskId}

DIFF：
client_message_id = diff-card-{taskId}
```



定位范围为同一个需求群内的：



```Plain Text
requirement_group_id + client_message_id
```



处理规则：



1. 首次发送时，如果不存在对应 clientMessageId，则创建一条新消息。

    

2. 后续状态变化时，查找并更新原消息的 content。

    

3. 更新时保持原消息的 message\.id、sequence 和 createdAt 不变。

    

4. 更新自动化卡片不会新增群消息，不增加未读数，不改变普通消息的顺序。

    

5. 普通用户消息和普通 Agent 消息仍使用原有 clientMessageId 幂等逻辑，不进入卡片更新逻辑。

    

6. 同一个 Task 不得因为状态变化重复创建 TASK\_STATUS 或 DIFF 消息。

    

### 23\.3 TASK\_STATUS 任务状态卡



TASK\_STATUS 用于展示 Task 的完整执行状态、Planner 计划和 TaskStep 快照。



示例：



```Plain Text
{
  "taskId": "task-uuid",
  "status": "RUNNING",
  "phase": "CODING",
  "deliveryMode": "DIFF_FIRST",
  "deliveryReason": "按规则判定 DIFF_FIRST",
  "node": "DEVELOPER",
  "message": "正在执行代码修改",
  "currentStepId": "step-uuid",
  "plan": {
    "summary": "修复需求群权限和上下文访问控制",
    "steps": [
      {
        "stepId": "step-001",
        "sequence": 1,
        "title": "分析现有权限链路",
        "role": "PLANNER",
        "status": "SUCCEEDED",
        "message": "计划已生成"
      },
      {
        "stepId": "step-002",
        "sequence": 2,
        "title": "修改群列表和上下文过滤",
        "role": "DEVELOPER",
        "status": "RUNNING",
        "message": "正在修改代码"
      },
      {
        "stepId": "step-003",
        "sequence": 3,
        "title": "执行测试和代码审查",
        "role": "TESTER",
        "status": "PENDING",
        "message": null
      }
    ]
  }
}
```



字段约束：



- taskId：Task 的 UUID。

    

- status：使用 Task 当前状态，例如 PLANNING、PENDING、RUNNING、WAITING\_DIFF\_CONFIRMATION、DELIVERING、SUCCEEDED、DELIVERY\_FAILED、FAILED、CANCELLED。

    

- phase：PLAN、CODING、TESTING、REVIEWING 或 DELIVERY。

    

- currentStepId：当前 RUNNING 的 TaskStepEntity\.id；没有运行中步骤时为 null。

    

- plan\.summary：Planner 产出的任务理解或计划摘要。

    

- plan\.steps：当前 TaskStep 快照，按照 sequence 升序排列。

    

- steps\[\]\.stepId：必须使用数据库中的 TaskStepEntity\.id，不能使用数组下标、role 或标题代替。

    

- steps\[\]\.sequence：使用 TaskStepEntity\.sequenceNo。

    

- steps\[\]\.role：Planner、Developer、Tester、Reviewer 等执行角色。

    

- steps\[\]\.status：PENDING、RUNNING、SUCCEEDED、FAILED、SKIPPED、CANCELLED。

    

- steps\[\]\.message：当前步骤的脱敏展示文案，可为空。

    

TASK\_STATUS 生命周期：



1. Planner 开始时创建或更新同一张任务卡，状态为 PLANNING，phase 为 PLAN，Steps 可以为空。

    

2. Plan 物化后，更新同一张任务卡，写入 plan\.summary 和全部 TaskStep。

    

3. Step 开始执行时，更新对应 stepId 的 status 为 RUNNING，并更新 currentStepId。

    

4. Step 完成、失败、跳过或取消时，只更新对应 Step，不重复追加相同 Step。

    

5. Task 进入等待确认、交付中、成功、失败或取消状态时，继续更新同一张 TASK\_STATUS 消息。

    

6. TaskStep、TaskRun 和 Task 的数据库记录仍然是执行事实的权威来源，消息卡片只是用户展示快照。

    

### 23\.4 DIFF 卡



DIFF 消息用于展示 Task 级 Diff 审核和交付状态，不将 Diff 内容放入 TASK\_STATUS 卡。



示例：



```Plain Text
{
  "taskId": "task-uuid",
  "diffId": "diff-uuid",
  "reviewBatchId": "review-batch-uuid",
  "title": "修复需求群权限和上下文访问控制",
  "additions": 120,
  "deletions": 30,
  "reviewStatus": "PENDING_CONFIRMATION",
  "deliveryStatus": "NOT_STARTED"
}
```



约束：



- message\.type 必须为 DIFF。

    

- content\.diffId 必须存在，且必须为真实 Diff UUID。

    

- content\.taskId 必须存在。

    

- reviewBatchId 为 Task 级 DiffReviewBatch 的 UUID。

    

- reviewStatus 使用 PENDING\_CONFIRMATION、ACCEPTED、REJECTED。

    

- deliveryStatus 使用 NOT\_STARTED、COMMITTED、PUSHED、MR\_CREATED、DELIVERY\_FAILED。

    

- DIFF 卡更新时必须保留 content\.diffId。

    

- 用户引用 Diff 卡时，服务端通过 replyToMessageId 找到原 DIFF 消息，再读取 content\.diffId，定位源 Task、Workspace 和 Diff。

    

- 已被用户引用或处理过的 diffId 不得被新的 Diff 快照直接覆盖。

    

- 如果产生新的 Diff 快照，应创建新的续作 Task，并使用新的 Diff 卡。

    

### 23\.5 卡片更新事件



当已有 TASK\_STATUS 或 DIFF 消息的 content 被更新时，服务端发布：



```Plain Text
message.updated
```



事件 payload：



```Plain Text
{
  "projectId": "project-uuid",
  "groupId": "requirement-group-uuid",
  "messageId": "message-uuid"
}
```



事件语义：



- message\.created：群中创建了一条新消息。

    

- message\.updated：已有群消息的 content 被更新。

    

- message\.updated 不代表创建了新消息。

    

- message\.updated 不改变 message\.id、sequence 或 createdAt。

    

- 事件只用于通知前端刷新，不作为完整消息数据源。

    

客户端收到 message\.updated 后，继续请求现有群消息接口：



```Plain Text
GET /api/v1/projects/{projectId}/groups/{groupId}/messages
```



如果返回消息的 id 已存在于本地消息列表中，客户端直接替换该消息的 content；如果不存在，则按普通消息处理。



前端不得根据消息连续性进行聚合，也不得把普通用户消息或其他 Agent 消息合并进 TASK\_STATUS 或 DIFF 卡。



### 23\.6 发送身份



正常情况下，自动化卡片由任务所属 Team 的 ORCHESTRATOR Agent 发送：



```Plain Text
senderType = AGENT
senderId = ORCHESTRATOR Agent ID
```



如果当前 Team 没有可用的 ORCHESTRATOR Agent，服务端使用 SYSTEM 身份创建或更新卡片：



```Plain Text
senderType = SYSTEM
senderId = null
senderName = null
```



SYSTEM 降级只表示卡片发送身份缺失，不改变 TASK\_STATUS 或 DIFF 的消息类型，也不改变 Task、Diff、Commit、Push 或 MR 的真实状态。



### 23\.7 兼容性说明



本次变更：



- 不新增数据库表或字段；

    

- 不新增 HTTP 查询接口；

    

- 不新增消息类型；

    

- 不改变现有群消息查询接口；

    

- 不改变普通消息发送和回复接口；

    

- 不改变 Diff 引用续作逻辑；

    

- 不改变 Task、TaskStep、TaskRun、DiffReviewBatch 的持久化事实。

    

前端只需要继续消费现有群消息接口，并额外处理 message\.updated 事件即可



## 24\. 项目成员、群成员与有效权限补充







本节补充 §3、§5、§7 中项目权限和需求群成员的实际契约；如与前文的成员集合、人数或退出语义存在冲突，以本节为准。服务端始终依据认证身份和资源归属授权，客户端不得以成员列表或本地 Team 角色替代服务端判断。







### 24\.1 项目详情的有效角色







`GET /projects/{projectId}` 返回 `ApiResponse<ProjectResponse>`，其中 `data.role` 是当前认证用户在该项目的**有效角色**，取值为 `PROJECT_ADMIN` 或 `PROJECT_MEMBER`。







- canonical Team Owner 对本团队项目具有服务端兜底管理权限；即使该用户没有 `project_members` 行，项目详情也返回 `role = PROJECT_ADMIN`。

    

- 客户端展示项目管理员能力时，应读取项目详情的 `data.role`；不得仅以项目成员列表或团队成员列表推断权限。

    

- 所有写操作仍由服务端再次校验；客户端的按钮显隐不构成授权。

    

`ProjectResponse` 另含 `repositoryCount`（v2\.0\.6 补充）：该项目**生效（ACTIVE）仓库绑定数**，用于项目卡/详情展示「绑定 N 个仓库」，避免前端逐卡 N\+1 查询仓库列表；`GET /teams/{teamId}/projects` 列表项与 `GET /projects/{projectId}` 详情均返回。







### 24\.2 显式项目成员列表与分页







`GET /projects/{projectId}/members` 仅返回 `project_members` 中的显式项目成员，不为 canonical Team Owner 虚构成员记录。该接口支持 `cursor`、`limit`，使用统一分页响应：







```JSON
{
  "data": [
    {"userId": "user-uuid", "role": "PROJECT_ADMIN"}
  ],
  "page": {"nextCursor": null, "hasMore": false},
  "requestId": "req_..."
}
```







因此，当前用户可访问项目但未出现在该列表中是合法状态；客户端需要分页读取完整成员集合，并以 §24\.1 的 `data.role` 判断当前用户的有效权限。







### 24\.3 创建需求群的初始成员







`POST /projects/{projectId}/groups` 创建 `REQUIREMENT` 群时：







- `memberIds` 是可选的初始其他显式项目成员列表。

    

- 创建者由服务端自动加入群；省略或传空 `memberIds` 时，群内只有创建者。

    

- 请求重复包含创建者时，服务端忽略该项，不要求创建者存在 `project_members` 行。

    

- 除创建者外，每个 `memberIds` 必须存在于 `project_members`，否则返回 `422 GROUP_MEMBER_NOT_PROJECT_MEMBER`。

    

- Agent 不通过 `memberIds` 加入群；Agent 首次以 `sendAsAgent` 在群内发言后写入 `group_agents`。

    

    

    

### 24\.4 群成员、人数与退出项目







- `PROJECT_MAIN` 成员列表由全部显式项目成员和 `group_agents` 构成；`REQUIREMENT` 成员列表由 `group_members` 和 `group_agents` 构成。

    

- `memberCount` 与上述集合一致：主群统计全部显式项目成员加 Agent，需求群统计 `group_members` 加 Agent。

    

- `POST /projects/{projectId}/groups/{groupId}/leave` 会移除显式项目成员，使其失去项目全部资源访问权。最后一名 Project Admin 返回 `409 PROJECT_ADMIN_CANNOT_LEAVE`。

    

- canonical Team Owner 因保留跨项目兜底权限，不能以该接口制造“已退出”假象；调用时返回 `409 TEAM_OWNER_CANNOT_LEAVE_PROJECT`，不删除任何成员关系。

    

    

    

### 24\.4\.1 需求群成员管理接口（v2\.0\.6 补充）



|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/projects/{projectId}/groups/{groupId}/members`|群创建者或 Project Admin|邀请项目成员入群（body `{ "userId": "user-uuid" }`，201）；仅 `REQUIREMENT` 群，主群返回 `422`|
|`DELETE`|`/projects/{projectId}/groups/{groupId}/members/{memberUserId}`|群创建者或 Project Admin|移出群聊（204）；创建者本人不可移出；仅 `REQUIREMENT` 群，主群返回 `422`|



- 被邀请用户必须是该项目成员（`project_members`），否则 `422 GROUP_MEMBER_NOT_PROJECT_MEMBER`。

- 移出后该用户失去该群的消息/任务/SSE 可见性（消息、任务列表、SSE 推送均按群成员收紧）；不影响其在项目内其他群的成员身份。

- 群创建者不可被移出（返回 `409` 或 `422`）。

- 两个接口均需 `Idempotency-Key`（写操作统一约定）。

    

    

    

### 24\.5 兼容性







本补充不新增接口、数据库表或响应顶层字段；仅明确现有 `ProjectResponse.role`、成员分页和群成员操作的语义及错误码。











## 25\. Branch 工作台 TaskRun 接口补充















本节冻结 Branch 工作台依赖的现有接口字段。所有字段由服务端从真实 Task、TaskStep、TaskRun、Workspace 和执行记录派生；没有对应持久化事实时返回 `null` 或空数组，不使用 Mock 值填充。















### 25\.1 Task 详情验收标准















`GET /projects/{projectId}/tasks/{taskId}` 的 `data.acceptanceCriteria` 始终存在，类型为数组。无验收项时返回 `[]`，不得省略字段或返回 `null`。















数组元素结构如下：















```JSON
{
  "id": "criterion-uuid",
  "title": "验收项标题",
  "description": null,
  "status": "PENDING"
}
```















`status` 取值为 `PENDING`、`SATISFIED`、`UNSATISFIED` 或 `NOT_APPLICABLE`。历史数据缺少状态时，服务端按 `PENDING` 返回；服务端不得伪造验收通过结果。















### 25\.2 TaskRun 执行上下文















`GET /projects/{projectId}/task-runs/{taskRunId}/execution-context` 返回 `ApiResponse<ExecutionContextResponse>`，`data` 至少包含以下稳定字段：















```JSON
{
  "workspaceId": "workspace-uuid",
  "sandboxStatus": null,
  "repositoryId": "project-repository-uuid",
  "baseRef": "main",
  "headRef": "feat/task-uuid",
  "startedAt": "2026-08-18T08:00:00Z",
  "expiresAt": null
}
```















- `repositoryId` 是 `project_repositories.id`，不是 GitHub provider repository ID。

    

- 没有关联代码仓库的运行仍返回 HTTP 200，`repositoryId`、`baseRef`、`headRef` 为 `null`。

    

- 当前没有可持久化的 Sandbox 实体时，`sandboxStatus` 和 `expiresAt` 为 `null`；不得用 Workspace 状态冒充 Sandbox 状态。

    

- 不得返回宿主机路径、容器控制入口、Git 凭证或其他敏感信息。

    

    

    

    

    

    

    

### 25\.3 TaskRun 详情摘要















`GET /projects/{projectId}/task-runs/{taskRunId}` 的 `data` 在原有字段基础上稳定提供：















- `taskStepTitle`：所属 TaskStep 标题，不存在时为 `null`；

    

- `agent`：实际执行 Agent 摘要，未分配时为 `null`；

    

- `statusSummary`：脱敏用户可见状态文案，不包含日志原文、Prompt、Token、环境变量或命令输出；

    

- `artifactSummary`：`{ "total": number, "diffCount": number }`；

    

- `steps`：执行服务持久化的真实内部节点轨迹；当前未持久化时返回 `[]`，不得根据角色或日志临时伪造。

    

    

    

    

    

    

    

TaskRun 的详情、日志、执行上下文和输入请求均继承所属 Task 的需求群可见性。用户必须是项目成员，并且是该 Task 所属 `PROJECT_MAIN` 或 `REQUIREMENT` 群的可见成员；否则返回权限错误或资源不可见错误。















### 25\.4 InputRequest 分页响应















`GET /projects/{projectId}/task-runs/{taskRunId}/input-requests` 直接返回统一 cursor envelope，不再套用额外的 `ApiResponse`：















```JSON
{
  "data": [
    {
      "id": "input-request-uuid",
      "taskRunId": "task-run-uuid",
      "kind": "INPUT",
      "status": "PENDING",
      "prompt": "请补充测试场景",
      "options": [],
      "createdAt": "2026-08-18T08:00:00Z"
    }
  ],
  "page": {
    "nextCursor": null,
    "hasMore": false
  },
  "requestId": "req_..."
}
```















即使没有输入请求，也必须返回 `data: []`、`page.nextCursor: null` 和 `page.hasMore: false`。分页接口的 `requestId` 由服务端生成并回传，前端不得依赖裸数组响应。







### 25\.5 TaskStep 最新运行摘要



`GET /projects/{projectId}/tasks/{taskId}` 和 `GET /projects/{projectId}/tasks/{taskId}/steps` 返回的步骤项中，`latestRun` 为最近一次真实 TaskRun 的摘要；步骤从未执行时为 `null`。`latestRun` 的结构如下：



```JSON
{
  "id": "task-run-uuid",
  "status": "WAITING_INPUT",
  "statusSummary": "等待用户补充输入",
  "startedAt": "2026-08-18T08:00:00Z",
  "finishedAt": null,
  "durationMs": null
}
```



- `status` 为机器可读状态；`statusSummary` 为脱敏的用户可读摘要，与 TaskRun 列表和详情使用相同映射。

    

- `statusSummary` 不包含日志原文、Prompt、Token、环境变量、命令输出或凭据；未知/未提供状态时为 `null`。

    

- `runCount` 统计该步骤的全部真实 TaskRun（含重试），不得由前端根据状态自行推算。

    

    

    

### 25\.6 TaskRun 接口的可见性与操作权限



以下接口均先校验项目成员身份，再校验 Task 所属需求群的可见性：



- `GET /projects/{projectId}/tasks/{taskId}/task-runs`

    

- `GET /projects/{projectId}/task-runs?agentId=...`

    

- `GET /projects/{projectId}/task-runs/{taskRunId}`

    

- `GET /projects/{projectId}/task-runs/{taskRunId}/logs`

    

- `GET /projects/{projectId}/task-runs/{taskRunId}/execution-context`

    

- `GET /projects/{projectId}/task-runs/{taskRunId}/input-requests`

    

- `POST /projects/{projectId}/task-runs/{taskRunId}/retry`

    

- `POST /projects/{projectId}/task-runs/{taskRunId}/cancel`

    

- `POST /projects/{projectId}/task-runs/{taskRunId}/input-requests/{requestId}/reply`

    

- `POST /projects/{projectId}/task-runs/{taskRunId}/input-requests/{requestId}/approve`

    

- `POST /projects/{projectId}/task-runs/{taskRunId}/input-requests/{requestId}/reject`

    

    

    

Task 属于 `REQUIREMENT` 群时，调用者必须是该需求群的可见成员；没有需求群时按项目成员权限处理。不可见资源不得通过已知的 TaskRun UUID、Task UUID 或 Agent UUID 读取或操作，服务端返回统一的资源不可见/不存在错误。



操作接口的附加规则：



- `retry`、`cancel`、`reply` 还要求调用者是该运行的创建者或 Project Admin；不满足时返回权限错误。

- `approve`、`reject` 要求调用者具有项目管理员权限，并且 Task 仍属于可见需求群。

- `GET /projects/{projectId}/task-runs?agentId=...` 只返回调用者可见需求群下的运行，不因指定 Agent ID 而扩大可见范围。

- 列表、日志和 InputRequest 查询统一使用 cursor envelope；空结果仍返回 `data: []`、`page.nextCursor: null`、`page.hasMore: false`。



## 26\. v2\.0\.6 补充：团队 / 项目按最后活跃时间排序



为团队选择页与项目选择页按「最近活跃优先」展示新增两个聚合接口，均不分页、返回完整列表，排序完全由服务端完成，客户端无需二次排序；只读接口，无需 `Idempotency-Key`。



### 26\.1 团队按最后活跃排序 `GET /api/v1/teams/by-last-activity`



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/api/v1/teams/by-last-activity`|登录用户|返回当前用户加入的**全部**团队，按最后活跃时间倒序|



**最后活跃口径**：团队最后活跃 = 该团队下所有项目最后活跃的最大值；项目最后活跃 = 该项目下所有群（`PROJECT_MAIN` \+ `REQUIREMENT`）中 `MAX(COALESCE(last_message_at, created_at))`；无项目时以团队创建时间兜底。



响应 `data` 为 `TeamResponse[]`，新增字段 `lastActivityAt`（ISO8601 UTC）；其余字段（`id/name/role/memberCount/description/createdAt`）与 `GET /teams` 一致。



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



### 26\.2 项目按最后活跃排序 `GET /api/v1/teams/{teamId}/projects/by-last-activity`



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/api/v1/teams/{teamId}/projects/by-last-activity`|团队成员|返回某团队下当前用户可见的**全部**项目，按最后活跃时间倒序|



**最后活跃口径**：项目最后活跃 = 该项目下所有群（`PROJECT_MAIN` \+ `REQUIREMENT`）中 `MAX(COALESCE(last_message_at, created_at))`；无群时以项目创建时间兜底。



**可见性**：与 `GET /teams/{teamId}/projects` 一致——canonical Team Owner 可见团队全部项目，其余成员仅见 `project_members` 显式成员。



响应 `data` 为 `ProjectResponse[]`，新增字段 `lastActivityAt`（ISO8601 UTC）；其余字段（`id/teamId/name/description/role/status/memberCount/repositoryCount`）与现有项目列表一致。



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

## 27\. MR 分支开发锁定补充



本节为增量契约，补充分支、Workspace 续作与 MR 生命周期之间的开发约束。前文未明确的部分以本节为准。



### 27\.1 分支开发状态



工作分支的“是否允许继续开发”与 Task 的 `deliveryMode` 是两个不同概念：



- `DIFF_FIRST`：当前 Task 不强制创建 MR，但只适用于当前没有未合并 MR 的工作分支。

- `MR_FIRST`：当前 Task 必须完成 commit/push、MR 前预检和 MR 创建；创建 MR 不等于合并 MR。

- 工作分支存在未合并 MR 时，分支进入开发锁定状态；只有真实同步到 `MERGED` 后才解除。

    

以下 MR 状态均视为分支锁定：



```Plain Text
OPEN、REOPENED，以及 CLOSED 但未 MERGED 的 MR
```



本地占位记录、Project Admin 的同意交付、GitHub `mergeable=true` 或 CQ\+1 记录均不能单独解除分支锁。只有受控 GitHub 查询确认 `merged=true`，并将本地 MR 镜像更新为 `MERGED` 后，分支才可继续开发。



### 27\.2 工作分支列表字段



`GET /api/v1/projects/{projectId}/work-branches` 的每个工作分支项新增以下只读字段：



```JSON
{
  "sourceBranch": "feat/task-uuid",
  "developmentStatus": "AVAILABLE",
  "canContinueDevelopment": true,
  "cannotContinueReason": null,
  "openMergeRequest": null,
  "latestMergeRequest": null
}
```



字段约定：



|字段|类型|说明|
|---|---|---|
|`developmentStatus`|string|`AVAILABLE`、`LOCKED_BY_OPEN_MR`、`MERGED`。|
|`canContinueDevelopment`|boolean|由服务端依据真实 MR 状态派生，前端不得自行计算。|
|`cannotContinueReason`|string/null|不可继续时返回 `WORK_BRANCH_LOCKED_BY_OPEN_MR`，可继续时为 `null`。|
|`openMergeRequest`|object/null|当前分支任一未合并 MR 摘要；至少包含 `id`、`number`、`status`、`webUrl`。|
|`latestMergeRequest`|object/null|最近一个真实 MR 摘要；已合并时可返回 `status=MERGED`。|



多仓库 Workspace 的分支状态按最严格结果聚合：任一仓库仍存在未合并 MR，则整个 Workspace 的 `canContinueDevelopment=false`。



### 27\.3 Workspace 续作限制



创建 Task 时复用 Workspace，或通过回复 `type=DIFF` 消息创建 continuation Task，服务端必须检查该 Workspace 所有 worktree 的 `projectRepositoryId + sourceBranch` 是否存在未合并 MR。



存在未合并 MR 时，创建请求返回 `409`：



```JSON
{
  "error": {
    "code": "WORKSPACE_CONTINUATION_BLOCKED_BY_OPEN_MR",
    "message": "该工作分支存在未合并的 MR，合并前不能继续开发",
    "details": [
      {
        "projectRepositoryId": "project-repository-uuid",
        "sourceBranch": "feat/task-uuid",
        "mergeRequestId": "merge-request-uuid",
        "providerNumber": 42,
        "status": "OPEN"
      }
    ]
  },
  "requestId": "req_xxx"
}
```



该检查适用于普通 Task 创建、显式 Workspace 续作和 Diff 卡引用续作。客户端不得通过提交 `workspaceId`、`continuationOfTaskId`、`deliveryMode` 或其他字段绕过检查。



### 27\.4 Diff 交付限制



`POST /api/v1/projects/{projectId}/tasks/{taskId}/diff-review/confirm` 和 `retry-delivery` 在执行任何 commit/push 前，必须检查最终 Diff 所属的全部 source branch。



如果任一分支存在未合并 MR，服务端不得继续执行 Worker 写入，返回 `409`：



```JSON
{
  "error": {
    "code": "DIFF_DELIVERY_BLOCKED_BY_OPEN_MR",
    "message": "当前工作分支存在未合并的 MR，不能继续进行 Diff 交付",
    "details": [{ "mergeRequestId": "merge-request-uuid", "status": "OPEN" }]
  },
  "requestId": "req_xxx"
}
```



该冲突不能被统一转换为普通 `DELIVERY_FAILED`。前端应引导用户查看 MR；如果需要独立开发，应创建新的 Workspace/feature branch。



### 27\.5 写入租约与最终防线



Workspace 写入租约仍然是并发控制，不等同于分支开发锁。以下入口在实际写入前都必须同时满足“持有有效写入租约”和“分支没有未合并 MR”：



- Coding Agent 写工具；

- Worker 创建可写 Sandbox；

- TaskRun 执行写入步骤；

- Diff confirm/retry 的 commit/push。

    

数据库事务持有行锁期间不得调用 Worker 或 GitHub。服务端应采用短事务认领、事务外检查/调用、短事务落库；在事务外等待期间发现分支新建 MR 时，必须阻止后续写入。



### 27\.6 MR 合并与解锁



`POST /api/v1/projects/{projectId}/merge-requests/{mergeRequestId}/merge` 仍仅允许 `PROJECT_ADMIN`。MR 合并成功后：



1. 服务端必须确认 GitHub 返回真实 `merged=true`；

2. 将本地 MR 状态更新为 `MERGED`；

3. 发布 `merge-request.updated` 和 `work-branch.updated`；

4. 前端重新查询工作分支、Task 和 MR，不得仅凭事件 payload 直接改写实体缓存。

    

`CLOSED` 但未合并的 MR 不触发解锁。多仓库 Workspace 只有所有相关 source branch 的未合并 MR 都已进入 `MERGED`，才允许继续复用该 Workspace。



### 27\.7 前端展示与错误处理



客户端只消费服务端返回的 `developmentStatus`、`canContinueDevelopment` 和 `cannotContinueReason`：



- `AVAILABLE`：展示“继续开发”；

- `LOCKED_BY_OPEN_MR`：展示“MR 审查中/等待合并”，提供 MR 详情入口；

- `MERGED`：展示“已合并”，恢复继续开发入口。

    

收到 `WORKSPACE_CONTINUATION_BLOCKED_BY_OPEN_MR` 或 `DIFF_DELIVERY_BLOCKED_BY_OPEN_MR` 时，不得显示通用系统失败，应提示当前 MR 未合并并刷新分支状态。



### 27\.8 与交付状态的关系



```Plain Text
DIFF_FIRST:
  RUNNING -> WAITING_DIFF_CONFIRMATION -> commit/push -> SUCCEEDED
  当前分支无 Open MR 时，Task 完成后可以创建 continuation Task。

MR_FIRST:
  RUNNING -> DELIVERING -> WAITING_PREFLIGHT
  -> 自动 Dry Run
  -> 独立 CQ+1
  -> 服务端自动创建 MR
  -> SUCCEEDED
  Task 完成不代表 MR 已合并；但创建 MR 后该 source branch 锁定，
  必须等真实 MERGED 才能创建新的 continuation Task。
```



“同意交付”只表示 Diff 交付授权，不表示 MR 已创建或已合并；

创建 MR 和合并 MR 仍然是两个不同动作。

MR\_FIRST 的创建动作由服务端在预检和 CQ\+1 通过后自动触发；

合并动作仍只能由 Project Admin 执行。

### 27\.9 本次实现补充：分支锁定门禁的实际返回



以下服务端入口统一执行工作分支锁定检查：



- 复用 Workspace 创建 continuation Task；

- `diff-review/confirm`、`retry-delivery` 的 commit/push；

- Worker 创建可写 Sandbox 及每次工具调用前的写租约续期；

- 已接受 Diff 的受控 push；

- 创建 MR 时对同一 source branch 的幂等校验。

    

`OPEN` 和 `CLOSED`（尚未合并）的 MR 都会锁定 source branch；只有真实同步为 `MERGED` 才解锁。已有 `OPEN` MR 且 headCommit 不同的请求不能再推送或更新该 MR，必须返回 `409`：



```JSON
{
  "error": {
    "code": "MR_BRANCH_LOCKED_BY_OPEN_MR",
    "message": "该工作分支已有未合并的 MR，不能继续推送新的提交",
    "details": [
      {
        "mergeRequestId": "merge-request-uuid",
        "providerNumber": 42,
        "status": "OPEN",
        "sourceBranch": "feat/task-uuid"
      }
    ]
  },
  "requestId": "req_xxx"
}
```



Worker 在已有 MR 后拒绝写入时返回 `WORKSPACE_WRITE_BLOCKED_BY_OPEN_MR`；该错误属于分支状态冲突，前端应刷新工作分支和 MR，不得显示为普通 Worker 故障。工作分支列表中的 `latestMergeRequest` 可为 `OPEN/MERGED/CLOSED`，`openMergeRequest` 仅在状态为 `OPEN` 时返回；`developmentStatus=LOCKED_BY_OPEN_MR` 也覆盖 CLOSED 未合并状态。





### 27\.10 MR\_FIRST 自动预检与创建 MR



本节覆盖 `MR_FIRST` 的自动推进实现；与前文存在差异时，以本节为准。



`MR_FIRST` 任务完成代码开发并完成全部仓库的 `commit/push` 后，服务端自动发起各仓库的 Dry Run，不要求用户再次确认 Diff，也不要求用户在正常路径手动点击“创建 MR”。



```Plain Text
Task 开发完成
  → commit/push 到 feat/<taskId>
  → Task = WAITING_PREFLIGHT
  → 服务端自动创建 Dry Run（每个仓库一条）
  → Dry Run 自动使用目标分支最新基线
  → 自动加载目标分支绑定的 Testset
  → Dry Run = PASSED
  → 独立项目成员提交 CQ+1
  → 服务端自动幂等创建 GitHub MR
  → 全部仓库 MR 创建成功后 Task = SUCCEEDED
```



自动流程的约束：



- 自动 Dry Run 必须固定当前 Workspace worktree 的 `headCommit`、目标分支名称和刷新后的 `targetCommit`；任一提交或目标基线变化都会使旧 Dry Run/CQ\+1 失效。

- Dry Run 必须包含目标分支绑定的 Testset；Testset 结果属于 Dry Run 门禁事实，不要求额外写入不存在的 MR 级 `TESTSET` 检查。

- Task 发起人不能提交自己的 CQ\+1；只有独立项目成员的 `APPROVED` 记录可以触发自动创建 MR。

- 任一仓库的 Dry Run 未通过、CQ\+1 缺失/拒绝、代码交付失败或上下文过期时，不得创建该仓库 MR。

- 多仓库任务按仓库独立创建 MR；只有全部已接受 Diff 对应的 MR 都创建成功后，Task 才进入 `SUCCEEDED`。

- 创建 MR 与合并 MR 仍是两个不同动作。MR 创建成功后 source branch 锁定，只有 Project Admin 合并并由 Webhook/同步确认 `MERGED` 后才解除锁定。

    

#### 自动流程中的接口职责



以下公开接口仍然保留：



|方法|路径|自动流程中的职责|
|---|---|---|
|`POST`|`/projects/{projectId}/dry-runs`|用户手动测试或自动流程的受理接口；`MR_FIRST` 正常路径由服务端内部调用同一服务逻辑|
|`POST`|`/projects/{projectId}/dry-runs/{dryRunId}/cq-approvals`|独立成员提交 CQ\+1；通过后触发自动创建对应仓库 MR|
|`POST`|`/projects/{projectId}/dry-runs/{dryRunId}/cq-rejections`|拒绝当前预检并给出修改意见；不会创建 MR|
|`POST`|`/projects/{projectId}/merge-requests`|保留为幂等补偿、异常重试和 Diff\-first 后续手动创建 MR 的接口；`MR_FIRST` 正常路径不要求前端主动调用|



前端应通过以下事件刷新自动流程状态，不应仅依赖本地按钮状态：



- `task.updated`：Task 进入 `WAITING_PREFLIGHT` 或全部 MR 创建后进入 `SUCCEEDED`；

- `dry-run.updated`：Dry Run 的 `QUEUED/RUNNING/PASSED/FAILED` 状态；

- `preflight.updated`：独立成员 CQ\+1 的 `APPROVED/REJECTED` 决定；

- `merge-request.updated`：真实 MR 创建或同步状态变化；

- `work-branch.updated`：source branch 锁定或合并后解除锁定。

    

自动创建失败时，Task 保持 `WAITING_PREFLIGHT`，由服务端补偿任务按照当前 `headCommit`、目标分支基线和门禁事实重试；前端可调用 `POST /merge-requests` 作为人工补偿，但不能把“已受理自动创建”展示为 MR 已创建。

### 27\.11 MR 普通评论



MR 评论用于在真实 GitHub Pull Request 对应的 Issue 讨论中留下普通审查意见。Diff 审查阶段的文件行级评论继续使用 `12.3` 的 Diff 评论接口，本节不重复定义逐行评论。



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/projects/{projectId}/merge-requests/{mergeRequestId}/comments`|Project Member|查询 Qgents 创建的该 MR 普通评论，按创建时间升序返回|
|`POST`|`/projects/{projectId}/merge-requests/{mergeRequestId}/comments`|Project Member|在真实 GitHub MR 对应的 Issue 讨论中创建普通评论；仅 `OPEN` MR 可评论|



**创建请求**：



```JSON
{
  "body": "请补充异常分支的测试说明"
}
```



服务端只接受评论正文，不接受 `userId`、GitHub Token、provider comment id 或 Git SHA。评论请求先经 GitHub App 受控调用成功，再落库为 `merge_request_comments` 镜像；GitHub 调用失败时不产生本地成功评论。写接口必须携带 `Idempotency-Key`。



创建成功后发布项目 SSE 事件 `merge-request.comment.created`，payload 只包含 `mergeRequestId`、本地 `commentId` 和 `providerCommentId`。客户端收到事件后重新读取评论列表，不应把事件 payload 当作完整评论 DTO。

## 28\.团队头像与成员头像

### 28\.1 团队头像上传

- `credential` 请求体 `{ "mediaType": "image/png", "sizeBytes": 20480 }`，响应 `{ objectKey, uploadUrl, method, headers, expiresAt }`（与用户/Agent 头像一致）。

- `confirm` 请求体 `{ "objectKey": "teams/{teamId}/{uuid}.png" }`，响应 `{ avatarUrl }`。

- 对象键前缀必须匹配 `teams/{teamId}/`，否则 `403 AVATAR_OBJECT_KEY_FORBIDDEN`；对象未真实上传返回 `409 AVATAR_NOT_UPLOADED`；OSS 未启用返回 `501 AVATAR_STORAGE_NOT_CONFIGURED`。

### 28\.2 头像字段

- `TeamResponse`（`GET /teams`、`GET /teams/{teamId}`、`GET /teams/by-last-activity`）新增 `avatarUrl`（团队头像，可为空）。

- `POST /teams` 创建请求可带 `avatarUrl`；`PATCH /teams/{teamId}` 可带 `avatarUrl`（null 保留原值，空串清空）。

- `GET /teams/{teamId}/members` 成员列表新增 `avatarUrl`（**用户头像** `users.avatar_url`，可为空），通讯录据此展示成员头像；无头像时前端回退首字占位。

### 28\.3 前端创建团队页头像流程

创建团队页选图预览 → `POST /teams` 创建（此时无 teamId 无法先传）→ 用返回的 `teamId` 走 `credential → OSS PUT → confirm` 拿到 `avatarUrl` → `PATCH /teams/{teamId}` 回写 → 跳转团队详情。头像上传失败不阻断团队创建（提示后继续跳转）。





## 29\. 可靠消息同步与移动端推送增量契约（2026\-08\-19）



本节覆盖并补充本文前文关于“WebSocket、离线同步与移动端推送”的说明。已有消息历史分页和 SSE 接口保持兼容；可靠恢复以持久化 SSE 游标和下方消息增量接口为准，WebSocket 仍只是刷新提示，不提供 ACK 或持久游标。



### 29\.1 群消息增量拉取



`GET /api/v1/projects/{projectId}/groups/{groupId}/messages/incremental?afterSequence={n}&limit={n}`



当前登录用户必须是该需求群成员。`afterSequence` 必填且非负，`limit` 默认 100，最大 100。服务端只返回 `sequence > afterSequence` 的消息，并按 `sequence` 升序返回；`page.nextCursor` 是本页最后一条消息序号，`hasMore=true` 时继续用它作为 `afterSequence`。该游标与原消息历史接口的 `cursor` 不同。



消息发送仍由服务端在群行锁内分配群内单调递增 `sequence`；同一群内 `clientMessageId` 唯一，断线重试命中时返回原消息，不重复创建消息或序号。`message.created`/`message.updated` 事件 payload 新增 `sequence`，客户端按 `message.id` 去重后合并。



### 29\.2 实时恢复规则



项目、团队和通知 SSE 均支持 `Last-Event-ID` 续传；事件 ID 是对应作用域的持久化序号，游标过期返回 `409 EVENT_CURSOR_EXPIRED`。客户端收到 WebSocket 刷新帧或 SSE 消息事件后，必须以 REST 查询结果为准；聊天消息使用 27\.1 的 `afterSequence` 补齐断线期间的消息。



### 29\.3 移动端推送设备



|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/api/v1/push/devices`|登录用户|注册/刷新 Android 或 iOS 的 FCM Token；必须携带 `Idempotency-Key`|
|`DELETE`|`/api/v1/push/devices/{installationId}`|登录用户|幂等停用当前用户的安装实例；必须携带 `Idempotency-Key`|



注册请求为 `{installationId, platform, token}`。Token 仅写入，服务端保存 SHA\-256 摘要和 AES\-GCM 密文，响应不回显 Token。通知落库后为每个活动设备创建唯一 Outbox 投递记录，后台按 `PENDING → SENDING → SENT` 或指数退避 `FAILED` 状态机调用 FCM；FCM 返回的真实消息 ID 才能写入 `SENT`。



离线推送必须配置 `QGENTS_PUSH_ENABLED`、FCM HTTP v1 endpoint、`QGENTS_PUSH_FCM_PROJECT_ID`、Base64 编码的服务账号 JSON 和 Base64 32 字节 Token 加密密钥。当前工作区没有 Kotlin Android/iOS 工程，原生端需按 27\.3 接入 FCM 初始化、权限申请、Token 刷新和熄屏通知展示；未配置 provider 时设备注册返回 `503 PUSH_NOT_CONFIGURED`，已存在的站内通知仍保留，不报告推送已送达。



## 30\. 自定义 Agent 发布审核



### 30\.1 状态机



自定义 Agent 发布为 `TEAM` 需 **Team Owner 审核**，不再直接发布：



```Plain Text
PRIVATE（默认，仅创建者）──publish──▶ PENDING（待审核）
    ▲                                   │
    └────── reject（Team Owner）────────┘
                                        │ approve（Team Owner）
                                        ▼
                                   TEAM（团队共享，不可回私有）──archive──▶ ARCHIVED
```



- `visibility` 取值：`PRIVATE` / `PENDING` / `TEAM`（DB `CHECK` 已放宽）。

- **已发布 ****`TEAM`**** 不可收回为私有**：`unpublish` 废弃，返回 `409 AGENT_UNPUBLISH_DISALLOWED`，只能归档。

- `PENDING` 对创建者与 Team Owner 可见（列表/详情）；拒绝后回到 `PRIVATE`，创建者可修正后重新提交。

    

### 30\.2 接口



|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/api/v1/teams/{teamId}/agents/{agentId}/publish`|创建者|提交发布审核（`PRIVATE+ACTIVE` → `PENDING+ACTIVE`）；需 `Idempotency-Key`|
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



### 30\.3 交付中心 AGENT 类型



- `GET /api/v1/projects/{projectId}/delivery-items?type=AGENT`（及 `delivery-summary`、export）支持 AGENT 类型。

- 进入范围：`PENDING`（待审核）/`TEAM`（已批准）/`ARCHIVED`（已归档）；`PRIVATE` 与系统预置 Agent 不进入。

- 专属字段：`role`、`descriptionExcerpt`、`isDefault`（恒 false）；**不返回 ****`prompt`**** 全文**。

- 展示状态：`PENDING`→`PENDING_REVIEW`、`TEAM`→`ACCEPTED`、`ARCHIVED`→`ARCHIVED`。

- `openTarget = { kind: "AGENT", agentId }`。

- 操作能力：`canApprove`/`canReject` = Team Owner 且 PENDING；`canArchive` = 创建者或 Team Owner 且 TEAM；`canSubmitReview` 恒 false（提交审核走 Agent 管理页 `publish`，不进交付中心）。

## 31\.团队 / 项目 PATCH 语义



`PATCH /teams/{teamId}` 与 `PATCH /projects/{projectId}` 均为**部分更新**：

- `name` / `description` / `avatarUrl` 任一可单独提交（null 保留原值；空串清空 `description`/`avatarUrl`）。

- 团队 PATCH：`{ "avatarUrl": "..." }` 单独更新头像（此前 `name` 必填会报 `INVALID_ARGUMENT`，已修复）。

- 项目 PATCH：`{ "avatarUrl": "..." }` 单独更新头像；全字段缺省返回 `422 INVALID_PROJECT_OPERATION`「至少需要提供 name、description 或 avatarUrl」。

    

### 31\.1 项目头像



|方法|路径|权限|说明|
|---|---|---|---|
|`POST`|`/api/v1/projects/{projectId}/avatar/credential`|项目成员|签发项目头像直传凭证；对象键 `projects/{projectId}/{uuid}.{ext}`|
|`POST`|`/api/v1/projects/{projectId}/avatar/confirm`|项目成员|确认上传并返回公共读 URL；不写任何字段（前端随 `PATCH /projects/{projectId}` 回写）|



- `ProjectResponse`（`GET /teams/{teamId}/projects`、`GET /projects/{projectId}`、`GET /teams/{teamId}/projects/by-last-activity`）新增 `avatarUrl`（可为空）。

- 前端入口：项目群聊/主群聊天页右上角「群聊设置」→「项目头像」区块（上传/更换）。

- 错误码与团队头像一致：`403 AVATAR_OBJECT_KEY_FORBIDDEN` / `409 AVATAR_NOT_UPLOADED` / `501 AVATAR_STORAGE_NOT_CONFIGURED`。

- 前端同步约定：上传确认后 URL 追加版本参数 `?v=<时间戳>` 强制浏览器刷新，并同时失效 `['projects', projectId]` 与 `['teams', teamId, 'projects']` 两类查询。

    

    ## 32\. Dry Run 前端实施要求

    

    本节是主文档 §28 的前端落地清单。Dry Run 创建成功返回 `202` 仅代表排队，不代表测试通过、CQ\+1、MR 创建或合并完成。

    

    ### 32\.1 请求与响应类型

    

    创建请求仍为 `repositoryId`、`sourceRef`、`targetBranch`，`taskId` 可选；前端**不要提交 ****`targetCommit`**。服务端刷新目标分支后返回固定 `targetCommit`，前端应在报告页展示该 SHA。

    

    报告至少消费以下字段：

    

    |    字段|    用途|
    |---|---|
    |    `id`|    当前 Dry Run 标识；重试后替换为新 ID|
    |    `status`|    `QUEUED/RUNNING/PASSED/FAILED/CANCELLED`|
    |    `headCommit`|    本次执行的源提交 SHA|
    |    `targetBranch`|    目标分支名|
    |    `targetCommit`|    本次执行固定的目标提交 SHA|
    |    `attemptCount`|    服务端记录的执行次数|
    |    `createdAt` / `updatedAt`|    创建和最后更新时间|
    |    `report`|    冲突、测试汇总和脱敏失败摘要；排队/运行时可为 `null`|

    

    `report.tests.results[]` 逐项包含 `testsetId`、`status`、`exitCode`、`durationMs`、`failureCode`、`message`。`message` 是脱敏摘要；禁止展示原始 stdout/stderr、命令、凭据或宿主机路径。

    

    ### 32\.2 重试按钮与错误处理

    

    调用 `POST /api/v1/projects/{projectId}/dry-runs/{dryRunId}/retries`，请求体为空，必须带 `Idempotency-Key`。成功后使用返回的**新** Dry Run ID 重新查询报告，原报告保持只读。

    

    |    错误码|    前端行为|
    |---|---|
    |    `DRY_RUN_RETRY_NOT_ALLOWED`|    隐藏重试按钮；提示该失败需要修复代码或配置|
    |    `DRY_RUN_RETRY_EXHAUSTED`|    提示已达到重试上限|
    |    `DRY_RUN_RETRY_IN_PROGRESS`|    提示已有重试在执行并等待 `dry-run.updated`；错误体不返回已有子运行 ID，不自行猜测|
    |    `IDEMPOTENCY_KEY_REQUIRED` / `IDEMPOTENCY_KEY_REUSED`|    按通用幂等错误处理，不创建第二次业务重试|

    

    仅以下基础设施失败显示重试：`DRY_RUN_TIMEOUT`、`SANDBOX_WORKER_UNAVAILABLE`、`SANDBOX_WORKER_ERROR`、`GITHUB_API_UNAVAILABLE`、`GIT_STORE_FETCH_FAILED`。`GIT_MERGE_CONFLICT`、`TESTSET_FAILED`、上下文不匹配和配置错误不能通过该按钮解决。

    

    Dry Run 历史列表通过第 38 节的 `GET /api/v1/projects/{projectId}/dry-runs` 获取。正常 `202` 响应中的新 ID 可写入本机会话历史作为即时入口，但本地历史不是服务端权威状态；列表页应在进入页面、筛选条件变化和收到 `dry-run.updated` 后重新请求服务端列表。

    

    ### 32\.3 状态与 SSE

    

    状态文案为：`QUEUED` 排队中、`RUNNING` 运行中、`PASSED` 通过、`FAILED` 失败、`CANCELLED` 已取消。不要增加顶层 `CONFLICT` 状态；冲突使用 `GIT_MERGE_CONFLICT` 或 `tests.reason=MERGE_CONFLICT` 展示。

    

    `dry-run.updated` 事件必须解析 `dryRunId/status/headCommit/targetBranch/targetCommit`。事件只触发 Query 失效和重新 GET，不直接拼装报告；重复、乱序和断线重连均以 REST 报告为准。SSE 游标过期时处理 `409 EVENT_CURSOR_EXPIRED`，重连后主动刷新当前报告。

    

    ### 32\.4 MR\_FIRST 和验收

    

    - 任一仓库 Dry Run 未 `PASSED` 时，不显示 MR 已创建；CQ\+1 也按仓库单独展示。

    - 多仓库分别展示 Dry Run、目标 SHA、CQ 和 MR，不能由一个仓库的通过状态推导全部通过。

    - 验收覆盖状态流转、SHA 展示、逐 Testset `message/failureCode`、可重试错误按钮、重复点击幂等、SSE 重复/乱序/断线恢复，以及失败时不误报 MR 创建。

---

## 33\. TaskRun 运行日志完整化（已落地）



### 33\.1 日志与内部轨迹边界



- `GET /api/v1/projects/{projectId}/task-runs/{taskRunId}/logs` 返回已持久化、脱敏后的运行日志；日志不等同于 TaskRun 详情中的结构化 `steps`。

- `steps` 用于展示执行节点、状态和耗时等结构化内部轨迹，前端不得根据日志文本推断步骤状态。

- 日志写入成功后才发布 `task-run.step.progress` SSE 事件；前端可使用 `sequence` 游标断线补拉。

- 终态摘要若作为日志返回，必须通过 `entryType=TERMINAL` 与真实执行日志区分；失败原因以 `statusReason` 为准。

    

### 33\.2 前端展示约定



- `entryType=EXECUTION`：显示为 Agent/Worker 执行日志。

- `entryType=SYSTEM`：显示为 TaskRun 生命周期日志。

- `entryType=TERMINAL`：显示为结果摘要，不显示为普通执行日志。

- 日志为空不代表执行失败；成功任务应显示成功状态和“暂无详细运行日志”。

    

## 34\. Task 启动失败原因（已落地）



### 34\.1 Task 详情



当任务在 Sandbox/Worker 初始化阶段失败、尚未创建 TaskRun 时，`GET /api/v1/projects/{projectId}/tasks/{taskId}` 仍返回 `statusReason`：



```JSON
{
  "code": "STARTUP_FAILED",
  "failureCode": "SANDBOX_WORKER_ERROR",
  "title": "任务启动失败",
  "summary": "Sandbox Worker 当前不可用",
  "retryable": true,
  "occurredAt": "2026-08-19T08:00:00Z"
}
```



`failureCode` 为稳定错误码，`summary` 为脱敏用户文案。原始堆栈、Worker 地址、命令参数、Token、环境变量和宿主机路径不会返回给客户端。



### 34\.2 `task.updated` SSE



任务启动失败时，`task.updated` payload 增加以下字段；字段仅在任务存在失败原因时返回：



```JSON
{
  "taskId": "task-uuid",
  "status": "FAILED",
  "failureCode": "GIT_BASE_REF_NOT_FOUND",
  "failureReason": "代码仓库基线不可用",
  "failureRetryable": true,
  "failureOccurredAt": "2026-08-19T08:00:00Z"
}
```



前端收到事件后应重新请求 Task 详情，以 REST 响应为最终状态；重试成功认领任务后，旧的 Task 级失败字段会被清除。

## 35\. 项目远程分支管理



本节管理项目绑定仓库在 GitHub 上的真实远程分支（`refs/heads/*`），不等同于 `GET /api/v1/projects/{projectId}/work-branches` 返回的 Task/Workspace 工作分支。



### 35\.1 查询远程分支



项目成员可查询项目绑定仓库的远程分支：



```HTTP
GET /api/v1/projects/{projectId}/repositories/{projectRepositoryId}/branches
```



成功返回 `200`。响应统一使用 `ApiResponse` 包装：



```JSON
{
  "data": [
    {
      "name": "develop",
      "commitSha": "0123456789abcdef0123456789abcdef01234567",
      "githubDefault": false,
      "projectDefault": true
    }
  ],
  "requestId": "req_xxx"
}
```



其中：



- `githubDefault` 表示 GitHub 仓库全局默认分支；

- `projectDefault` 表示当前 Qgents 项目的默认基准分支；

- `projectRepositoryId` 是 ProjectRepository 响应中的 `id`，不是授权仓库 `repositoryId` 或 GitHub 数字 ID。

    

### 35\.2 创建远程分支



仅 Project Admin 可创建项目绑定仓库的远程分支。创建请求必须携带 `Idempotency-Key`：



```HTTP
POST /api/v1/projects/{projectId}/repositories/{projectRepositoryId}/branches
Idempotency-Key: <stable-key>
Content-Type: application/json
```



请求体：



```JSON
{
  "name": "develop",
  "fromRef": "main"
}
```



后端会在 GitHub 上解析 `fromRef` 的当前提交，再创建 `refs/heads/{name}`；客户端不得提交 Git SHA，也不得接触 GitHub App 安装令牌。分支名不得包含 `refs/` 前缀、`..`、`//`、`@{` 或反斜杠。创建操作不会创建 Qgents Task 或 Workspace。



成功返回 `200`，响应统一使用 `ApiResponse` 包装：



```JSON
{
  "data": {
    "name": "develop",
    "commitSha": "0123456789abcdef0123456789abcdef01234567",
    "githubDefault": false,
    "projectDefault": true
  },
  "requestId": "req_xxx"
}
```



同一目标分支的重复请求具有幂等恢复行为：如果远程分支已存在且其提交 SHA 与 `fromRef` 一致，返回已有分支；如果已存在但提交 SHA 不一致，返回 `409 GIT_BRANCH_ALREADY_EXISTS`。



### 35\.3 默认基准分支与边界



项目仓库的 `PATCH /api/v1/projects/{projectId}/repositories/{projectRepositoryId}` 设置默认基准分支时，后端会先校验该分支在 GitHub 上真实存在、仓库未归档且 Installation 为 `ACTIVE`，校验通过后才更新 `project_repositories.default_branch`。该字段是 Qgents 项目级基准配置，不会修改 GitHub 全局默认分支，也不会改变已有 Workspace 的 `baseRef`。第一版前端不调用该 PATCH 修改默认分支。



### 35\.4 错误码



|HTTP|错误码|含义|
|---|---|---|
|`400`|`INVALID_BRANCH_NAME`|分支名为空、格式不合法，或新分支与来源分支相同|
|`403`|`GITHUB_REPOSITORY_ACCESS_DENIED`|当前用户不是项目成员或管理员|
|`403`|`GITHUB_BRANCH_CREATE_FORBIDDEN`|GitHub App 没有创建分支所需的 Contents 写权限|
|`403`|`GITHUB_BRANCH_READ_FORBIDDEN`|GitHub App 没有读取分支所需的 Contents 读权限|
|`404`|`PROJECT_REPOSITORY_NOT_FOUND`|项目绑定不存在或不属于当前项目|
|`404`|`GIT_BRANCH_NOT_FOUND`|来源分支或默认分支不存在|
|`409`|`GIT_BRANCH_ALREADY_EXISTS`|目标远程分支已存在且指向不同提交|
|`409`|`PROJECT_REPOSITORY_UNBOUND`|项目仓库已软解绑|
|`409`|`GITHUB_REPOSITORY_UNAVAILABLE`|仓库已撤权、归档或不可用|
|`409`|`GITHUB_INSTALLATION_UNAVAILABLE`|GitHub App Installation 不可用|
|`502`|`GITHUB_API_UNAVAILABLE`|GitHub API 暂时不可用或返回不完整数据|

## 36\. Workspace 实时 Diff Preview（2026\-08\-19）



实时 Preview 只反映 Coding 执行过程中的**累积工作树变更**，不代表已生成正式 Diff，不代表已 commit、push 或创建 MR。正式 Diff 仅在 Task 完成后生成，用于用户确认与后续交付。



### 36\.1 与正式 Diff 的区别



|类型|产生时机|是否可变|用途|是否可交付|
|---|---|---|---|---|
|Workspace Diff Preview|Coding 每次有效写入后|是，通过 revision 递增|执行过程中的实时查看|否|
|Task 正式 Diff|Task 完成后|否|用户审查、确认、交付|是|



实时 Preview 的统计不能写入现有 `artifactSummary.diffCount` 等正式 Diff 摘要字段，否则会让用户误以为正式 Diff 已生成。



### 36\.2 SSE 事件



事件名：`workspace.diff-preview.updated`，广播范围使用 Task 所属需求群（PROJECT\_MAIN 项目总群任务仍广播全部项目成员）。



```JSON
{
  "eventVersion": 1,
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "taskRunId": "task-run-uuid",
  "workspaceId": "workspace-uuid",
  "previewRevision": 3,
  "filesChanged": 2,
  "additions": 8,
  "deletions": 3,
  "updatedAt": "2026-08-19T12:00:00Z"
}
```



约束：



- Payload 只含元数据，**不含 patch、源码、Token、密码、宿主机路径、环境变量或命令原文**。

- 事件在 revision 落库成功后才发布；可重复投递，前端收到后必须以 REST 查询结果为准，不依赖事件顺序。

- `previewRevision` 是 Workspace 内单调递增的非负整数；`filesChanged/additions/deletions` 为非负整数。

    

### 36\.3 查询 Preview 详情



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/api/v1/projects/{projectId}/tasks/{taskId}/workspace-diff-preview`|Project Member \+ Task 需求群成员|缺省返回最新修订|
|`GET`|`/api/v1/projects/{projectId}/tasks/{taskId}/workspace-diff-preview?revision={revision}`|同上|返回指定修订|



响应 `data`：



```JSON
{
  "projectId": "project-uuid",
  "taskId": "task-uuid",
  "taskRunId": "task-run-uuid",
  "workspaceId": "workspace-uuid",
  "revision": 3,
  "baseCommit": "base-sha",
  "workingTreeHash": "sha256:...",
  "filesChanged": 2,
  "additions": 8,
  "deletions": 3,
  "patch": "diff --git ...",
  "createdAt": "2026-08-19T12:00:00Z"
}
```



- `patch` 从受控快照读取；快照已清理或读取失败时为 `null`，前端不得把 `null` 当作正式 Diff 缺失。

- 非需求群成员（项目成员但未加入该群）→ `403 FORBIDDEN GROUP_MEMBER_REQUIRED`。

- 任务不存在 / 不属于当前项目 / 指定修订不存在 → `404 WORKSPACE_DIFF_PREVIEW_NOT_FOUND`。

    

### 36\.4 查询 Preview 文件列表



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/api/v1/projects/{projectId}/tasks/{taskId}/workspace-diff-preview/files`|Project Member \+ Task 需求群成员|缺省返回最新修订的文件列表|
|`GET`|`/api/v1/projects/{projectId}/tasks/{taskId}/workspace-diff-preview/files?revision={revision}`|同上|返回指定修订的文件列表|



文件条目至少包含：



```JSON
{
  "path": "src/main.ts",
  "changeType": "MODIFIED",
  "additions": 3,
  "deletions": 1,
  "binary": false,
  "repositoryPath": "repo-1"
}
```



- `changeType` 枚举与正式 Diff 一致：`ADDED` / `MODIFIED` / `DELETED` / `RENAMED`。

- `repositoryPath` 为该文件所属仓库在 Workspace 内的相对目录；多仓库 patch 以 `===== repositoryPath =====` 分隔行归属，单仓库或无分隔时为空。前端按 `repositoryPath` 分组展示，不得跨仓库混合同名文件。

- 文件路径是 Workspace 相对路径，不包含宿主机绝对路径。

    

### 36\.5 权限与归属



- 查询校验两级：先校验当前用户是项目成员，再校验属于 Task 所属需求群；PROJECT\_MAIN 项目总群任务按项目成员可见性处理。

- 修订行按 `projectId + taskId + workspaceId`（\+ 可选 `revision`）精确匹配，Task A / Task B 复用同一 Workspace 时互不可见。

- 身份、项目归属与仓库范围全部由服务端从认证上下文和持久化数据判断，不信任客户端提交的 `actor/projectId/workspaceId` 字段。

    

### 36\.6 失败与降级



- Worker 未启用、diff 不可用、无 workingTreeHash 或快照存储失败时，本次 Preview **不产生 revision、不发事件、不影响 Coding / Task 结果**。

- 前端在无 revision 或查询失败时展示“实时预览暂不可用”，不得把 Task 标记为失败。

- `RUNNING` / `FAILED` / `SUCCEEDED` 状态均不能仅凭 Preview 推断正式交付状态。

    

### 36\.7 前端接入要求



- 注册 `workspace.diff-preview.updated` 到 SSE 解析白名单，校验 `eventVersion == 1`、必要 ID 非空、统计字段为非负整数。

- 收到事件后只刷新该 Task 的 Preview detail / files 查询缓存，**不刷新正式 ****`diffs`**** 查询**。

- 任务详情“最近执行”卡片独立展示实时状态，例如：`实时预览：2 个文件 · +8 / -3 · revision 3`，与正式 Diff 摘要分开。

- Task 完成并生成正式 Diff 后，正式交付区域优先展示正式 Diff；实时 Preview 可折叠保留。

    

前端类型建模参考：



```TypeScript
interface WorkspaceDiffPreview {
  projectId: string
  taskId: string
  taskRunId: string | null
  workspaceId: string
  revision: number
  baseCommit: string | null
  workingTreeHash: string | null
  filesChanged: number
  additions: number
  deletions: number
  patch: string | null
  createdAt: string
}

interface WorkspaceDiffPreviewFile {
  path: string
  changeType: 'ADDED' | 'MODIFIED' | 'DELETED' | 'RENAMED'
  additions: number
  deletions: number
  binary: boolean
  repositoryPath: string | null
}
```



### 36\.8 验收清单



- 单仓库：Coding 写入 `a.txt` 产生 revision 1，再写 `b.txt` 产生 revision 2，前端累计显示 2 个文件。

- 多仓库：`/files` 按 `repositoryPath` 正确分组，同名文件不混淆。

- 复用 Workspace 的 Task 之间互不可见 Preview。

- 需求群外项目成员不能读取其他群 Task 的 Preview。

- Worker 停止时 Task 不因 Preview 失败而失败，前端显示“实时预览暂不可用”。

- 正式 Diff 仍只在 Task 完成后生成；确认前不 commit/push/MR。

- SSE 断线重连后按游标恢复并重新查询最新 Preview。

## 38\. Test Run 与 Dry Run 历史列表接口（已实现）



本节补充项目级 Test Run / Dry Run 历史查询。两个接口只返回轻量生命周期摘要，不返回测试用例结果、Dry Run 报告或 Sandbox 详情；详情继续使用第 12\.4 节已有的单条接口。



### 38\.1 接口总览



|方法|路径|权限|说明|
|---|---|---|---|
|`GET`|`/api/v1/projects/{projectId}/test-runs`|Project Member|查询项目 Test Run 历史列表|
|`GET`|`/api/v1/projects/{projectId}/dry-runs`|Project Member|查询项目 Dry Run 历史列表|



`repositoryId` 指当前项目中的仓库绑定 ID（`project_repositories.id`），不是 GitHub 仓库数字 ID。服务端会先校验当前用户是项目成员，并将所有查询限制在路径中的 `projectId` 内。



### 38\.2 Test Run 列表



请求：



```HTTP
GET /api/v1/projects/{projectId}/test-runs
```



可选查询参数：



|参数|类型|说明|
|---|---|---|
|`repositoryId`|UUID|仅返回指定项目仓库的运行|
|`taskId`|UUID|仅返回指定 Task 关联的运行|
|`status`|string|状态筛选，可传逗号分隔值：`QUEUED,RUNNING,PASSED,FAILED,CANCELLED`|
|`createdByUserId`|UUID|仅返回指定用户发起的运行|
|`cursor`|string|上一页响应的 `page.nextCursor`，不透明游标，首次请求不传|
|`limit`|integer|每页数量，默认 `20`，最大 `100`；小于等于 `0` 使用默认值，超过 `100` 按 `100` 处理|



列表项 `data[]` 字段：



|字段|类型|说明|
|---|---|---|
|`id`|UUID|Test Run ID|
|`projectId`|UUID|项目 ID|
|`repositoryId`|UUID|项目仓库绑定 ID|
|`testsetIds`|UUID\[\]|本次运行使用的 Testset 快照 ID 列表|
|`taskId`|UUID/null|关联 Task；手动按 ref 发起时为 `null`|
|`ref`|string/null|请求 ref；Task 运行未直接提交 ref 时返回解析后的执行 ref|
|`status`|enum|`QUEUED`、`RUNNING`、`PASSED`、`FAILED`、`CANCELLED`|
|`createdBy`|UUID|发起用户 ID|
|`createdAt`|RFC 3339 string|创建时间，UTC|
|`startedAt`|RFC 3339 string/null|执行开始时间；尚未开始时为 `null`|
|`finishedAt`|RFC 3339 string/null|执行结束时间；未进入终态时为 `null`|



### 38\.3 Dry Run 列表



请求：



```HTTP
GET /api/v1/projects/{projectId}/dry-runs
```



可选查询参数：



|参数|类型|说明|
|---|---|---|
|`repositoryId`|UUID|仅返回指定项目仓库的 Dry Run|
|`taskId`|UUID|仅返回指定 Task 关联的 Dry Run|
|`status`|string|状态筛选，可传逗号分隔值：`QUEUED,RUNNING,PASSED,FAILED,CONFLICT,CANCELLED`|
|`targetBranch`|string|仅返回指定目标分支的 Dry Run；服务端会去除首尾空格|
|`createdByUserId`|UUID|仅返回指定用户发起的 Dry Run|
|`cursor`|string|上一页响应的 `page.nextCursor`，不透明游标，首次请求不传|
|`limit`|integer|每页数量，默认 `20`，最大 `100`；小于等于 `0` 使用默认值，超过 `100` 按 `100` 处理|



列表项 `data[]` 字段：



|字段|类型|说明|
|---|---|---|
|`id`|UUID|Dry Run ID|
|`projectId`|UUID|项目 ID|
|`repositoryId`|UUID|项目仓库绑定 ID|
|`sourceRef`|string|本次预演的源分支或源提交引用|
|`targetBranch`|string|本次预演的目标分支|
|`taskId`|UUID/null|关联 Task；手动 Dry Run 未关联 Task 时为 `null`|
|`status`|enum|`QUEUED`、`RUNNING`、`PASSED`、`FAILED`、`CONFLICT`、`CANCELLED`|
|`createdBy`|UUID|发起用户 ID|
|`createdAt`|RFC 3339 string|创建时间，UTC|
|`startedAt`|RFC 3339 string/null|执行开始时间；尚未开始时为 `null`|
|`finishedAt`|RFC 3339 string/null|执行结束时间；未进入终态时为 `null`|



`CONFLICT` 仅表示服务端已持久化的冲突状态；冲突详情和测试摘要仍须通过 `GET /api/v1/projects/{projectId}/dry-runs/{dryRunId}/report` 获取。前端不得仅根据列表状态推断 MR 已创建或已通过门禁。



### 38\.4 分页响应与排序



两个接口均返回统一的 `ApiPageResponse`：



```JSON
{
  "data": [
    {
      "id": "test-run-uuid",
      "projectId": "project-uuid",
      "repositoryId": "repository-binding-uuid",
      "testsetIds": ["testset-uuid"],
      "taskId": "task-uuid",
      "ref": "feat/login-api",
      "status": "RUNNING",
      "createdBy": "user-uuid",
      "createdAt": "2026-08-19T08:00:00Z",
      "startedAt": null,
      "finishedAt": null
    }
  ],
  "page": {
    "nextCursor": "opaque-cursor",
    "hasMore": true
  },
  "requestId": "req_uuid"
}
```



结果按 `createdAt DESC, id DESC` 排序。还有下一页时，前端将本次响应的 `page.nextCursor` 原样作为下一次请求的 `cursor`；`hasMore=false` 且 `nextCursor=null` 表示已到末页。游标由服务端生成，前端不得解码、拼接或根据 ID/时间自行构造。



列表接口不要求 `Idempotency-Key`。收到 `test-run.updated` 或 `dry-run.updated` SSE 事件后，前端应重新请求当前列表（必要时同时刷新当前详情），不要使用事件 payload 在本地拼接或覆盖完整列表记录。



状态值、筛选值或游标格式不合法时分别返回 `400 INVALID_STATUS_FILTER` 或 `400 INVALID_CURSOR`；非项目成员按通用权限错误处理。

### 38.5 Test Run 详情

`GET /api/v1/projects/{projectId}/test-runs/{testRunId}` 返回单条运行的完整生命周期与结果摘要。

响应 `data` 字段：

|字段|类型|说明|
|---|---|---|
|`id`|UUID|Test Run ID|
|`projectId`|UUID|项目 ID|
|`repositoryId`|UUID|项目仓库绑定 ID|
|`ref`|string/null|请求 ref；Task 运行未直接提交 ref 时返回解析后的执行 ref|
|`testsetIds`|UUID\[\]|本次运行使用的 Testset 快照 ID 列表|
|`status`|enum|`QUEUED`、`RUNNING`、`PASSED`、`FAILED`、`CANCELLED`|
|`summary`|object/null|用例与结果摘要；含 `status`、resolved 提交 SHA 与 `results` 数组|
|`createdBy`|UUID|发起用户 ID|
|`createdAt`|RFC 3339 string|创建时间，UTC|
|`startedAt`|RFC 3339 string/null|执行开始时间（claim 时写入）；QUEUED 时为 `null`|
|`finishedAt`|RFC 3339 string/null|执行结束时间（进入终态时写入）；非终态时为 `null`|
|`updatedAt`|RFC 3339 string/null|最后更新时间，由数据库在行变更时维护|

`summary.results[]` 与列表接口的用例摘要一致，含 `testsetId`、`status`、`exitCode`、`durationMs`、`failureCode`、`message`；`summary.failureCode` 与 `summary.message` 为执行失败时的稳定错误码与脱敏描述。

## 39. `TASK_STATUS` 卡片结构化仓库映射

`TASK_STATUS` 消息的 `content` 新增以下字段，用于让客户端明确展示 AI 当前操作的真实项目仓库：

|字段|类型|说明|
|---|---|---|
|`repositoryMappings`|array|当前 Task Workspace 已挂载的仓库映射，可为空数组；按 `workspacePath` 稳定排序|
|`repositoryMappings[].workspacePath`|string|Workspace 内一级相对目录，例如 `repo-2`；不是宿主机绝对路径|
|`repositoryMappings[].repositoryId`|UUID|项目仓库绑定 ID（`project_repositories.id`），不是 GitHub 数字仓库 ID|
|`repositoryMappings[].name`|string/null|仓库短名称；元数据缺失时为 `null`|
|`repositoryMappings[].fullName`|string/null|Git 提供方 `owner/name`；元数据缺失时为 `null`|
|`repositoryMappings[].provider`|string/null|Git 提供方，例如 `GITHUB`|
|`repositoryMappings[].baseRef`|string/null|当前 Task 在该仓库固定的基线分支|
|`repositoryMappings[].sourceBranch`|string/null|当前 Task 在该仓库的源分支|
|`currentRepositoryPaths`|string[]|当前 TaskStep、Diff 批次、Dry Run 或 MR 实际涉及的 Workspace 路径，可为空数组|

示例：

```json
{
  "type": "TASK_STATUS",
  "content": {
    "taskId": "task-uuid",
    "status": "RUNNING",
    "phase": "CODING",
    "currentStepId": "step-uuid",
    "repositoryMappings": [
      {
        "workspacePath": "repo-2",
        "repositoryId": "project-repository-uuid",
        "name": "testtesttest",
        "fullName": "Choco-emmm/testtesttest",
        "provider": "GITHUB",
        "baseRef": "main",
        "sourceBranch": "feat/task-uuid"
      }
    ],
    "currentRepositoryPaths": ["repo-2"]
  }
}
```

语义约束：

- `repositoryMappings` 表示当前 Task Workspace 的完整仓库映射；
- `currentRepositoryPaths` 表示当前卡片所对应的实际步骤或交付事实涉及的仓库，不得默认取第一个仓库；
- Planner 尚未创建 Workspace 或当前步骤没有仓库范围时返回空数组，不代表任务失败；
- 仓库被软解绑、归档或远程元数据缺失时，仍尽量保留 `repositoryId` 和 `workspacePath`，名称字段可为 `null`；
- 客户端应使用 `currentRepositoryPaths` 查找映射并优先展示 `fullName`，再回退到 `name` 或 `repositoryId`；
- 客户端不得根据 Planner 文本、`repo-1/repo-2` 路径或项目默认仓库自行猜测当前仓库；
- 实时消息、历史消息、SSE/WebSocket 载荷使用同一字段语义；
- 字段不得包含宿主机绝对路径、Token、凭据、命令输出或完整文件列表。

---

## 39. 手动发起任务的群聊提示（2026-08-24）

手动发起任务（`POST /api/v1/projects/{projectId}/tasks`，非 @Agent/消息触发）后，服务端以**编排助手 Agent** 身份在需求群插入一句 TEXT 文本消息，提示发起者任务已开始。**不新增群聊消息类型**，复用既有 `TEXT` 类型与 `mentions` 结构。

### 39.1 消息内容

```json
{
  "type": "TEXT",
  "content": { "text": "@发起者显示名 您创建的任务已开始" },
  "mentions": [
    { "type": "USER", "id": "发起者用户ID" }
  ],
  "clientMessageId": "task-started-<taskId>"
}
```

- 发送者为团队**编排助手** Agent（`senderType=AGENT`，名称"编排助手"），团队查不到编排助手时跳过插入并记录日志，不阻断任务创建。
- 消息文本中的 `@发起者显示名` 取自用户 `displayName`；`mentions` 携带发起者，前端据此高亮 @ 提及。
- `clientMessageId = "task-started-<taskId>"` 固定幂等：同一任务重复触发只插入一次。
- 触发条件：任务 `triggerMessageId` 为空（用户手动发起）。@Agent 自动触发与显式消息触发（`triggerMessageId` 非空）不重复插入，避免与 @Agent 消息本身重复提示。

### 39.2 触发时机与失败语义

- 任务创建事务提交后异步执行（`TaskStartedNoticeListener`，`@Async` + `AFTER_COMMIT`），不占任务创建事务与群行锁。
- 消息插入失败只记日志，不影响任务创建与后续编排。
- 前端通过既有 `message.created` SSE 实时收到该消息，无需额外轮询。

---

## 40. 任务启动失败错误码修复：GIT_BRANCH_NOT_FOUND（2026-08-24）

任务启动阶段（Sandbox 会话准备、Git Store 同步前）解析基线分支时，若仓库在 GitHub 上不存在指定分支，服务端返回稳定的 `GIT_BRANCH_NOT_FOUND` 错误，**不再降级为泛化的 `FAILED_INFRASTRUCTURE`**。

### 40.1 错误码与用户可读信息

| 字段 | 值 |
|---|---|
| `failureCode` | `GIT_BRANCH_NOT_FOUND` |
| `failureReason` | `仓库 CloudPlayerBaby/test01 不存在基线分支 develop，请在项目仓库配置中选择真实存在的分支后重试` |
| `failureRetryable` | `true` |
| HTTP 语义 | 启动失败落 Task `FAILED`，`TaskStatusReason.code = STARTUP_FAILED` |

- `GIT_BRANCH_NOT_FOUND` 已加入 `ExecutionContentSanitizer` 公开白名单：`stableInfrastructureCode` / `publicFailureCode` / `userFailureRetryable` / `userFailureDescription` 均识别该码，未定义内部码不会被误映射。
- 启动失败卡片（TASK_STATUS `status=FAILED`）与任务详情 `statusReason` 均携带仓库名与基线分支名；`summary` 只含脱敏可读文案，不回显 GitHub 地址、Token 或异常堆栈。

### 40.2 前端消费

任务详情页在 `statusReason.failureCode === "GIT_BRANCH_NOT_FOUND"` 时展示：

```text
任务无法启动
仓库：CloudPlayerBaby/test01
基线分支：develop
原因：仓库 CloudPlayerBaby/test01 不存在基线分支 develop，请在项目仓库配置中选择真实存在的分支后重试
请修改基线分支后重新发起任务。
[重新发起任务]
```

- 「重新发起任务」跳回需求群（`/app/projects/{projectId}/req-chat/{groupId}`），由用户在发起弹窗中选择真实存在的基线分支后重新创建任务。
- 仓库名与基线分支名优先取自任务详情 `repositories[]`（`fullName` / `baseRef`）；数据缺失时回退展示 `statusReason.summary` 中的文案。

### 40.3 触发路径与预检时机

- 校验发生在任务异步编排启动的 Sandbox 会话准备阶段（`SandboxSessionManager.acquire` → GitHub 分支解析），**早于任何 TaskRun 创建与 Agent 执行**；失败直接落 Task `FAILED`，不会先进入执行再失败。
- 结构化错误详情（仓库 `fullName` 与 `branch`）随 `ApiException.details` 传递，仅用于服务端拼装用户可读文案，不进入 SSE 或公开日志。
