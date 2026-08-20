FROM eclipse-temurin:21-jdk-noble

ARG NODE_MAJOR=22
ARG USER_ID=10001
ARG GROUP_ID=10001
# 固定 Android 命令行工具的官方归档版本与校验和，保证镜像构建可复现。
ARG ANDROID_COMMAND_LINE_TOOLS_VERSION=15859902
ARG ANDROID_COMMAND_LINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ARG ANDROID_API_LEVEL=35
ARG ANDROID_BUILD_TOOLS_VERSION=35.0.0

ENV DEBIAN_FRONTEND=noninteractive \
    MAVEN_VERSION=3.9.11 \
    GRADLE_VERSION=8.10.2 \
    HOME=/home/developer \
    MAVEN_USER_HOME=/home/developer/.m2 \
    GRADLE_USER_HOME=/home/developer/.gradle \
    NPM_CONFIG_CACHE=/home/developer/.npm \
    PNPM_HOME=/opt/pnpm \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_USER_HOME=/home/developer/.android \
    JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/home/developer/.cache -Djansi.tmpdir=/home/developer/.cache" \
    PATH=/opt/pnpm:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash build-essential ca-certificates curl diffutils findutils gnupg jq make \
        patch python3 ripgrep tar unzip wget xz-utils zip \
    && curl -fsSL https://deb.nodesource.com/setup_${NODE_MAJOR}.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
        | tar -xz -C /opt \
    && ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn \
    && curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && ln -s "/opt/gradle-${GRADLE_VERSION}" /opt/gradle \
    && ln -s /opt/gradle/bin/gradle /usr/local/bin/gradle \
    && rm -f /tmp/gradle.zip \
    && corepack enable \
    && groupadd --gid ${GROUP_ID} developer \
    && useradd --uid ${USER_ID} --gid ${GROUP_ID} --create-home --shell /bin/bash developer \
    && mkdir -p /workspace /tmp/qgents /opt/pnpm /home/developer/.m2 /home/developer/.gradle /home/developer/.npm /home/developer/.cache \
    && chown -R developer:developer /workspace /tmp/qgents /opt/pnpm /home/developer \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# 只安装无界面 Android 编译/本地单测所需 SDK；不引入 Android Studio、NDK 或 Emulator。
# SDK 本体位于镜像只读层，用户级 Android 状态仍写入每个 Sandbox 的 HOME tmpfs。
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" "${ANDROID_USER_HOME}" \
    && curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_COMMAND_LINE_TOOLS_VERSION}_latest.zip" -o /tmp/android-command-line-tools.zip \
    && echo "${ANDROID_COMMAND_LINE_TOOLS_SHA256}  /tmp/android-command-line-tools.zip" | sha256sum -c - \
    && unzip -q /tmp/android-command-line-tools.zip -d /tmp/android-command-line-tools \
    && mv /tmp/android-command-line-tools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest" \
    && yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses >/dev/null \
    && sdkmanager --sdk_root="${ANDROID_HOME}" \
        "platform-tools" \
        "platforms;android-${ANDROID_API_LEVEL}" \
        "build-tools;${ANDROID_BUILD_TOOLS_VERSION}" \
    && chown -R developer:developer "${ANDROID_USER_HOME}" \
    && rm -rf /tmp/android-command-line-tools /tmp/android-command-line-tools.zip

USER developer:developer
WORKDIR /workspace

CMD ["sleep", "infinity"]
