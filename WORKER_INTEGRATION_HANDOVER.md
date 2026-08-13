# 后端2 Worker 接入交接说明

> 作者：后端1（Agent Orchestrator）　·　日期：2026-08-13
> 目的：说明「反馈 Agent 的 Local* 端口 → 后端2 sandbox-worker HTTP API」已完成的部分、如何启用，以及**还缺什么**才能跑通端到端。

---

## 1. 目标

把主后端 `qg.qgent.orchestration.tool` 下 4 个 `Local*` 端口从「主后端本地文件系统/进程实现」切换为「通过 HTTP 调用后端2 `sandbox-worker`（`/internal/v1/*`）」，并用开关控制，默认关闭时行为完全不变。

## 2. 已完成内容

新增包 `qg.qgent.orchestration.worker`（主后端内）：

| 组件 | 职责 |
|---|---|
| `SandboxWorkerClient` | RestClient 封装，覆盖 workspace provision/get/delete、git/status、git/diff、sandbox create/get/destroy、tool-execution submit/get/logs；保留 Worker 业务错误码 |
| `SandboxSessionManager` | 每 Task 一把沙箱：幂等 provision workspace → create sandbox → 终态 destroy（Workspace 保留） |
| `SandboxSession` | 会话值对象：sandboxId + `workspacePath → repositoryId` 映射 |
| `WorkerWorkspaceCodeAccess` | `file.list`（递归）+`file.read`（分页重组）+`file.search`（rg 解析） |
| `WorkerWorkspaceCodeWriter` | 先 `file.read` 取 sha256 → `file.write(expectedHash)` 乐观并发写 |
| `WorkerSandboxExecutionPort` | `process.exec` 提交 → 轮询终态 → 拉 STDOUT/STDERR 日志 |
| `WorkerWorkspaceDiffAccess` | workspace 级 `git/diff` 逐仓聚合 |
| 15 个镜像 DTO | 对齐 openapi 的 camelCase 形状 |

改动：`TaskOrchestrator`（入口 acquire/release 沙箱会话 + 抽出 `runLoop`）、4 个 `Local*.java`（加 `@ConditionalOnProperty` 停用开关）、`application.yaml`（`app.worker.*`）、`TaskService` + `TaskCreatedEvent` + `TaskExecutionListener`（任务创建事务提交后异步触发编排）。

## 3. 如何启用

`application.yaml` / 环境变量：

```yaml
app:
  worker:
    base-url: ${SANDBOX_WORKER_URL:http://localhost:8091}
    enabled: ${SANDBOX_WORKER_ENABLED:false}   # true 时改走 Worker HTTP API
    image-profile: ${SANDBOX_IMAGE_PROFILE:java-node}
    poll-interval: ${SANDBOX_POLL_INTERVAL:250ms}
    poll-timeout: ${SANDBOX_POLL_TIMEOUT:15m}
```

- `enabled=false`（默认）：4 个 `Local*` 端口照常生效，编排行为与之前完全一致。
- `enabled=true`：4 个 `Worker*` 端口生效（Bean 选择已用 `WorkerPortSelectionTest` 验证）。

## 4. 端口 → Worker 契约映射

| 端口方法 | worker 调用 | 形状翻译 |
|---|---|---|
| `listFiles` | 每仓递归 `file.list` | 扁平化 + 前缀 `workspacePath/` |
| `readFile` | `file.read` 分页 | `lines[]` join `\n` 重组，64KB 上限 |
| `searchCode` | 每仓 `file.search`(rg) | 解析 `path:line:content` 提取去重路径 |
| `writeFile` | `file.read`→`file.write` | expectedHash 乐观并发，哈希冲突→工具级失败回灌 LLM |
| `execute` | `process.exec`→轮询→logs | exitCode 非空=真实执行，否则基础设施失败 |
| `diff` | 每仓 `git/diff` | 聚合 patch + `===== workspacePath =====` 分隔 |

