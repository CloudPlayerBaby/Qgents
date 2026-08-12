# Qgents 沙箱工作节点

该模块是独立的执行面服务，向控制层提供沙箱生命周期和命令执行接口。控制层负责业务编排，Worker 负责容器安全边界、执行超时和兜底回收。

同一服务内还包含 Workspace Manager。它把控制层的 Workspace 描述落实为宿主机上的持久多仓库独立副本；Sandbox 只挂载并使用已经准备完成的 Workspace。

## 当前能力

- 创建、查询、续租和销毁 Sandbox。
- 空闲期限与最大生命周期双重回收。
- 结构化工具统一异步执行，支持取消、超时和增量日志，并把状态持久化到 MySQL。
- `fake` 运行时，用于不依赖 Docker 的开发和单元测试。
- `docker` 运行时，通过 Docker Engine API 创建和管理真实容器。
- Worker 重启后根据容器标签重新认领运行中的沙箱，并清理无效孤儿容器。
- 命令超时后重启容器，终止容器内残留进程；挂载的 Workspace 不受影响。
- stdout、stderr 分别设置内存上限，超过部分会被截断。
- 工具 Execution 和日志写入独立 MySQL 表，Worker 重启后仍可查询历史结果。
- 统一工具入口支持文件读取、列举、搜索、哈希保护写入、进程执行和基础 Git 操作。

## Java + Node 镜像

镜像文件位于 `../sandbox-images/java-node/Dockerfile`，当前包含：

- Eclipse Temurin JDK 21
- Node.js 22 与 Corepack
- Maven 3.9.11
- Git、Bash、Python 3、ripgrep、jq、patch 和常用构建工具
- 固定的非 root 用户 `developer`，用户与组编号均为 `10001`

构建命令：

```bash
docker build -t qgents/sandbox-java-node:0.1.0 sandbox-images/java-node
```

这个镜像用于 Java 与 Node 项目，不追求覆盖所有语言。以后应按语言族增加独立镜像配置，不把数据库、Docker daemon、浏览器等服务继续塞入同一个镜像。

## Docker 部署关系

Worker 可以运行在容器内，但它创建的沙箱仍由宿主机 Docker daemon 管理。Workspace 保存在宿主机，并同时挂载给 Worker 和沙箱：

```text
宿主机 /srv/qgents/workspaces
  ├─ 挂载到 Worker /var/lib/qgents/workspaces
  └─ 由 Docker daemon 挂载到 Sandbox /workspace
```

因此存在两个根目录配置：

- `SANDBOX_WORKSPACE_LOCAL_ROOT`：Worker 进程实际可见的路径，用于存在性和越界检查。
- `SANDBOX_WORKSPACE_DOCKER_HOST_ROOT`：Docker daemon 所在宿主机的真实路径，用于创建 bind mount。

两者在 Worker 直接运行于宿主机时可以配置成相同路径。控制层只传 `workspaceStorageKey`，不得传宿主机绝对路径或任意挂载参数。

## 运行配置

默认使用安全的模拟运行时：

```powershell
mvn -f sandbox-worker/pom.xml spring-boot:run
```

启用 Docker 运行时需要至少设置：

```text
SANDBOX_RUNTIME=docker
SANDBOX_DOCKER_HOST=unix:///var/run/docker.sock
SANDBOX_WORKER_ID=worker-01
SANDBOX_WORKSPACE_LOCAL_ROOT=/var/lib/qgents/workspaces
SANDBOX_WORKSPACE_DOCKER_HOST_ROOT=/srv/qgents/workspaces
SANDBOX_JAVA_NODE_IMAGE=qgents/sandbox-java-node:0.1.0
SANDBOX_DB_URL=jdbc:mysql://mysql:3306/qgents_sandbox_worker
SANDBOX_DB_USERNAME=qgents
SANDBOX_DB_PASSWORD=请通过部署环境注入
SANDBOX_NETWORK_POLICY=outbound
```

默认监听端口为 `8091`，可以通过 `SANDBOX_WORKER_PORT` 修改。其他资源上限见 `src/main/resources/application.yaml`。
`SANDBOX_WORKER_ID` 必须在同一个 Docker daemon 范围内唯一，并在 Worker 重启后保持不变；Worker 只会认领带有自身编号的容器。
首次运行前需要执行 `src/main/resources/db/sandbox_worker_schema.sql` 创建 Worker 独立表。

## 工具执行

