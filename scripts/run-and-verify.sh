#!/bin/bash
# Run cleanup job and verify results

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
DB_HOST="${DB_HOST:-192.168.232.128}"
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
echo "1. Inserting test data"
echo "=============================================="

psql_exec << 'EOF'
-- Insert unpublished old posts (for cleanupStep - Step 1)
INSERT INTO posts (author_name, content, title, view_count, like_count, is_published, is_deleted, created_at, updated_at)
SELECT
    'Author ' || i,
    'Content ' || i,
    'Unpublished Post ' || i,
    0, 0, false, false,
    NOW() - INTERVAL '35 days',
    NOW() - INTERVAL '35 days'
FROM generate_series(1, 15) i;

-- Mark some posts as already deleted (for processDeletedPostsStep - Step 2)
UPDATE posts
SET is_deleted = true, updated_at = NOW() - INTERVAL '1 day'
WHERE title LIKE 'Unpublished Post 1'
   OR title LIKE 'Unpublished Post 2'
   OR title LIKE 'Unpublished Post 3'
   OR title LIKE 'Unpublished Post 4'
   OR title LIKE 'Unpublished Post 5';
EOF

echo "Test data inserted."

echo ""
echo "=============================================="
echo "2. Checking data before job execution"
echo "=============================================="

echo "Unpublished old posts:"
psql_query "SELECT COUNT(*) FROM posts WHERE is_published = false AND is_deleted = false AND created_at < NOW() - INTERVAL '30 days';"

echo "Already deleted posts:"
psql_query "SELECT COUNT(*) FROM posts WHERE is_deleted = true;"

echo ""
echo "=============================================="
echo "3. Running cleanup job (local image $LOCAL_IMAGE)"
echo "=============================================="
apply_local_job

echo "Waiting for job to complete..."
kubectl wait --for=condition=complete job/$JOB_NAME -n $NAMESPACE --timeout=60s 2>/dev/null || true

echo ""
echo "=============================================="
echo "5. Job status"
echo "=============================================="
kubectl get job $JOB_NAME -n $NAMESPACE

echo ""
echo "=============================================="
echo "6. Job logs"
echo "=============================================="
POD_NAME=$(kubectl get pods -n $NAMESPACE -l job-name=$JOB_NAME -o name 2>/dev/null | head -1 || true)
if [ -n "$POD_NAME" ]; then
    kubectl logs -n $NAMESPACE $POD_NAME 2>&1 | grep -E "(Step|Reader|Writer|Soft-deleting|Job completed)" || true
else
    echo "(pod already reaped; skipping log dump)"
fi

echo ""
echo "=============================================="
echo "7. Data after job execution"
echo "=============================================="

echo "Unpublished old posts (should be 0 or reduced):"
psql_query "SELECT COUNT(*) FROM posts WHERE is_published = false AND is_deleted = false AND created_at < NOW() - INTERVAL '30 days';"

echo "Already deleted posts (should include newly deleted):"
psql_query "SELECT COUNT(*) FROM posts WHERE is_deleted = true;"

echo ""
echo "=============================================="
echo "Done!"
echo "=============================================="