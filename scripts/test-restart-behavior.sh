#!/bin/bash
# Test restart behavior: Step 1 should NOT re-run if Step 2 fails
#
# This script demonstrates Spring Batch restart semantics:
# - Each step has its own checkpoint/savepoint
# - If step 2 fails and we restart, step 1 does NOT re-run (already committed)

set -e

NAMESPACE="batch-jobs"
DB_HOST="192.168.232.128"

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
PGPASSWORD=postgres psql -h $DB_HOST -U postgres -d testdb << 'EOF'
DELETE FROM posts WHERE title LIKE 'Restart Test%';
INSERT INTO posts (author_name, content, title, view_count, like_count, is_published, is_deleted, created_at, updated_at)
SELECT 'Tester', 'Content', 'Restart Test Post ' || i, 0, 0, false, false,
       NOW() - INTERVAL '35 days', NOW() - INTERVAL '35 days'
FROM generate_series(1, 5) i;
UPDATE posts SET is_deleted = true, updated_at = NOW() WHERE title LIKE 'Restart Test%';
EOF

echo "Running job..."
kubectl delete job cleanup-manual -n $NAMESPACE 2>/dev/null || true
kubectl apply -f k8s/job.yaml
sleep 15

POD_NAME=$(kubectl get pods -n $NAMESPACE -l job-name=cleanup-manual -o name | head -1)
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
echo "To run this test:"
echo "1. Set ERROR_INJECTION_STEP1=true in k8s/job.yaml"
echo "2. kubectl delete job cleanup-manual -n $NAMESPACE"
echo "3. kubectl apply -f k8s/job.yaml"
echo "4. Wait ~30 seconds for retries"
echo "5. Check: kubectl get job -n $NAMESPACE (should be Failed)"
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
echo "1. Set ERROR_INJECTION_STEP1=true and ERROR_TYPE=TRANSIENT in k8s/job.yaml"
echo "2. kubectl delete job cleanup-manual -n $NAMESPACE"
echo "3. kubectl apply -f k8s/job.yaml"
echo "4. Watch logs: kubectl logs -n $NAMESPACE -l job-name=cleanup-manual -f"
echo "5. You should see retry messages and eventually COMPLETED status"
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
echo "To run this test:"
echo "1. Set ERROR_INJECTION_STEP2=true in k8s/job.yaml"
echo "2. kubectl delete job cleanup-manual -n $NAMESPACE"
echo "3. kubectl apply -f k8s/job.yaml"
echo "   - Step 1 completes successfully"
echo "   - Step 2 fails"
echo "4. Set ERROR_INJECTION_STEP2=false in k8s/job.yaml"
echo "5. kubectl apply -f k8s/job.yaml (restart)"
echo "   - Step 1 is SKIPPED (already committed)"
echo "   - Step 2 runs and completes"
echo ""
echo "Check job execution history:"
echo "kubectl get job cleanup-manual -n $NAMESPACE -o yaml"
echo ""

# ============================================================================
# Manual commands reference
# ============================================================================
echo "=============================================="
echo "Manual Test Commands"
echo "=============================================="
echo ""
echo "# Build and deploy (after code changes)"
echo "mvn clean package -DskipTests && docker build -t cleanup-batch:1.0.0 ."
echo ""
echo "# Run normal job"
echo "kubectl apply -f k8s/job.yaml"
echo ""
echo "# View logs"
echo "kubectl logs -n $NAMESPACE -l job-name=cleanup-manual -f"
echo ""
echo "# Check job status"
echo "kubectl get job cleanup-manual -n $NAMESPACE"
echo ""
echo "# Delete job"
echo "kubectl delete job cleanup-manual -n $NAMESPACE"
echo ""

echo "=============================================="
echo "Done!"
echo "=============================================="