统一入口为 `POST /internal/v1/sandboxes/{sandboxId}/tool-executions`。当前工具包括：

- `process.exec`
- `file.read`、`file.list`、`file.search`、`file.write`
- `git.status`、`git.diff`、`git.log`、`git.add`、`git.commit`、`git.head`、`git.push`

`git.head` 返回当前分支、HEAD 提交和工作树是否干净。`git.push` 必须提供预期 HEAD 提交、`origin` 远端和当前检出的目标分支；Worker 会从当前 `HEAD` 推送，并在推送后通过远端引用核验提交 SHA。

创建沙箱时，控制层必须通过 `repositories` 提供仓库编号到 Workspace 相对目录的映射。后续工具请求只提交 `repositoryId`，不能自行指定容器工作目录。

创建接口返回 `202` 和 `QUEUED` 状态。网络超时后，调用方应使用原 `executionId` 查询执行状态，不应重复提交；重复的 `executionId` 返回 409。响应中的 `ownerWorkerId` 用于控制层把取消请求路由回实际执行 Worker，错误路由会返回 `EXECUTION_OWNED_BY_OTHER_WORKER`。`process.exec` 可以不指定仓库并在 `/workspace` 执行，其他当前工具必须指定 `repositoryId`。

`file.write` 必须传入最近一次 `file.read` 返回的 `sha256`。文件已经变化时返回 `FILE_HASH_MISMATCH`，Agent 需要重新读取后再决定如何修改。

本地开发默认使用 `outbound`，对应 Docker `bridge` 网络。设置 `SANDBOX_NETWORK_POLICY=none` 可以禁用网络；客户端和 Agent 无权选择 Docker 网络。

## Workspace Manager

Workspace Manager 提供三个内部接口：

```text
PUT    /internal/v1/workspaces/{workspaceId}
GET    /internal/v1/workspaces/{workspaceId}
DELETE /internal/v1/workspaces/{workspaceId}
```

`PUT` 根据 `workspaceId` 固定创建 `workspaces/{workspaceId}`，并根据每个 `repositoryId` 从 `${SANDBOX_GIT_STORE_ROOT}/{repositoryId}.git` 执行 `git clone --no-hardlinks`。每个 Workspace 都拥有独立 `.git`，Sandbox 无法修改共享 Git Store。调用方只能提交基线引用、功能分支和 Workspace 内一级目录名称，不能提交 Git Store 或宿主机绝对路径。

需要配置：

```text
SANDBOX_WORKSPACE_METADATA_ROOT=/var/lib/qgents/workspace-metadata
SANDBOX_GIT_STORE_ROOT=/var/lib/qgents/git-store
```

共享 Git Store 由 Git 模块同步和维护，只对 Worker 可见。Workspace Manager 不持有 GitHub App 私钥，不签发 Token，也不执行 push；删除 Workspace 时只移除独立仓库副本和自身元数据，不删除共享裸仓库。Workspace 仍被 Sandbox 使用时，删除接口返回 409。

## 安全边界

- 沙箱使用只读根文件系统，唯一持久写入位置为当前 Workspace。
- 当前默认允许通过 Docker bridge 出站访问网络；设置 `SANDBOX_NETWORK_POLICY=none` 可关闭网络。所有 Sandbox 都会删除 Linux capabilities、启用 `no-new-privileges`，并限制内存、CPU 与进程数。
- `SANDBOX_WORKER_ID` 必须在共享同一执行数据库的所有 Worker 中全局唯一且重启后保持稳定。
- 当前 Workspace 删除保护要求使用同一 Workspace 根目录的 Worker 同时共享 metadata root 和同一个 Docker daemon；暂不支持多个 Docker daemon 共同挂载并管理同一批 Workspace。
- 同一 Sandbox 的 Docker 命令会串行执行，因为取消或超时需要重启容器；文件读取、写入和搜索等宿主机受控工具不受此限制。
- 接口不接受 Docker Socket、Git 凭证、宿主机路径或任意挂载参数。
- Docker Socket 只允许 Worker 使用，绝不能挂进 Agent 沙箱。
- 当前内部服务认证尚未定案，服务只能放在可信内部网络，不能直接暴露到公网。
- Workspace 不是容器卷，销毁沙箱不会删除 Workspace。

## 验证

```powershell
mvn -f sandbox-worker/pom.xml clean test
```

完整 Docker 验证还需要本机或测试服务器已启动 Docker daemon，并准备一个符合 storage key 的 Workspace 目录。
