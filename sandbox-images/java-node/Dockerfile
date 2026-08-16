FROM eclipse-temurin:21-jdk-noble

ARG NODE_MAJOR=22
ARG USER_ID=10001
ARG GROUP_ID=10001

ENV DEBIAN_FRONTEND=noninteractive \
    MAVEN_VERSION=3.9.11 \
    HOME=/home/developer \
    MAVEN_USER_HOME=/home/developer/.m2 \
    PNPM_HOME=/opt/pnpm \
    PATH=/opt/pnpm:$PATH

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash build-essential ca-certificates curl diffutils findutils gnupg jq make \
        patch python3 ripgrep tar unzip wget xz-utils zip \
    && curl -fsSL https://deb.nodesource.com/setup_${NODE_MAJOR}.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
        | tar -xz -C /opt \
    && ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn \
    && corepack enable \
    && groupadd --gid ${GROUP_ID} developer \
    && useradd --uid ${USER_ID} --gid ${GROUP_ID} --create-home --shell /bin/bash developer \
    && mkdir -p /workspace /tmp/qgents /opt/pnpm \
    && chown -R developer:developer /workspace /tmp/qgents /opt/pnpm \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

USER developer:developer
WORKDIR /workspace

CMD ["sleep", "infinity"]
