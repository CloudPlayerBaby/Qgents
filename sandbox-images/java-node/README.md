# Java / Node Agent Sandbox 镜像

镜像包含 Java 21、Maven、Node.js、pnpm、Python、ripgrep 等开发工具，刻意不安装 Git 和 OpenSSH 客户端。

全部 Git 操作由 Sandbox 外的 Workspace Manager 执行；容器只接收当前 TaskRun 获得授权的仓库 worktree bind mount。

镜像使用 `--create-home` 创建用户 `developer`（uid/gid 10001），home 为 `/home/developer`，并显式设置 `HOME` 与 `MAVEN_USER_HOME` 为该用户目录。Worker 以容器级 tmpfs 挂载 `/home/developer/.m2`（默认 `2g`，由 `SANDBOX_MAVEN_CACHE_SIZE` 控制），`mvn test` 和 `mvnw test` 都使用该目录作为本地依赖与 Wrapper 分发缓存。该缓存只属于单个 Sandbox，随容器销毁清理，不是持久缓存；rootfs 其余位置保持只读。

```bash
docker build -t qgents/sandbox-java-node:0.1.0 sandbox-images/java-node
docker run --rm qgents/sandbox-java-node:0.1.0 sh -lc 'git --version; test $? -eq 127'
```

验证 Maven 缓存可写（应与 Worker 的 tmpfs 配置一致）：

```bash
docker run --rm --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=512m \
  --tmpfs /run:rw,noexec,nosuid,size=64m \
  --tmpfs /home/developer/.m2:rw,nosuid,nodev,size=2g \
  qgents/sandbox-java-node:0.1.0 \
  sh -lc 'test -w /home/developer/.m2 && mvn -version'
```
