# syntax=docker/dockerfile:1

# Container image of Warden (CON-009, SDD V21): built from source in a JDK stage, run as a
# non-root user on a JRE with the Spring Boot jar extracted in layers, so a code change rebuilds
# only the application layer.
#
#   docker build -t warden:local .
#
# The base images are pinned by tag here; the reference environment pins every image by digest.

# ---- Build: compile and package with the Maven wrapper (tests run in CI, not here) ----
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
# The cache mount keeps the Maven repository between builds without putting it in a layer.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -Dmaven.test.skip=true -Djacoco.skip=true package \
    && cp target/warden-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- Runtime: JRE only, non-root, layers from the least to the most frequently changed ----
FROM eclipse-temurin:21-jre-noble AS runtime

RUN groupadd --system --gid 10001 warden \
    && useradd --system --uid 10001 --gid warden --no-create-home --shell /usr/sbin/nologin warden

WORKDIR /app
COPY --from=build --chown=warden:warden /workspace/extracted/dependencies/ ./
COPY --from=build --chown=warden:warden /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=warden:warden /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=warden:warden /workspace/extracted/application/ ./

USER 10001:10001

# 8080: API with the local profile; 8443: API over TLS with the prod-like profile. The management
# port (8081) is deliberately not exposed: it is reached only inside the deployment network.
EXPOSE 8080 8443

# The JVM is PID 1, so it receives SIGTERM itself and shuts down gracefully (REQ-AVAIL-002). The
# heap takes at most 60 % of the container's memory limit; the rest is left to metaspace, threads,
# direct buffers and Argon2 (ADR-010). JAVA_TOOL_OPTIONS adds options without rebuilding.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]