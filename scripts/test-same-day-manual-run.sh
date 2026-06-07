#!/bin/bash
# Regression test for the same-day manual run behavior.
#
# The DateJobParametersIncrementer gives each calendar day a distinct
# JobInstance, so the same-day manual run AFTER the daily cron has
# completed must be a graceful no-op (exits 0), not a JVM crash that
# puts the pod into CrashLoopBackOff. This script exercises all three
# meaningful states against the live cluster.
#
# Pre-conditions:
#   - The new image (with the CleanupJobRunner fix) is in the registry
#     (i.e. the spring-batch-cleanup-job-ci build has run since the fix
#     was pushed). The sed-patch below uses :latest, so the latest CI
#     build is what gets tested.
#   - kubectl context points at the cluster that holds the
#     'batch-jobs' namespace.
#   - DB credentials are available (via .env in the project root, an
#     already-exported DB_PASSWORD, or ~/.pgpass for psql).
#
# The script is self-restoring: it starts and ends with a COMPLETED
# JobInstance for today (the post-cron state), so re-running it is
# safe.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
NAMESPACE="batch-jobs"
JOB_NAME="cleanup-manual"
SPRING_BATCH_JOB_NAME="cleanupUnpublishedPostsJob"
DEFAULT_IMAGE="crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com/mike-docker-registry/spring-batch-cleanup-job:latest"
# Allow override via env (handy for testing a different tag in CI,
# or running the test against a local image: DEPLOY_IMAGE=cleanup-batch:1.0.0)
DEPLOY_IMAGE="${DEPLOY_IMAGE:-$DEFAULT_IMAGE}"

# Load the apply-local-job helper (delete + sed-on-stream + apply).
# shellcheck disable=SC1091
. "$SCRIPT_DIR/lib-local.sh"

# --- env ----------------------------------------------------------------

