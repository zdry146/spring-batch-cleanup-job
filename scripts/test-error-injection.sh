#!/bin/bash
# Test Step 1 failure and restart behavior
# This script actually performs the tests

set -e

NAMESPACE="batch-jobs"
JOB_NAME="cleanup-manual"
DOCKER_IMAGE="cleanup-batch:1.0.0"
DB_HOST="${DB_HOST:-192.168.232.128}"
DB_DATABASE="${DB_DATABASE:-testdb}"
DB_USERNAME="${DB_USERNAME:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

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

echo "2. Enabling ERROR_INJECTION_STEP1=true..."
sed -i 's/ERROR_INJECTION_STEP1.*false/ERROR_INJECTION_STEP1 true/' "$PROJECT_DIR/k8s/job.yaml"

echo "3. Rebuilding Docker image..."
cd "$PROJECT_DIR"
mvn clean package -DskipTests -q
docker build -t $DOCKER_IMAGE . -q

echo "4. Running job with Step 1 error injection..."
kubectl delete job $JOB_NAME -n $NAMESPACE 2>/dev/null || true
kubectl apply -f k8s/job.yaml

echo "5. Waiting for job to fail (will retry 3 times, ~30 seconds)..."
sleep 40

echo ""
echo "6. Job status:"
kubectl get job $JOB_NAME -n $NAMESPACE

echo ""
echo "7. Pod status:"
kubectl get pods -n $NAMESPACE -l job-name=$JOB_NAME

echo ""
echo "8. Logs (showing retry behavior):"
POD_NAME=$(kubectl get pods -n $NAMESPACE -l job-name=$JOB_NAME -o name | head -1)
kubectl logs -n $NAMESPACE $POD_NAME 2>&1 | grep -E "(ERROR INJECTION|Retrying|retry|Step:|Job completed)" | head -20

echo ""
echo "9. Disabling error injection..."
sed -i 's/ERROR_INJECTION_STEP1.*true/ERROR_INJECTION_STEP1 false/' "$PROJECT_DIR/k8s/job.yaml"

mvn clean package -DskipTests -q
docker build -t $DOCKER_IMAGE . -q

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

echo "2. Enabling ERROR_INJECTION_STEP2=true..."
sed -i 's/ERROR_INJECTION_STEP2.*false/ERROR_INJECTION_STEP2 true/' "$PROJECT_DIR/k8s/job.yaml"

echo "3. Rebuilding..."
mvn clean package -DskipTests -q
docker build -t $DOCKER_IMAGE . -q

echo "4. Running job (Step 1 should succeed, Step 2 should fail)..."
kubectl delete job $JOB_NAME -n $NAMESPACE 2>/dev/null || true
kubectl apply -f k8s/job.yaml
sleep 20

echo ""
echo "5. Job status (should show FAILED because Step 2 failed):"
kubectl get job $JOB_NAME -n $NAMESPACE

echo ""
echo "6. Step 1 was successful, Step 2 failed. Now testing restart..."

sed -i 's/ERROR_INJECTION_STEP2.*true/ERROR_INJECTION_STEP2 false/' "$PROJECT_DIR/k8s/job.yaml"
mvn clean package -DskipTests -q
docker build -t $DOCKER_IMAGE . -q

echo "7. Restarting job (Step 1 should be SKIPPED)..."
kubectl apply -f k8s/job.yaml
sleep 20

echo ""
echo "8. Final job status:"
kubectl get job $JOB_NAME -n $NAMESPACE

echo ""
echo "9. Logs (Step 1 should NOT appear in this run):"
POD_NAME=$(kubectl get pods -n $NAMESPACE -l job-name=$JOB_NAME -o name | head -1)
kubectl logs -n $NAMESPACE $POD_NAME 2>&1 | grep -E "(Step|Reader|Writer|Soft-deleting|Job completed)"

echo ""
echo "=============================================="
echo "Done! Check the logs above to verify:"
echo "- Step 1 was skipped on restart (already committed)"
echo "- Step 2 ran and completed successfully"
echo "=============================================="