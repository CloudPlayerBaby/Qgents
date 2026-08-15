# Qgents Sandbox Worker

本模块承载 Workspace Manager 与 Sandbox Manager 的内部执行面实现。

- Workspace Manager：从共享 bare Git Store 创建 linked worktree，并提供受控 `status/diff/commit/push`。
- Sandbox Manager：按 `repositoryIds` 读取 Workspace 元数据，逐仓 bind mount，只提供文件和进程工具（`file.read`、`file.list`、`file.search`、`file.write`、`file.patch`、`process.exec`）。
- Agent Sandbox 镜像不安装 Git；Worker 镜像保留 Git CLI。

## 文件工具

- `file.write`：新建或整文件替换，旧内容哈希校验 + 原子替换。
- `file.patch`：对已有 UTF-8 文本文件精确应用统一 Diff 局部修改；`expectedHash` 校验通过且补丁上下文与声明行号完全匹配才写回，禁止模糊匹配/自动偏移/冲突覆盖，失败保证原文件不变。只处理工作树文件，不产生 Git Commit、Diff 或 MR。

完整设计和使用说明见 [Workspace 与 Sandbox Manager](../docs/workspace-sandbox-manager.md)，精确契约见 [OpenAPI](../contracts/sandbox-worker-openapi.yaml)。

## 验证

```bash
mvn -f sandbox-worker/pom.xml test
mvn -f sandbox-worker/pom.xml package
```

关键配置：`SANDBOX_GIT_STORE_ROOT`、`SANDBOX_WORKSPACE_LOCAL_ROOT`、`SANDBOX_WORKSPACE_DOCKER_HOST_ROOT`、`SANDBOX_WORKSPACE_METADATA_ROOT`、`SANDBOX_RUNTIME`、`SANDBOX_JAVA_NODE_IMAGE`。
