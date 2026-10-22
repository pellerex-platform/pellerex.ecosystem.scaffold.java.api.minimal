# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage: Temurin 21 JDK + Maven (JV-D15). Builds, extracts the
# layered jar, and fetches the pinned App Insights agent.
# ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first so they cache independently of source changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Compile and package without the tests, then explode the Spring Boot layered jar (JV-D14).
# The tests run once, in the unit-tests stage below, which the CI builds run before the image.
# Test sources are not compiled here either, so a compile error in a test reaches that
# stage's exit code instead of stopping this stage.
COPY src ./src
RUN mvn -B clean package -Dmaven.test.skip=true
RUN java -Djarmode=tools -jar target/application.jar extract --layers --destination target/extracted

# Pinned Application Insights Java agent for codeless telemetry (JV-D7 / J5).
RUN mvn -B dependency:copy \
      -Dartifact=com.microsoft.azure:applicationinsights-agent:3.7.9 \
      -DoutputDirectory=/agent

# ---------------------------------------------------------------------------
# Unit tests stage: runs the unit tests with the JaCoCo coverage agent and keeps
# Surefire's JUnit XML, the JaCoCo XML report and the test command's exit code.
# The RUN always succeeds, so the results are kept when a test fails: the CI
# builds read the exit code and stop. The default image build skips this stage.
# Pellerex reads these folder and file names from the build's test-coverage
# artifact for the app's Test Coverage tab, so they stay as they are (decided
# 20 Oct 2026).
# ---------------------------------------------------------------------------
FROM build AS unit-tests

# What two tests read besides the code: the agent's settings (ApplicationInsightsSettingsTest)
# and the Helm chart (HealthChartTest).
COPY applicationinsights.json ./
COPY infrastructure/Helm ./infrastructure/Helm

RUN mkdir -p /unit-test-results/test-coverage/unit-tests /unit-test-results/test-coverage/coverage; \
    mvn -B jacoco:prepare-agent test; \
    echo "$?" > /unit-test-results/unit-tests-exit-code; \
    mvn -B jacoco:report; \
    cp target/surefire-reports/TEST-*.xml /unit-test-results/test-coverage/unit-tests/; \
    cp target/site/jacoco/jacoco.xml /unit-test-results/test-coverage/coverage/; \
    exit 0

# ---------------------------------------------------------------------------
# Unit test results: only the files the unit-tests stage kept. Exporting it
# (--target unit-test-results with a local --output) puts test-coverage/ and
# unit-tests-exit-code at the root of the export folder.
# ---------------------------------------------------------------------------
FROM scratch AS unit-test-results
COPY --from=unit-tests /unit-test-results /

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
EXPOSE <port-number>

# -javaagent attaches App Insights codelessly (APPLICATIONINSIGHTS_CONNECTION_STRING is supplied by
# the tokenised Helm env). -XX:MaxRAMPercentage sizes the heap to the container memory limit (J13).
ENTRYPOINT ["java", \
  "-javaagent:/app/applicationinsights-agent-3.7.9.jar", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "application.jar"]
