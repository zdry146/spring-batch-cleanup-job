#!/bin/bash
# Test restart behavior: Step 1 should NOT re-run if Step 2 fails
#
# This script demonstrates Spring Batch restart semantics:
# - Each step has its own checkpoint/savepoint
# - If step 2 fails and we restart, step 1 does NOT re-run (already committed)

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
echo "Spring Batch Restart Behavior Test"
echo "=============================================="
echo ""
echo "This script demonstrates:"
echo "1. Step 1 (cleanupStep) commits its data"
echo "2. Step 2 (processDeletedPostsStep) would process deleted posts"
echo "3. If step 2 fails and we restart, step 1 does NOT re-run"
echo ""

# ============================================================================
# Test 1: Normal execution (both steps complete)
# ============================================================================
echo "=============================================="
echo "TEST 1: Normal Execution"
echo "=============================================="

echo "Preparing test data..."
psql_exec << 'EOF'
DELETE FROM posts WHERE title LIKE 'Restart Test%';
INSERT INTO posts (author_name, content, title, view_count, like_count, is_published, is_deleted, created_at, updated_at)
SELECT 'Tester', 'Content', 'Restart Test Post ' || i, 0, 0, false, false,
       NOW() - INTERVAL '35 days', NOW() - INTERVAL '35 days'
FROM generate_series(1, 5) i;
UPDATE posts SET is_deleted = true, updated_at = NOW() WHERE title LIKE 'Restart Test%';
EOF

echo "Running job..."
apply_local_job
sleep 15

POD_NAME=$(kubectl get pods -n $NAMESPACE -l job-name=$JOB_NAME -o name | head -1)
echo "Job completed. Check logs with:"
echo "kubectl logs -n $NAMESPACE $POD_NAME"

# ============================================================================
# Test 2: Step 1 fails (permanent error - step retries then fails)
# ============================================================================
echo ""
echo "=============================================="
echo "TEST 2: Step 1 Permanent Error (retries exhausted, job fails)"
echo "=============================================="
echo ""
echo "This test injects a PERMANENT error into Step 1."
echo "Step 1 will retry 3 times (10s each), then fail."
echo "Step 2 will NOT run because Step 1 failed."
echo ""
echo "To run this test (uses the lib-local.sh helper, no manifest editing):"
echo "  1. mvn clean package -DskipTests && docker build -t $LOCAL_IMAGE ."
echo "  2. apply_local_job \"\$LOCAL_IMAGE\" true                  # err_step1=true, err_step2=false"
echo "  3. Wait ~30 seconds for retries"
echo "  4. kubectl get job $JOB_NAME -n $NAMESPACE (should be Failed)"
echo ""

# ============================================================================
# Test 3: Step 1 fails but recovers (transient error - for retry testing)
# ============================================================================
echo "=============================================="
echo "TEST 3: Step 1 Transient Error (recovers on retry)"
echo "=============================================="
echo ""
echo "This test injects a TRANSIENT error into Step 1."
echo "Step 1 will fail on first attempt, wait 10s, then succeed on retry."
echo "Step 2 will run normally after Step 1 recovers."
echo ""
echo "To run this test:"
echo "  1. mvn clean package -DskipTests && docker build -t $LOCAL_IMAGE ."
echo "  2. apply_local_job \"\$LOCAL_IMAGE\" true false TRANSIENT  # err_step1=true, type=TRANSIENT"
echo "  3. Watch logs: kubectl logs -n $NAMESPACE -l job-name=$JOB_NAME -f"
echo "  4. You should see retry messages and eventually COMPLETED status"
echo ""

# ============================================================================
# Test 4: Step 2 fails, then restart (demonstrates restart semantics)
# ============================================================================
echo "=============================================="
echo "TEST 4: Step 2 Fails + Restart (Step 1 does NOT re-run)"
echo "=============================================="
echo ""
echo "This is the KEY restart test:"
echo "1. Step 1 commits successfully"
echo "2. Step 2 fails (inject error)"
echo "3. Fix the error"
echo "4. Restart job - Step 1 does NOT re-run!"
echo ""
echo "To run this test (the helper is fully self-contained — no file edits):"
echo "  1. mvn clean package -DskipTests && docker build -t $LOCAL_IMAGE ."
echo "  2. apply_local_job \"\$LOCAL_IMAGE\" false true            # err_step2=true (Step 2 will fail)"
echo "  3. Wait ~20s — Step 1 commits, Step 2 fails, Job ends FAILED"
echo "  4. mvn clean package -DskipTests && docker build -t $LOCAL_IMAGE .  # rebuild w/o error"
echo "  5. apply_local_job \"\$LOCAL_IMAGE\"                        # restart: Step 1 SKIPPED, Step 2 runs"
echo ""
echo "Check job execution history:"
echo "kubectl get job $JOB_NAME -n $NAMESPACE -o yaml"
echo ""

# ============================================================================
# Manual commands reference
# ============================================================================
echo "=============================================="
echo "Manual Test Commands"
echo "=============================================="
echo ""
echo "# Build and deploy (after code changes)"
echo "mvn clean package -DskipTests && docker build -t $LOCAL_IMAGE ."
echo ""
echo "# Apply the job with the local image (delete + sed + apply)"
echo "apply_local_job"
echo ""
echo "# View logs"
echo "kubectl logs -n $NAMESPACE -l job-name=$JOB_NAME -f"
echo ""
echo "# Check job status"
echo "kubectl get job $JOB_NAME -n $NAMESPACE"
echo ""
echo "# Delete job (next apply will recreate)"
echo "kubectl delete job $JOB_NAME -n $NAMESPACE"
echo ""

echo "=============================================="
echo "Done!"
echo "=============================================="