# Auto-load local dev credentials from .env (gitignored) so agents and
# humans can run this script with zero environment setup. An
# already-exported DB_PASSWORD in the shell always wins.
if [ -z "${DB_PASSWORD:-}" ] && [ -f "$PROJECT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$PROJECT_DIR/.env"
  set +a
fi
: "${DB_PASSWORD:?DB_PASSWORD must be set, e.g. 'export DB_PASSWORD=...' or create .env from .env.example}"
: "${DB_HOST:?DB_HOST must be set}"
: "${DB_USERNAME:?DB_USERNAME must be set}"
: "${DB_DATABASE:?DB_DATABASE must be set}"

PASS=0
FAIL=0
fail() { echo "  FAIL: $*"; FAIL=$((FAIL + 1)); }
pass() { echo "  OK";   PASS=$((PASS + 1)); }

# --- helpers ------------------------------------------------------------

psql_query() {
  PGPASSWORD=$DB_PASSWORD psql -h "$DB_HOST" -U "$DB_USERNAME" -d "$DB_DATABASE" -t -A -c "$1"
}

# Thin wrapper that pins DEPLOY_IMAGE so callers below stay readable.
apply_job_with_image() {
  apply_local_job "$DEPLOY_IMAGE"
}

wait_for_pod() {
  local pod timeout=60
  for _ in $(seq 1 $timeout); do
    pod=$(kubectl -n "$NAMESPACE" get pods -l "job-name=$JOB_NAME" -o name 2>/dev/null | head -1)
    if [ -n "$pod" ]; then
      local phase
      phase=$(kubectl -n "$NAMESPACE" get "$pod" -o jsonpath='{.status.phase}' 2>/dev/null)
      [ "$phase" = "Succeeded" ] || [ "$phase" = "Failed" ] && { echo "$pod"; return 0; }
    fi
    sleep 1
  done
  return 1
}

pod_exit_code() {
  kubectl -n "$NAMESPACE" get "$1" -o jsonpath='{.status.containerStatuses[0].state.terminated.exitCode}' 2>/dev/null
}

pod_log_matches() {
  kubectl -n "$NAMESPACE" logs "$1" 2>&1 | grep -qE "$2"
}

count_executions() {
  psql_query "SELECT COUNT(*) FROM batch_job_execution WHERE job_instance_id IN (SELECT job_instance_id FROM batch_job_instance WHERE job_name = '$SPRING_BATCH_JOB_NAME');"
}

cleanup_spring_batch_state() {
  PGPASSWORD=$DB_PASSWORD psql -h "$DB_HOST" -U "$DB_USERNAME" -d "$DB_DATABASE" \
    -v job_name="$SPRING_BATCH_JOB_NAME" -v ON_ERROR_STOP=1 \
    -f "$SCRIPT_DIR/sql/cleanup-spring-batch-job.sql" 2>&1 | tail -3
}

# --- header -------------------------------------------------------------

echo "=============================================="
echo "Same-day manual run — regression test"
echo "  image:    $DEPLOY_IMAGE"
echo "  cluster:  $(kubectl config current-context 2>/dev/null || echo '?')"
echo "  namespace: $NAMESPACE"
echo "  job:      $JOB_NAME"
echo "=============================================="
echo ""

# Make sure we have a baseline: at least one JobInstance for today, so
# the post-test cluster state matches the pre-test state.
PRE_COUNT=$(count_executions)
echo "Pre-test JobInstance count for $SPRING_BATCH_JOB_NAME: $PRE_COUNT"
echo ""

# --- TEST A: COMPLETED instance present → no-op -------------------------

echo "=============================================="
echo "TEST A: COMPLETED instance present → no-op"
echo "=============================================="
echo "(this is the bug we fixed: before the fix the pod entered CrashLoopBackOff)"
echo ""

apply_job_with_image "$DEPLOY_IMAGE"
echo "Job applied. Waiting for pod..."
POD_A=$(wait_for_pod) || { fail "pod did not reach terminal phase"; exit 1; }
EXIT_A=$(pod_exit_code "$POD_A")
echo "Pod: $POD_A   exit: $EXIT_A"
COUNT_A=$(count_executions)
echo "JobInstance count after Test A: $COUNT_A (should equal pre-test $PRE_COUNT)"

[ "$EXIT_A" = "0" ] && pass "pod exited 0" || fail "pod exit was $EXIT_A, expected 0"
pod_log_matches "$POD_A" "Job instance for today is already COMPLETED" \
  && pass "log shows the no-op message" \
  || fail "log does NOT contain the no-op message — fix is not active in this image"
[ "$COUNT_A" = "$PRE_COUNT" ] && pass "no new JobInstance created" || fail "a new JobInstance was created (no-op didn't no-op)"

# --- clean state for Test B --------------------------------------------

echo ""
echo "--- Cleaning Spring Batch state for $SPRING_BATCH_JOB_NAME (Test B needs an empty slate) ---"
cleanup_spring_batch_state
POST_CLEAN_COUNT=$(count_executions)
[ "$POST_CLEAN_COUNT" = "0" ] && pass "state cleared" || fail "state not cleared (count=$POST_CLEAN_COUNT)"

# --- TEST B: empty state → job actually runs ---------------------------

echo ""
echo "=============================================="
echo "TEST B: empty state → job actually runs"
echo "=============================================="
echo "(proves the fix doesn't break the normal flow — a fresh launch still runs the job end-to-end)"
echo ""

apply_job_with_image "$DEPLOY_IMAGE"
POD_B=$(wait_for_pod) || { fail "pod did not reach terminal phase"; exit 1; }
EXIT_B=$(pod_exit_code "$POD_B")
echo "Pod: $POD_B   exit: $EXIT_B"
COUNT_B=$(count_executions)
echo "JobInstance count after Test B: $COUNT_B (should be 1)"

[ "$EXIT_B" = "0" ] && pass "pod exited 0" || fail "pod exit was $EXIT_B, expected 0"
pod_log_matches "$POD_B" "Step: \[cleanupStep\] executed" \
  && pass "Step 1 actually ran" \
  || fail "Step 1 did NOT run — fix is consuming the empty-state path too"
pod_log_matches "$POD_B" "Job: \[.*cleanupUnpublishedPostsJob.*\] completed" \
  && pass "job completed successfully" \
  || fail "job did NOT complete in the log"
[ "$COUNT_B" = "1" ] && pass "exactly one new JobInstance created" || fail "JobInstance count is $COUNT_B, expected 1"

# --- TEST C: post-B state has COMPLETED → no-op again ------------------

echo ""
echo "=============================================="
echo "TEST C: state has COMPLETED (from Test B) → no-op again"
echo "=============================================="
echo "(proves the no-op kicks in even when the COMPLETED instance was made by the manual run, not the cron)"
echo ""

apply_job_with_image "$DEPLOY_IMAGE"
POD_C=$(wait_for_pod) || { fail "pod did not reach terminal phase"; exit 1; }
EXIT_C=$(pod_exit_code "$POD_C")
echo "Pod: $POD_C   exit: $EXIT_C"
COUNT_C=$(count_executions)
echo "JobInstance count after Test C: $COUNT_C (should still be 1)"

[ "$EXIT_C" = "0" ] && pass "pod exited 0" || fail "pod exit was $EXIT_C, expected 0"
pod_log_matches "$POD_C" "Job instance for today is already COMPLETED" \
  && pass "log shows the no-op message" \
  || fail "log does NOT contain the no-op message"
[ "$COUNT_C" = "$COUNT_B" ] && pass "no new JobInstance created" || fail "a new JobInstance was created"

# --- summary ------------------------------------------------------------

echo ""
echo "=============================================="
echo "Summary: $PASS passed, $FAIL failed"
echo "=============================================="
[ "$FAIL" = "0" ]
