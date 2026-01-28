#!/bin/bash
# CLM Service Local Runner
# Sets up Oracle wallet environment and runs Spring Boot

# Source .env file if it exists (will be loaded as shell vars)
if [ -f .env ]; then
    set -a  # automatically export all variables
    source .env
    set +a
fi

# Validate required Oracle environment
missing_vars=()
[ -z "$ORACLE_USERNAME" ] && missing_vars+=("ORACLE_USERNAME")
[ -z "$ORACLE_PASSWORD" ] && missing_vars+=("ORACLE_PASSWORD")
[ -z "$ORACLE_TNS_NAME" ] && missing_vars+=("ORACLE_TNS_NAME")
[ -z "$ORACLE_WALLET_LOCATION" ] && missing_vars+=("ORACLE_WALLET_LOCATION")

if [ ${#missing_vars[@]} -ne 0 ]; then
    echo "Missing required environment variables: ${missing_vars[*]}" >&2
    echo "Populate .env or export them before running." >&2
    exit 1
fi

# Set TNS_ADMIN to the wallet location
export TNS_ADMIN="$ORACLE_WALLET_LOCATION"

echo "===================================="
echo "CLM Service Local Runner"
echo "===================================="
echo "TNS_ADMIN:          $TNS_ADMIN"
echo "ORACLE_TNS_NAME:    $ORACLE_TNS_NAME"
echo "ORACLE_USERNAME:    $ORACLE_USERNAME"
echo "Password length:    ${#ORACLE_PASSWORD}"
echo "===================================="

# Run Spring Boot
exec mvn spring-boot:run
