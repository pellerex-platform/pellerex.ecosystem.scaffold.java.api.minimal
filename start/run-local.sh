#!/usr/bin/env bash
# Run the API locally on http://localhost:<port-number>, reading secrets from the local file mount
# via configtree: (the same mechanism used in the cluster). Exporting SECRETS_MOUNT_PATH lets
# application.yml's `configtree:${SECRETS_MOUNT_PATH:/mnt/secrets-store}/` resolve to the local
# mount — the exact code path the container/pod uses, just pointed at the dev secrets dir.
set -euo pipefail

cd "$(dirname "$0")/.."

export SECRETS_MOUNT_PATH="${SECRETS_MOUNT_PATH:-$HOME/.pellerex/secrets/RepoUniqueNormalisedIdentifier}"

# Seed the local key-per-file secrets mount if missing/empty (same layout as the prod CSI mount).
if [ ! -d "$SECRETS_MOUNT_PATH" ] || [ -z "$(ls -A "$SECRETS_MOUNT_PATH" 2>/dev/null)" ]; then
  ./start/setup-secrets.sh
fi

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-staging}"

echo "Starting Spring Boot (SPRING_PROFILES_ACTIVE=$SPRING_PROFILES_ACTIVE, SECRETS_MOUNT_PATH=$SECRETS_MOUNT_PATH) on :<port-number>"
mvn -B spring-boot:run
