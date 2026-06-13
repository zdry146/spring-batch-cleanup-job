#!/bin/bash
# Full Jenkins CI/CD end-to-end test.
#
# This is the registry-image / Jenkins-driven counterpart to
# scripts/e2e-cycle.sh (which uses a local docker build). Same source,
# same tests, different transport: this script triggers
# spring-batch-cleanup-job-cicd on the live Jenkins instance and waits
# for the resulting pod to finish, then **hard-asserts the data**:
#
#   1. Cleanup: delete the k8s cleanup-manual Job + clear the
#      cleanupUnpublishedPostsJob rows in batch_* (existing primitive).
#   2. Seed:    insert 18 E2E-% rows across 5 groups (4 control + 1
#      target + 1 already-deleted).
#   3. Trigger: invoke the extended scripts/jenkins-run-cicd.py with
#      MODE=both and the same DB_HOST/DB_DATABASE the script was run
#      with, so the deployed pod uses the same DB this script queries.
#   4. Verify:  wait for the pod, soft-assert log lines, hard-assert
#      5 row counts + 1 batch_job_execution count. Non-zero exit on
#      any mismatch.
#
# Re-runnable: starts with `DELETE FROM posts WHERE title LIKE 'E2E-%'`
# so re-runs don't accumulate; clears Spring Batch state so the
# CleanupJobRunner no-op path doesn't kick in.
#
# Pre-conditions:
#   - kubectl context is the cluster Jenkins deploys to
#   - DB_HOST, DB_USERNAME, DB_PASSWORD, DB_DATABASE are resolvable
#     (auto-loaded from .env, or set in the shell)
#   - JENKINS_USER, JENKINS_TOKEN are set (Jenkins user with build
#     permission on spring-batch-cleanup-job-cicd)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
NAMESPACE="batch-jobs"
JOB_NAME="cleanup-manual"
SPRING_BATCH_JOB_NAME="cleanupUnpublishedPostsJob"
JENKINS_CICD_JOB="spring-batch-cleanup-job-cicd"

# Auto-load local dev credentials from .env (gitignored) so agents
# and humans can run this script with zero environment setup. An
# already-exported var in the shell always wins.
if [ -z "${DB_PASSWORD:-}" ] || [ -z "${JENKINS_USER:-}" ]; then
  if [ -f "$PROJECT_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    . "$PROJECT_DIR/.env"
    set +a
  fi
fi
: "${DB_HOST:?DB_HOST must be set, e.g. 'export DB_HOST=...'}"
: "${DB_USERNAME:?DB_USERNAME must be set}"
: "${DB_PASSWORD:?DB_PASSWORD must be set, e.g. 'export DB_PASSWORD=...' or create .env from .env.example}"
: "${DB_DATABASE:?DB_DATABASE must be set}"
: "${JENKINS_USER:?JENKINS_USER must be set (Jenkins user with build permission on $JENKINS_CICD_JOB)}"
: "${JENKINS_TOKEN:?JENKINS_TOKEN must be set (Jenkins API token for $JENKINS_USER)}"

PASS=0
FAIL=0
pass() { echo "  OK:   $*"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL: $*"; FAIL=$((FAIL + 1)); }

psql_query() {
  PGPASSWORD=$DB_PASSWORD psql -h "$DB_HOST" -U "$DB_USERNAME" -d "$DB_DATABASE" -t -A -c "$1"
}

psql_exec() {
  PGPASSWORD=$DB_PASSWORD psql -h "$DB_HOST" -U "$DB_USERNAME" -d "$DB_DATABASE"
}

echo "=============================================="
echo "Full CI/CD e2e test"
echo "  cluster:    $(kubectl config current-context 2>/dev/null || echo '?')"
echo "  namespace:  $NAMESPACE"
echo "  db:         $DB_HOST / $DB_DATABASE"
echo "  jenkins:    $JENKINS_CICD_JOB (http://localhost:8080/)"
echo "=============================================="
echo ""

# === STEP 1: CLEANUP ====================================================
echo "=============================================="
echo "STEP 1: Cleanup"
echo "=============================================="

# (Filled in by Task 3)

# === STEP 2: SEED =======================================================
echo ""
echo "=============================================="
echo "STEP 2: Seed test data"
echo "=============================================="

# (Filled in by Task 4)

# === STEP 3: TRIGGER CICD ===============================================
echo ""
echo "=============================================="
echo "STEP 3: Trigger spring-batch-cleanup-job-cicd (MODE=both)"
echo "=============================================="

# (Filled in by Task 5)

# === STEP 4: VERIFY =====================================================
echo ""
echo "=============================================="
echo "STEP 4: Verify"
echo "=============================================="

# (Filled in by Tasks 6 and 7)

# === SUMMARY ============================================================
echo ""
echo "=============================================="
echo "Summary: $PASS passed, $FAIL failed"
echo "=============================================="
[ "$FAIL" = "0" ]