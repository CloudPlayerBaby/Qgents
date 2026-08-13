# Workspace Manager 与 Sandbox Manager

## 职责边界

Workspace Manager 管理持久代码现场和全部 Git 操作；Sandbox Manager 只管理临时容器、文件修改、构建和测试。

```text
受控远端 -> 共享 bare Git Store -> linked worktree -> 逐仓 bind -> Sandbox
                         ^               |
                         |               +-- 只修改工作文件/运行命令
                         +-- status/diff/commit/push 由 Workspace Manager 执行
```

Sandbox 镜像不安装 Git，也没有 Git 凭证。标准 linked worktree 根目录包含指向宿主 store 的 `.git` 文件；Docker 在逐仓 rw bind 之上，用 Worker 创建的空 marker 对容器内 `<repo>/.git` 增加嵌套只读 bind。因此 Agent 既看不到宿主 store 路径，也不能通过 `process.exec` 篡改该指针。共享 store 从不挂载进 Sandbox。

## 目录结构

```text
/var/lib/qgents/git-store/<repositoryId>.git
/var/lib/qgents/workspaces/<workspaceId>/backend/
/var/lib/qgents/workspace-metadata/<workspaceId>.json
```

同一 `sourceBranch` 不能同时被两个 linked worktree 使用。共享 store 操作由 repositoryId 级文件锁串行化。

## Workspace 接口

创建或幂等查询：

```http
PUT /internal/v1/workspaces/{workspaceId}

{
  "projectId": "11111111-1111-1111-1111-111111111111",
  "repositories": [{
    "repositoryId": "22222222-2222-2222-2222-222222222222",
    "baseRef": "main",
    "sourceBranch": "feat/login",
    "workspacePath": "backend"
  }]
}
```

服务从 `<git-store-root>/<repositoryId>.git` 解析真实基线，用 `git worktree add` 创建目录，不执行 clone。相同请求可重试；不同规格返回 `409 WORKSPACE_SPEC_CONFLICT`。

```http
GET    /internal/v1/workspaces/{workspaceId}
DELETE /internal/v1/workspaces/{workspaceId}
```

删除会执行 `worktree remove --force` 和 `worktree prune`，保留 bare store；有 Sandbox 使用时返回 `WORKSPACE_IN_USE`。

Git 状态和 Diff：

```http
GET  /internal/v1/workspaces/{workspaceId}/repositories/{repositoryId}/git/status
POST /internal/v1/workspaces/{workspaceId}/repositories/{repositoryId}/git/diff
```

`status` 返回 branch、真实 HEAD、clean 和结构化变更。`diff` 用临时 index 收集 tracked/untracked 文件，返回完整 binary patch 与 `sha256:` diffHash；超过 10 MiB 失败，不截断后继续审核。

Commit：

```http
POST /internal/v1/workspaces/{workspaceId}/repositories/{repositoryId}/git/commit

{
  "expectedHeadCommit": "<diff 返回的 SHA>",
  "expectedDiffHash": "sha256:<diff 返回的哈希>",
  "message": "feat(auth): add login endpoint"
}
```

服务重新计算快照并校验，然后内部执行 `git add -A` 与 `git commit`，不提供独立 `git.add` 接口。仍有 Sandbox 使用时拒绝 commit。

真实 `git add -A` 后会再次从真实暂存区生成完整 binary patch 并校验 diffHash，只有该 staged snapshot 与审查结果一致才 commit。

Push：

```http
POST /internal/v1/workspaces/{workspaceId}/repositories/{repositoryId}/git/push

{"expectedHeadCommit":"<commitSha>"}
```

调用方不能传 URL、remote、refspec 或凭证。Worker 只使用 bare store 已配置的 `origin` 和元数据中的 `sourceBranch`，推送后用 `ls-remote` 核验 SHA。当前实现不定义远端短期凭证获取方式；部署系统必须通过既有受控 Git 集成准备 origin 和认证环境。

## Sandbox 接口

```http
POST /internal/v1/sandboxes

{
  "sandboxId": "33333333-3333-3333-3333-333333333333",
  "taskRunId": "44444444-4444-4444-4444-444444444444",
  "workspaceStorageKey": "workspaces/55555555-5555-5555-5555-555555555555",
  "repositoryIds": ["22222222-2222-2222-2222-222222222222"],
  "imageProfile": "java-node"
}
```

调用方只提交 Orchestrator 已授权的 repositoryIds。Worker 从元数据解析 workspacePath，拒绝未登记仓库，并分别挂载到 `/workspace/<workspacePath>`，不会挂载整个 Workspace。

Sandbox 工具仅支持 `process.exec`、`file.read`、`file.list`、`file.search`、`file.write`；任何 `git.*` 返回 `TOOL_NOT_SUPPORTED`。

```http
GET    /internal/v1/sandboxes/{sandboxId}
POST   /internal/v1/sandboxes/{sandboxId}/lease/renew?ttlSeconds=600
DELETE /internal/v1/sandboxes/{sandboxId}
```

销毁 Sandbox 不删除 Workspace。

## 推荐调用顺序

1. 受控 Git 集成准备 bare store 和 origin。
2. Orchestrator 创建 Workspace。
3. 根据 TaskStep 仓库范围创建 Sandbox，传 repositoryIds。
4. Sandbox 修改文件并执行构建、测试。
5. 销毁 Sandbox。
6. Workspace Manager 生成 status/diff。
7. 用户接受 Diff 后调用 commit。
8. 需要远端结果时调用 push，再由 Git Provider 集成创建 MR。

## 配置、重试与错误

- `SANDBOX_GIT_STORE_ROOT`：共享 bare store 根目录。
- `SANDBOX_WORKSPACE_LOCAL_ROOT`：Worker 看到的 worktree 根目录。
- `SANDBOX_WORKSPACE_DOCKER_HOST_ROOT`：Docker daemon 宿主机看到的对应根目录。
- `SANDBOX_WORKSPACE_METADATA_ROOT`：Workspace 元数据和锁目录。
- `SANDBOX_JAVA_NODE_IMAGE`：不含 Git 的 Agent 镜像。

local root 与 Docker host root 必须表示同一份持久存储。`GIT_HEAD_MISMATCH` 或 `GIT_DIFF_MISMATCH` 后必须重新生成并审核 Diff。只有 push 返回 `verified=true` 才能描述为推送成功。Git stderr 不原样返回，避免远端地址或认证信息泄露。

Commit 当前没有可靠的跨请求幂等记录：Worker 没有已确认的 operationId/Task 身份持久化契约。调用方在响应丢失后必须先查询 HEAD/status 判断 Commit 是否已创建，不能盲目重放。后续应由主后端提供受认证的 operationId 与结果记录契约后再补齐。

## 归属与 Task 来源接缝

主后端已有 Project 业务数据和成员校验能力，但 Worker 没有业务库访问、调用身份认证以及 `headCommitSourceTaskId` 的已确认接口契约。因此 Orchestrator 必须在调用 Worker 前校验 Task、Workspace、Repository 与 Project 归属，并在 Commit 成功后把真实 SHA 及来源 Task 回填业务库。当前 Worker 仅按已登记 Workspace 元数据限制 repositoryId；不能把它描述为已经独立完成 Project 授权或 Task 来源记录。
