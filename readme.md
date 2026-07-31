# Java / Spring Boot — Managed API Service scaffold (minimal)

The Spring Boot REST API scaffold for the Pellerex **Managed API Service** product. Provisioning
clones this repo, tokenises it, builds the image, and deploys it with Helm — exactly the same
17-step chain every other language runs. Nothing in the engine is Java-aware.

## What it is

| Concern | How |
|---|---|
| Framework | Spring Boot 3.5 (REST, embedded Tomcat), JDK 21 |
| Port | The `<port-number>` token (`server.port`, Dockerfile `EXPOSE`, Helm `targetPort`), provisioned platform-wide |
| Health | Plain `@RestController` at `/health/{startup,live,ready}` (root, no `/api`, not Actuator). No Kubernetes probes |
| Config | `application-{profile}.yml` + `@Validated @ConfigurationProperties` → **fail-fast at boot** |
| Secrets | CSI tmpfs **file mount** read via `spring.config.import=configtree:/mnt/secrets-store/` — no `secretObjects`, no env-var secrets |
| Telemetry | **Codeless** App Insights Java agent (`-javaagent`), `APPLICATIONINSIGHTS_CONNECTION_STRING` from the tokenised env |
| Logging | Spring Boot **structured logging** (ECS JSON) fan-out: console + daily-rolling `log-<date>.log`/`.json` files + App Insights (agent ships Logback records); config-driven level; `X-Correlation-Id` on every record |
| Image | Multi-stage Temurin 21 JDK build → `eclipse-temurin:21-jre`, layered jar, non-root |
| Hardening | Read-only root FS + writable `/tmp` emptyDir, drop ALL caps, no-priv-esc, runAsNonRoot, seccomp `RuntimeDefault` |
| Supply chain | Pinned deps + OWASP `dependency-check` CI gate |
| Shutdown | `server.shutdown=graceful` — Tomcat drains in-flight requests on `SIGTERM` |

## Layout

```
pom.xml                       # Spring Boot 3.5, pinned deps, OWASP dependency-check gate
Dockerfile                    # multi-stage, layered jar, App Insights agent, non-root
applicationinsights.json      # agent self-diagnostics -> console (read-only FS friendly)
src/main/java/com/pellerex/api # Application, controllers, config, web advice, model
src/main/resources             # application.yml + application-{staging,production,qualityassurance}.yml
src/test/java/com/pellerex/api # health, sample+validation, configtree secret, config fail-fast tests
infrastructure/
  Helm/                        # Deployment+Service(ClusterIP 80-><port-number>)+Ingress+ServiceAccount, no probes
  secret-provider-class-*.yaml # CSI SecretProviderClass per env (file mount only)
  azure-containers-pipelines.yml # mvn verify -> dependency-check -> docker build -> push
```

## Run locally

Secrets live under the Pellerex local-secrets convention — `~/.pellerex/secrets/<product>/`, one
file per secret, selected by `SECRETS_MOUNT_PATH` (the same env var every non-.NET scaffold uses).
`run-local.sh` exports it and seeds the dir on first run, so Spring reads it through the exact
`configtree:` path the pod uses.

```bash
./start/setup-secrets.sh     # seed ~/.pellerex/secrets/<product>/ (DbConnectionString)
./start/run-local.sh         # mvn spring-boot:run on http://localhost:<port-number>
```

```bash
curl http://localhost:<port-number>/health/ready
curl http://localhost:<port-number>/v1/hello
```

## Logging (Serilog parity)

Structured logging via Spring Boot's **native structured logging** (ECS JSON) + Logback, fanned
out to three sinks like the .NET scaffold's Serilog — zero extra dependencies:

1. **Console** — ECS JSON to stdout (always; collected by the platform).
2. **Files** — daily-rolling `log-<date>.log` (text) + `log-<date>.json` (ECS JSON) under
   `LOG_FILE_DIRECTORY` (default `logs` locally, `/var/log/app` in-cluster on a writable
   `emptyDir` — the read-only root FS stays intact), pruned after `LOG_RETENTION_DAYS`.
3. **Azure Application Insights** — the codeless `-javaagent` captures Logback records (INFO+)
   and ships them with trace↔log correlation; no appender or SDK code needed.

Every ECS record carries `service.name` / `service.version` / `service.environment` (per profile).
`CorrelationIdFilter` honours an inbound `X-Correlation-Id` (or mints a UUID), echoes it on the
response, puts it in the MDC (so it lands on every record in every sink), and emits one structured
line per request. Level precedence: `LOG_LEVEL` env > profile yml > `INFO`.

## Build the image

Mount the local secrets dir read-only at `/mnt/secrets-store` — the same path the CSI driver uses
in-cluster — so the container reads its secret the same way (`SECRETS_MOUNT_PATH` stays unset, so
the app's `/mnt/secrets-store` default is used, exactly as in prod).

```bash
docker build -t pellerex/managed-api-java:dev .
docker run --rm -p <port-number>:<port-number> \
  -e SPRING_PROFILES_ACTIVE=production \
  -v "$HOME/.pellerex/secrets/RepoUniqueNormalisedIdentifier:/mnt/secrets-store:ro" \
  pellerex/managed-api-java:dev
```

## Provisioning tokens

The platform tokeniser substitutes (among others) `RepoUniqueNormalisedIdentifier`,
`<secret-provider-class-enabled>`, the three `<{env}-namespace>` / `<{env}-keyvault-name>` /
`<azure-app-insights-connection-string-in-{env}>` tokens, `<identity-id>`, `<tenant-id>` and
`<target-branch>`. The port is the `<port-number>` token, provisioned platform-wide.