provision 字段映射：`repositoryId=project_repository_id`、`baseRef=base_commit`（缺失回退 defaultBranch）、`sourceBranch=source_branch`、`workspacePath=workspace_path`、`workspaceStorageKey=workspaces/{id}`（与后端2 `storageKey` 约定一致，已核对）。

## 5. 已处理的边界

- **baseRef 缺失**：Task 创建时不填 `baseRef` 会导致 `workspace_repositories.base_commit` 为 NULL，而 worker 要求 `baseRef` 非空。已处理：`SandboxSessionManager` 回退到项目仓库绑定的 `defaultBranch`；两者都缺失才明确失败（不伪造基线）。
- **Bean 装配**：`@ConditionalOnProperty` + `@Primary`，`enabled=false` 时 `Local*` 压制 `Disabled*`，`enabled=true` 时 `Worker*` 压制 `Disabled*`。
- **错误映射**：worker 连接失败→`SANDBOX_WORKER_UNAVAILABLE`(502)，非 2xx 响应透传 worker 业务错误码（如 `FILE_HASH_MISMATCH`）。

## 6. 还缺什么（阻塞端到端联调）

> 都不是后端2/3 的缺口——后端2 的 worker 契约是完整的、与映射一致。

1. **群聊 @agent → 自动创建 Task**（后端4 的「聊天消息→任务转换」，尚未实现）
   当前 `TaskService.create` 是前端手动传 `requirement/title/repositoryIds` 的 API，还没有「从聊天消息自动提炼需求建 Task」的代码。已完成的编排触发（Task 创建 → `orchestrate()`）依赖这半边：只要有人调 `TaskService.create`，编排就会自动跑起来；但「@agent 自动建 Task」要等后端4。

2. **在线基础设施**（部署/联调项，全员）
   MySQL、Redis、可运行的 `sandbox-worker`（`runtime=fake` 即可联调）、LLM key（deepseek）、GitHub App 配置。本机当前无法自证。

> 编排触发入口（后端1 半边）**已完成**：`TaskService.create` 在事务提交后发 `TaskCreatedEvent`，`TaskExecutionListener` 用 `@Async @TransactionalEventListener(AFTER_COMMIT)` 异步调 `orchestrate()`。

## 7. 已知限制与风险

- **`file.list` 无递归接口**：`listFiles` 需逐目录递归，大仓库 HTTP 调用较多（O(目录数)）。如后端2 后续补递归参数可消除。
- **`searchCode` 解析 rg 输出**：按 `path:line:...` 切分，路径含冒号时会有误差（沙箱 Linux 路径一般不出现）。
- **多仓库 `execute`**：`process.exec` 只带单仓库 cwd（单仓用唯一仓库，多仓回退 workspace 根）。
- **`readFile` 重组**：按行 join 会丢失末尾换行的精确性，仅影响喂给 LLM 的上下文文本，不影响正确性。
- **沙箱会话是进程内 Map**：当前编排同步单线程、单 workspace 单写者可成立；若未来并行执行，需升级为持久状态/锁（AGENTS.md 已要求）。

## 8. 验证情况

- 编译：`mvn test-compile` 全量通过。
- 测试：36 个测试全绿（客户端契约 7、配置装配 1、会话生命周期 7、三个工具端口形状翻译 7、diff 聚合 1、Bean 开关选择 2、编排回归 11），全部 Mock/MockRestServiceServer，不访问真实 Worker/DB/LLM。
- `git diff --check`：干净。
- **未验证**：完整 `@SpringBootTest` 上下文启动（需 MySQL/Redis）与真实 Worker 联调（需在线基础设施）。

## 9. 下一步责任

- 后端4：补「群聊 @agent → 自动创建 Task」的触发（聊天消息→需求提炼→调 `TaskService.create`）。
- 后端2：可选——`file.list` 是否补递归参数（非阻塞）。
- 全员：起基础设施后做一次 `enabled=true` 的完整 Task 联调。
