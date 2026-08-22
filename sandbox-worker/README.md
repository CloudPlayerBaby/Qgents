# Qgents Sandbox Worker

本模块承载 Workspace Manager 与 Sandbox Manager 的内部执行面实现。

- Workspace Manager：从共享 bare Git Store 创建 linked worktree，并提供受控 `status/diff/commit/push`。
- Sandbox Manager：按 `repositoryIds` 读取 Workspace 元数据，逐仓 bind mount，只提供文件、目录和固定开发命令工具（`file.read`、`file.list`、`file.search`、`file.write`、`file.patch`、`directory.create`、`development.run`）。
- Agent Sandbox 镜像不安装 Git；Worker 镜像保留 Git CLI。

## 文件工具

- `file.write`：新建或整文件替换，旧内容哈希校验 + 原子替换。
- `file.patch`：对已有 UTF-8 文本文件精确应用统一 Diff 局部修改；`expectedHash` 校验通过且补丁上下文与声明行号完全匹配才写回，禁止模糊匹配/自动偏移/冲突覆盖，失败保证原文件不变。只处理工作树文件，不产生 Git Commit、Diff 或 MR。
- `directory.create`：在 Repository 内递归、幂等创建目录；目标目录已存在时返回 `created=false`，不自动创建 `.gitkeep`。

完整设计和使用说明见 [Workspace 与 Sandbox Manager](../docs/workspace-sandbox-manager.md)，精确契约见 [OpenAPI](../contracts/sandbox-worker-openapi.yaml)。

## 数据库升级

全新 Worker 数据库执行 `src/main/resources/db/sandbox_worker_schema.sql`。现有数据库不自动执行迁移；
部署包含 `failureCode` 的 Worker 前，运维必须先执行
`src/main/resources/db/migration/V20260819_01__add_tool_execution_failure_code.sql`，确认成功后再启动新版本。

## 验证

```bash
mvn -f sandbox-worker/pom.xml test
mvn -f sandbox-worker/pom.xml package
```

关键配置：`SANDBOX_DB_URL`、`SANDBOX_DB_USERNAME`、`SANDBOX_DB_PASSWORD`、`SANDBOX_GIT_STORE_ROOT`、`SANDBOX_WORKSPACE_LOCAL_ROOT`、`SANDBOX_WORKSPACE_DOCKER_HOST_ROOT`、`SANDBOX_WORKSPACE_METADATA_ROOT`、`SANDBOX_RUNTIME`、`SANDBOX_DEV_TOOLS_IMAGE`、`SANDBOX_DEVELOPER_HOME_SIZE`、`SANDBOX_NETWORK_POLICY`。沙箱 rootfs 默认可写，开发用户 HOME 默认以 `8g` tmpfs 挂载；默认网络策略为 `outbound`，用于首次解析构建依赖，部署方可设为 `none` 显式禁网；Workspace 仍只逐仓库挂载授权目录。

## Worker 数据库初始化

Worker 的工具执行详情与完整日志存入独立 MySQL 库 `qgents_sandbox_worker`，表为
`tool_executions` 和 `tool_execution_logs`。它们不写入主后端的 `qgents` 库，也不依赖
`docker logs`。

部署管理员必须在首次启动 Worker 前创建数据库：

```bash
mysql --protocol=tcp -h <mysql-host> -u <admin-user> -p \
  < sandbox-worker/src/main/resources/db/sandbox_worker_database.sql
```

随后为 `SANDBOX_DB_USERNAME` 配置的应用账户授予该库所需权限。Worker 启动时通过
`spring.sql.init` 自动执行幂等脚本 `db/sandbox_worker_schema.sql`，因此会创建缺失的两张表；
应用账户不需要 `CREATE DATABASE` 全局权限。生产环境应把这两个 SQL 文件纳入部署发布步骤。

## 分步构建与运行

主后端：

```powershell
mvn -DskipTests package
```

Worker：

```powershell
mvn -f sandbox-worker/pom.xml clean package -DskipTests
```

开发工具镜像：

```powershell
docker build -t qgents/sandbox-dev-tools:0.2.0 sandbox-images/dev-tools
```

Worker 服务镜像：

```powershell
docker build -t qgents/sandbox-worker:0.1.0 sandbox-worker
```

Worker 本地容器模板（数据库、主后端地址和内部服务凭证由部署环境补充）：

```powershell
docker run --rm --name qgents-sandbox-worker `
  -p 8091:8091 `
  -v //var/run/docker.sock:/var/run/docker.sock `
  -v /srv/qgents/workspaces:/var/lib/qgents/workspaces `
  -v /srv/qgents/workspace-metadata:/var/lib/qgents/workspace-metadata `
  -v /srv/qgents/git-store:/var/lib/qgents/git-store `
  -e SANDBOX_RUNTIME=docker `
  -e SANDBOX_DB_URL='jdbc:mysql://<mysql-host>:3306/qgents_sandbox_worker?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC' `
  -e SANDBOX_DB_USERNAME='<worker-db-user>' `
  -e SANDBOX_DB_PASSWORD='<worker-db-password>' `
  -e SANDBOX_DEV_TOOLS_IMAGE=qgents/sandbox-dev-tools:0.2.0 `
  -e SANDBOX_IMAGE_PROFILES=dev-tools `
  -e SANDBOX_DOCKER_HOST=unix:///var/run/docker.sock `
  -e SANDBOX_WORKSPACE_LOCAL_ROOT=/var/lib/qgents/workspaces `
  -e SANDBOX_WORKSPACE_DOCKER_HOST_ROOT=/srv/qgents/workspaces `
  -e SANDBOX_WORKSPACE_METADATA_ROOT=/var/lib/qgents/workspace-metadata `
  -e SANDBOX_GIT_STORE_ROOT=/var/lib/qgents/git-store `
  qgents/sandbox-worker:0.1.0
```
