# Java / Node Agent Sandbox 镜像

镜像包含 Java 21、Maven、Node.js、pnpm、Python、ripgrep 等开发工具，刻意不安装 Git 和 OpenSSH 客户端。

全部 Git 操作由 Sandbox 外的 Workspace Manager 执行；容器只接收当前 TaskRun 获得授权的仓库 worktree bind mount。

```bash
docker build -t qgents/sandbox-java-node:0.1.0 sandbox-images/java-node
docker run --rm qgents/sandbox-java-node:0.1.0 sh -lc 'git --version; test $? -eq 127'
```
