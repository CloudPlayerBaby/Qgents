# Qgents 沙箱工作节点

该模块是独立的执行面服务，向控制层提供沙箱生命周期和命令执行接口。控制层负责业务编排，Worker 负责容器安全边界、执行超时和兜底回收。

## 当前能力

- 创建、查询、续租和幂等销毁沙箱。
- 空闲期限与最大生命周期双重回收。
- 单沙箱单执行约束，以及异步执行、取消、超时和增量日志。
- `fake` 运行时，用于不依赖 Docker 的开发和单元测试。
- `docker` 运行时，通过 Docker Engine API 创建和管理真实容器。
- Worker 重启后根据容器标签重新认领运行中的沙箱，并清理无效孤儿容器。
- 命令超时后重启容器，终止容器内残留进程；挂载的 Workspace 不受影响。
- stdout、stderr 分别设置内存上限，超过部分会被截断。
- 工具 Execution 和日志写入独立 MySQL 表，Worker 重启后保留查询与幂等依据。
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

`file.write` 必须传入最近一次 `file.read` 返回的 `sha256`。文件已经变化时返回 `FILE_HASH_MISMATCH`，Agent 需要重新读取后再决定如何修改。

本地开发默认使用 `outbound`，对应 Docker `bridge` 网络。设置 `SANDBOX_NETWORK_POLICY=none` 可以禁用网络；客户端和 Agent 无权选择 Docker 网络。

## 安全边界

- 沙箱使用只读根文件系统，唯一持久写入位置为当前 Workspace。
- 默认关闭网络、删除全部 Linux capabilities、启用 `no-new-privileges`，并限制内存、CPU 与进程数。
- 接口不接受 Docker Socket、Git 凭证、宿主机路径或任意挂载参数。
- Docker Socket 只允许 Worker 使用，绝不能挂进 Agent 沙箱。
- 当前内部服务认证尚未定案，服务只能放在可信内部网络，不能直接暴露到公网。
- Workspace 不是容器卷，销毁沙箱不会删除 Workspace。

## 验证

```powershell
mvn -f sandbox-worker/pom.xml clean test
```

完整 Docker 验证还需要本机或测试服务器已启动 Docker daemon，并准备一个符合 storage key 的 Workspace 目录。
