# Dev Tools Agent Sandbox 镜像

镜像包含 Java 21、Maven 3.9.11、Gradle 8.10.2、Node.js 22、npm、pnpm、Python、ripgrep、Android SDK Command-line Tools、Android Platform Tools、Android API 35 Platform 与 Build Tools 35.0.0。它支持 Android 的编译和本地 Gradle 单元测试，刻意不安装 Android Studio、Android Emulator、NDK、Git 和 OpenSSH 客户端。

全部 Git 操作由 Sandbox 外的 Workspace Manager 执行；容器只接收当前 TaskRun 获得授权的仓库 worktree bind mount。

镜像使用非 root 用户 `developer`（uid/gid 10001）。Android SDK 固定预置在镜像层的 `/opt/android-sdk`，通过 `ANDROID_HOME` 提供；用户级 Android 状态位于 `/home/developer/.android`，由 `ANDROID_USER_HOME` 指定。Worker 默认使用可写 rootfs，并以容器级 tmpfs 挂载开发用户 HOME（默认 `8g`）、pnpm 全局目录（`1g`）以及 Maven、Gradle、npm 和通用缓存目录（默认分别为 `2g`、`3g`、`1g`、`512m`），同时开放 `/tmp`、`/var/tmp` 和 `/run` 的临时空间。Workspace 仍只通过 Worker 挂载当前任务授权的 Repository。项目自带 Wrapper 优先，Wrapper 没有 executable bit 时使用 `sh ./gradlew` 或 `sh ./mvnw` 启动。

构建阶段使用固定版本的 Android Command-line Tools，校验 SHA-256 后接受对应 SDK 许可证并安装 API 35。部署此镜像即表示部署方同意 [Android SDK License Agreement](https://developer.android.com/studio/terms)。当目标仓库的 `compileSdk` 或 `buildToolsVersion` 不同，应通过 Docker build arguments 指定匹配版本，例如 `--build-arg ANDROID_API_LEVEL=36 --build-arg ANDROID_BUILD_TOOLS_VERSION=36.0.0`。

```powershell
docker build -t qgents/sandbox-dev-tools:0.2.0 sandbox-images/dev-tools
```

验证构建工具、Android SDK 与缓存可用（应与 Worker 的 tmpfs 配置一致）：

```bash
docker run --rm \
  --tmpfs /tmp:rw,noexec,nosuid,size=512m \
  --tmpfs /var/tmp:rw,noexec,nosuid,size=512m \
  --tmpfs /run:rw,noexec,nosuid,size=64m \
  --tmpfs /home/developer:rw,nosuid,nodev,uid=10001,gid=10001,mode=700,size=8g \
  --tmpfs /home/developer/.m2:rw,nosuid,nodev,uid=10001,gid=10001,mode=700,size=2g \
  --tmpfs /home/developer/.gradle:rw,nosuid,nodev,uid=10001,gid=10001,mode=700,size=3g \
  --tmpfs /home/developer/.npm:rw,nosuid,nodev,uid=10001,gid=10001,mode=700,size=1g \
  --tmpfs /home/developer/.cache:rw,nosuid,nodev,uid=10001,gid=10001,mode=700,size=512m \
  --tmpfs /opt/pnpm:rw,nosuid,nodev,uid=10001,gid=10001,mode=700,size=1g \
  qgents/sandbox-dev-tools:0.2.0 \
  sh -lc 'set -eu; java -version; mvn -version; gradle --version; node --version; npm --version; test -d "$ANDROID_HOME/platform-tools"; test -d "$ANDROID_HOME/platforms/android-35"; test -d "$ANDROID_HOME/build-tools/35.0.0"; sdkmanager --version; test -w /home/developer; test -w /home/developer/.m2; test -w /home/developer/.gradle; test -w /home/developer/.npm; mkdir -p "$ANDROID_USER_HOME" /home/developer/.config /home/developer/.local; test -w "$ANDROID_USER_HOME"'
```
