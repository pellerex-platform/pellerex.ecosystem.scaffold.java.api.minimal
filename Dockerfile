# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage — Temurin 21 JDK + Maven (JV-D15). Builds, tests, extracts the
# layered jar, and fetches the pinned App Insights agent.
# ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first so they cache independently of source changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Compile + run unit tests, then explode the Spring Boot layered jar (JV-D14).
COPY src ./src
RUN mvn -B clean package
RUN java -Djarmode=tools -jar target/application.jar extract --layers --destination target/extracted

# Pinned Application Insights Java agent for codeless telemetry (JV-D7 / J5).
RUN mvn -B dependency:copy \
      -Dartifact=com.microsoft.azure:applicationinsights-agent:3.7.9 \
      -DoutputDirectory=/agent

# ---------------------------------------------------------------------------
# Runtime stage — JRE only, non-root, read-only-root-FS friendly (JV-D15 / J14).
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Non-root user; UID 10001 matches the Helm pod securityContext.runAsUser.
# (Avoids GID/UID 1000, already taken by the base image's 'ubuntu' user.)
RUN groupadd --gid 10001 spring \
 && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin spring

# App Insights agent + its config. The COPY target filename matches the -javaagent: path exactly.
COPY --from=build /agent/applicationinsights-agent-3.7.9.jar /app/applicationinsights-agent-3.7.9.jar
COPY applicationinsights.json /app/applicationinsights.json

# Layered jar copied least- to most-frequently-changed for optimal Docker caching.
COPY --from=build /build/target/extracted/dependencies/ ./
COPY --from=build /build/target/extracted/spring-boot-loader/ ./
COPY --from=build /build/target/extracted/snapshot-dependencies/ ./
COPY --from=build /build/target/extracted/application/ ./

USER spring
EXPOSE 8890

# -javaagent attaches App Insights codelessly (APPLICATIONINSIGHTS_CONNECTION_STRING is supplied by
# the tokenised Helm env). -XX:MaxRAMPercentage sizes the heap to the container memory limit (J13).
ENTRYPOINT ["java", \
  "-javaagent:/app/applicationinsights-agent-3.7.9.jar", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "application.jar"]
