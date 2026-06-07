#!/bin/bash
# One-time setup for local-Docker E2E tests.
#
# Idempotent. Creates:
#   - the batch-jobs namespace
#   - the db-credentials Secret in that namespace (from .env's DB_PASSWORD)
#
# After this, `make test-happy` (or any of the other test targets) can
# run without further cluster prep.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
NAMESPACE="batch-jobs"
DB_SECRET="db-credentials"

# --- env ----------------------------------------------------------------

if [ -z "${DB_PASSWORD:-}" ] && [ -f "$PROJECT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$PROJECT_DIR/.env"
  set +a
fi
: "${DB_PASSWORD:?DB_PASSWORD must be set, e.g. 'export DB_PASSWORD=...' or create .env from .env.example}"

# --- pre-flight: kubectl reachable, cluster responds --------------------

if ! command -v kubectl >/dev/null 2>&1; then
  echo "ERROR: kubectl is not on PATH. Install it or set up your kubeconfig first." >&2
  exit 1
fi

if ! kubectl cluster-info >/dev/null 2>&1; then
  echo "ERROR: kubectl cannot reach a cluster. Set KUBECONFIG or run \`minikube start\` (or your cluster equivalent)." >&2
  exit 1
fi

# --- 1. namespace --------------------------------------------------------

if kubectl get namespace "$NAMESPACE" >/dev/null 2>&1; then
  echo "Namespace '$NAMESPACE' already exists."
else
  echo "Creating namespace '$NAMESPACE'..."
  kubectl apply -f k8s/namespace.yaml
fi

# --- 2. db-credentials Secret -------------------------------------------

if kubectl -n "$NAMESPACE" get secret "$DB_SECRET" >/dev/null 2>&1; then
  echo "Secret '$NAMESPACE/$DB_SECRET' already exists; not overwriting."
  echo "  (to rotate: delete it and re-run this script, or use Jenkins)"
else
  echo "Creating Secret '$NAMESPACE/$DB_SECRET'..."
  kubectl -n "$NAMESPACE" create secret generic "$DB_SECRET" \
    --from-literal=password="$DB_PASSWORD"
fi

# --- 3. helpful summary -------------------------------------------------

echo ""
echo "Cluster is ready for local-Docker tests:"
echo "  namespace : $NAMESPACE"
echo "  db secret : $DB_SECRET (password sourced from .env / env)"
echo "  postgres  : $DB_HOST:$DB_DATABASE (must be reachable from the cluster's pods)"
echo ""
echo "Next:"
echo "  mvn -B -DskipTests clean package # build the jar"
echo "  docker build -t cleanup-batch:1.0.0 .       # build the local image"
echo "  bash scripts/run-and-verify.sh              # happy-path E2E"
echo "  bash scripts/test-error-injection.sh        # retry + restart with injected errors"
echo "  bash scripts/test-same-day-manual-run.sh    # regression for the no-op fix"
echo "  mvn -Pe2e verify                            # all of the above in one command"
