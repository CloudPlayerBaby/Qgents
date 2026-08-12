FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/qgents-sandbox-worker-0.1.0-SNAPSHOT.jar app.jar
RUN useradd --system --uid 10001 worker
USER 10001
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
