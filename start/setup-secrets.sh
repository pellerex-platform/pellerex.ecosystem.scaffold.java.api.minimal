#!/usr/bin/env bash
# Seed a local key-per-file secrets directory that mirrors the CSI tmpfs mount.
# Spring reads it via configtree: exactly as it reads /mnt/secrets-store/ in the cluster.
# Location follows the Pellerex local-secrets convention: $HOME/.pellerex/secrets/<product>/,
# selected by SECRETS_MOUNT_PATH (the same env var every non-.NET scaffold uses).
set -euo pipefail

SECRETS_MOUNT_PATH="${SECRETS_MOUNT_PATH:-$HOME/.pellerex/secrets/RepoUniqueNormalisedIdentifier}"
mkdir -p "$SECRETS_MOUNT_PATH"
chmod 700 "$SECRETS_MOUNT_PATH"

# One file per secret; the file name is the property name.
printf '%s' "Server=localhost;Database=RepoUniqueNormalisedIdentifier;User Id=sa;Password=Your_password123;TrustServerCertificate=True;" \
  > "$SECRETS_MOUNT_PATH/DbConnectionString"
chmod 600 "$SECRETS_MOUNT_PATH/DbConnectionString"

echo "Local secrets written to: $SECRETS_MOUNT_PATH"
ls -1 "$SECRETS_MOUNT_PATH"
