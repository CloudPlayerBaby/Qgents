# Dev Tools Agent Sandbox 镜像

镜像包含 Java 21、Maven 3.9.11、Gradle 8.10.2、Node.js 22、npm、pnpm、Python、ripgrep 和常用构建工具，刻意不安装 Git 和 OpenSSH 客户端。

全部 Git 操作由 Sandbox 外的 Workspace Manager 执行；容器只接收当前 TaskRun 获得授权的仓库 worktree bind mount。

镜像使用非 root 用户 `developer`（uid/gid 10001）。Worker 以容器级 tmpfs 挂载 Maven、Gradle、npm 和通用缓存目录（默认分别为 `2g`、`3g`、`1g`、`512m`），rootfs 其余位置保持只读。项目自带 Wrapper 优先，Wrapper 没有 executable bit 时使用 `sh ./gradlew` 或 `sh ./mvnw` 启动。

```powershell
docker build -t qgents/sandbox-dev-tools:0.2.0 sandbox-images/dev-tools
```

验证 Maven 缓存可写（应与 Worker 的 tmpfs 配置一致）：

```bash
docker run --rm --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=512m \
  --tmpfs /run:rw,noexec,nosuid,size=64m \
  --tmpfs /home/developer/.m2:rw,nosuid,nodev,size=2g \
  --tmpfs /home/developer/.gradle:rw,nosuid,nodev,size=3g \
  --tmpfs /home/developer/.npm:rw,nosuid,nodev,size=1g \
  --tmpfs /home/developer/.cache:rw,nosuid,nodev,size=512m \
  qgents/sandbox-dev-tools:0.2.0 \
  sh -lc 'set -eu; java -version; mvn -version; gradle --version; node --version; npm --version; test -w /home/developer/.m2; test -w /home/developer/.gradle; test -w /home/developer/.npm'
```
