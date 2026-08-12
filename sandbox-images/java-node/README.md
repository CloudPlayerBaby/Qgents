# Java + Node.js 沙箱镜像

该镜像面向 Java 后端与 Web 前端任务，包含 JDK 21、Maven 3.9.11、Node.js 22、npm、Corepack、Git、ripgrep、Python 3 和常用构建工具。

```bash
docker build -t qgents/sandbox-java-node:0.1.0 sandbox-images/java-node
```

镜像默认使用用户与组编号均为 `10001` 的 `developer` 用户，不包含 Docker 客户端、云平台工具或任何凭证。生产环境应把镜像推送到受控仓库，并在 Worker 配置中使用不可变 digest。
