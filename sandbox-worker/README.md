# Qgents Sandbox Worker

本模块承载 Workspace Manager 与 Sandbox Manager 的内部执行面实现。

- Workspace Manager：从共享 bare Git Store 创建 linked worktree，并提供受控 `status/diff/commit/push`。
- Sandbox Manager：按 `repositoryIds` 读取 Workspace 元数据，逐仓 bind mount，只提供文件、目录和进程工具（`file.read`、`file.list`、`file.search`、`file.write`、`file.patch`、`directory.create`、`process.exec`）。
- Agent Sandbox 镜像不安装 Git；Worker 镜像保留 Git CLI。

## 文件工具

- `file.write`：新建或整文件替换，旧内容哈希校验 + 原子替换。
- `file.patch`：对已有 UTF-8 文本文件精确应用统一 Diff 局部修改；`expectedHash` 校验通过且补丁上下文与声明行号完全匹配才写回，禁止模糊匹配/自动偏移/冲突覆盖，失败保证原文件不变。只处理工作树文件，不产生 Git Commit、Diff 或 MR。
- `directory.create`：在 Repository 内递归、幂等创建目录；目标目录已存在时返回 `created=false`，不自动创建 `.gitkeep`。

完整设计和使用说明见 [Workspace 与 Sandbox Manager](../docs/workspace-sandbox-manager.md)，精确契约见 [OpenAPI](../contracts/sandbox-worker-openapi.yaml)。

## 验证

```bash
mvn -f sandbox-worker/pom.xml test
mvn -f sandbox-worker/pom.xml package
```

关键配置：`SANDBOX_GIT_STORE_ROOT`、`SANDBOX_WORKSPACE_LOCAL_ROOT`、`SANDBOX_WORKSPACE_DOCKER_HOST_ROOT`、`SANDBOX_WORKSPACE_METADATA_ROOT`、`SANDBOX_RUNTIME`、`SANDBOX_DEV_TOOLS_IMAGE`、`SANDBOX_DEVELOPER_HOME_SIZE`。开发用户 HOME 默认以 `8g` tmpfs 挂载；rootfs 其他位置仍保持只读。

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
  -v qgents_worker_workspaces:/var/lib/qgents/workspaces `
  -v qgents_worker_metadata:/var/lib/qgents/workspace-metadata `
  -v qgents_worker_git_store:/var/lib/qgents/git-store `
  -e SANDBOX_RUNTIME=docker `
  -e SANDBOX_DEV_TOOLS_IMAGE=qgents/sandbox-dev-tools:0.2.0 `
  -e SANDBOX_IMAGE_PROFILES=dev-tools `
  -e SANDBOX_DOCKER_HOST=unix:///var/run/docker.sock `
  -e SANDBOX_WORKSPACE_LOCAL_ROOT=/var/lib/qgents/workspaces `
  -e SANDBOX_WORKSPACE_DOCKER_HOST_ROOT=/var/lib/qgents/workspaces `
  -e SANDBOX_WORKSPACE_METADATA_ROOT=/var/lib/qgents/workspace-metadata `
  -e SANDBOX_GIT_STORE_ROOT=/var/lib/qgents/git-store `
  qgents/sandbox-worker:0.1.0
```
