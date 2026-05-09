#!/bin/bash
# Run cleanup job and verify results

set -e

NAMESPACE="batch-jobs"
JOB_NAME="cleanup-manual"
DOCKER_IMAGE="cleanup-batch:1.0.0"
DB_HOST="${DB_HOST:-192.168.232.128}"
DB_DATABASE="${DB_DATABASE:-testdb}"
DB_USERNAME="${DB_USERNAME:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"

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
echo "3. Deleting old job (if exists)"
echo "=============================================="
kubectl delete job $JOB_NAME -n $NAMESPACE 2>/dev/null || true

echo ""
echo "=============================================="
echo "4. Running cleanup job"
echo "=============================================="
kubectl apply -f k8s/job.yaml

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
POD_NAME=$(kubectl get pods -n $NAMESPACE -l job-name=$JOB_NAME -o name | head -1)
kubectl logs -n $NAMESPACE $POD_NAME 2>&1 | grep -E "(Step|Reader|Writer|Soft-deleting|Job completed)"

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