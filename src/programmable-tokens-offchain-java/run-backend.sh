#!/usr/bin/env bash

# Script to run the backend with environment variables from .env file

set -e

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Load environment variables from .env file
if [ -f .env ]; then
    echo "Loading environment variables from .env file..."
    set -a  # automatically export all variables
    source .env
    set +a  # stop automatically exporting
    echo "Environment variables loaded."
else
    echo "Warning: .env file not found. Using system environment variables."
fi

# Verify BLOCKFROST_KEY is set
if [ -z "$BLOCKFROST_KEY" ]; then
    echo "Error: BLOCKFROST_KEY is not set!"
    echo "Please set it in the .env file or export it as an environment variable."
    exit 1
fi

echo "Starting backend with profile: ${SPRING_PROFILES_ACTIVE:-preview}"
echo "BLOCKFROST_KEY: ${BLOCKFROST_KEY:0:20}..." # Show first 20 chars for verification

# Run the backend
./gradlew bootRun --args="--spring.profiles.active=${SPRING_PROFILES_ACTIVE:-preview}"
