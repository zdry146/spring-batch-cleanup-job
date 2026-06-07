#!/bin/bash
# End-to-end local test cycle, called by `mvn -Pe2e verify`.
#
# This is the Maven-driven counterpart to the Jenkins CD pipeline:
#   mvn -B clean verify           <-- unit + integration tests
#   docker build -t ...            <-- local image (no registry, no push)
#   bash scripts/setup-local.sh    <-- idempotent cluster prep
#   bash scripts/run-and-verify.sh <-- happy-path E2E
#   bash scripts/test-error-injection.sh <-- retry / restart with injected errors
#   bash scripts/test-same-day-manual-run.sh <-- regression for the no-op fix
#
# The Jenkins pipeline is the PROMOTION path (CI/CD to the testing cluster
# via the registry). This script is the LOCAL DEBUG path (build + deploy
# to a local/cluster + watch pods). Same source, same tests, different
# transport. See README.md > "Local-Docker testing" for the trade-off.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOCAL_IMAGE="${LOCAL_IMAGE:-cleanup-batch:1.0.0}"

cd "$PROJECT_DIR"

echo "=============================================="
echo "1. mvn -B clean verify  (unit + integration)"
echo "=============================================="
mvn -B clean verify

echo ""
echo "=============================================="
echo "2. docker build -t $LOCAL_IMAGE ."
echo "=============================================="
docker build -t "$LOCAL_IMAGE" .

echo ""
echo "=============================================="
echo "3. bash scripts/setup-local.sh  (idempotent)"
echo "=============================================="
bash "$SCRIPT_DIR/setup-local.sh"

echo ""
echo "=============================================="
echo "4. bash scripts/run-and-verify.sh  (happy path)"
echo "=============================================="
bash "$SCRIPT_DIR/run-and-verify.sh"

echo ""
echo "=============================================="
echo "5. bash scripts/test-error-injection.sh  (retry + restart)"
echo "=============================================="
bash "$SCRIPT_DIR/test-error-injection.sh"

echo ""
echo "=============================================="
echo "6. bash scripts/test-same-day-manual-run.sh  (no-op regression)"
echo "=============================================="
# Default to the local image; user can override with DEPLOY_IMAGE=...
DEPLOY_IMAGE="${DEPLOY_IMAGE:-$LOCAL_IMAGE}" \
  bash "$SCRIPT_DIR/test-same-day-manual-run.sh"

echo ""
echo "=============================================="
echo "All E2E tests passed. Cluster is in a COMPLETED state for today."
echo "  mvn -B clean verify                - just unit + integration tests"
echo "  bash scripts/run-and-verify.sh     - just the happy-path E2E"
echo "  bash scripts/test-error-injection.sh - just the error-injection E2E"
echo "=============================================="
