#!/bin/bash
# Test Step 1 failure and restart behavior
# This script actually performs the tests

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Auto-load local dev credentials from .env (gitignored) so agents
# and humans can run this script with zero environment setup.
# An already-exported DB_PASSWORD in the shell always wins.
if [ -z "${DB_PASSWORD:-}" ] && [ -f "$PROJECT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$PROJECT_DIR/.env"
  set +a
fi

NAMESPACE="batch-jobs"
JOB_NAME="cleanup-manual"
LOCAL_IMAGE="${LOCAL_IMAGE:-cleanup-batch:1.0.0}"
DB_HOST="${DB_HOST:-localhost}"
DB_DATABASE="${DB_DATABASE:-testdb}"
DB_USERNAME="${DB_USERNAME:-postgres}"
: "${DB_PASSWORD:?DB_PASSWORD must be set, e.g. 'export DB_PASSWORD=...' or create .env from .env.example}"

# Load the apply-local-job helper (delete + sed-on-stream + apply).
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib-local.sh"

psql_query() {
    PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USERNAME -d $DB_DATABASE -t -c "$1"
}

psql_exec() {
    PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USERNAME -d $DB_DATABASE
}

echo "=============================================="
echo "TEST: Step 1 Permanent Error"
echo "=============================================="

# Prepare test data
echo "1. Preparing test data..."
psql_exec << 'EOF'
DELETE FROM posts WHERE title LIKE 'Restart Test%';
INSERT INTO posts (author_name, content, title, view_count, like_count, is_published, is_deleted, created_at, updated_at)
SELECT 'Tester', 'Content', 'Restart Test Post ' || i, 0, 0, false, false,
       NOW() - INTERVAL '35 days', NOW() - INTERVAL '35 days'
FROM generate_series(1, 5) i;
UPDATE posts SET is_deleted = true, updated_at = NOW() WHERE title LIKE 'Restart Test%';
EOF

echo "2. Rebuilding Docker image with ERROR_INJECTION_STEP1=true baked in..."
cd "$PROJECT_DIR"
mvn clean package -DskipTests -q
docker build -t $LOCAL_IMAGE . -q

echo "3. Running job with Step 1 error injection..."
apply_local_job "$LOCAL_IMAGE" true

echo "4. Waiting for job to fail (will retry 3 times, ~30 seconds)..."
sleep 40

echo ""
echo "6. Job status:"
kubectl get job $JOB_NAME -n $NAMESPACE

echo ""
echo "7. Pod status:"
kubectl get pods -n $NAMESPACE -l job-name=$JOB_NAME

echo ""
echo "8. Logs (showing retry behavior):"
POD_NAME=$(kubectl get pods -n $NAMESPACE -l job-name=$JOB_NAME -o name 2>/dev/null | head -1 || true)
if [ -n "$POD_NAME" ]; then
    kubectl logs -n $NAMESPACE $POD_NAME 2>&1 | grep -E "(ERROR INJECTION|Retrying|retry|Step:|Job completed)" | head -20 || true
else
    echo "(pod already reaped; skipping log dump)"
fi

echo ""
echo "=============================================="
echo "TEST: Step 2 Failure + Restart"
echo "=============================================="

echo "1. Preparing fresh test data..."
psql_exec << 'EOF'
DELETE FROM posts WHERE title LIKE 'Restart Test%';
INSERT INTO posts (author_name, content, title, view_count, like_count, is_published, is_deleted, created_at, updated_at)
SELECT 'Tester', 'Content', 'Restart Test Post ' || i, 0, 0, false, false,
       NOW() - INTERVAL '35 days', NOW() - INTERVAL '35 days'
FROM generate_series(1, 5) i;
UPDATE posts SET is_deleted = true, updated_at = NOW() WHERE title LIKE 'Restart Test%';
EOF

echo "2. Rebuilding Docker image with ERROR_INJECTION_STEP2=true baked in..."
mvn clean package -DskipTests -q
docker build -t $LOCAL_IMAGE . -q

echo "3. Running job (Step 1 should succeed, Step 2 should fail)..."
apply_local_job "$LOCAL_IMAGE" false true

sleep 20

echo ""
echo "4. Job status (should show FAILED because Step 2 failed):"
kubectl get job $JOB_NAME -n $NAMESPACE

echo ""
echo "5. Step 1 was successful, Step 2 failed. Now testing restart..."

echo "6. Rebuilding Docker image with no error injection (for the restart run)..."
mvn clean package -DskipTests -q
docker build -t $LOCAL_IMAGE . -q

echo "7. Restarting job (Step 1 should be SKIPPED)..."
apply_local_job "$LOCAL_IMAGE"
sleep 20

echo ""
echo "8. Final job status:"
kubectl get job $JOB_NAME -n $NAMESPACE

echo ""
echo "9. Logs (Step 1 should NOT appear in this run):"
POD_NAME=$(kubectl get pods -n $NAMESPACE -l job-name=$JOB_NAME -o name 2>/dev/null | head -1 || true)
if [ -n "$POD_NAME" ]; then
    kubectl logs -n $NAMESPACE $POD_NAME 2>&1 | grep -E "(Step|Reader|Writer|Soft-deleting|Job completed)" || true
else
    echo "(pod already reaped; skipping log dump)"
fi

echo ""
echo "=============================================="
echo "Done! Check the logs above to verify:"
echo "- Step 1 was skipped on restart (already committed)"
echo "- Step 2 ran and completed successfully"
echo "=============================================="