#!/bin/bash
# Shared helper for the local E2E test scripts.
#
# Source this file from a script that defines:
#   PROJECT_DIR  - path to the project root
#   NAMESPACE    - k8s namespace (e.g. "batch-jobs")
#   JOB_NAME     - job name (e.g. "cleanup-manual")
#   LOCAL_IMAGE  - image:tag for the locally-built image (e.g. "cleanup-batch:1.0.0")

# apply_local_job: apply k8s/job.yaml as a local Job, overriding image,
# imagePullPolicy, DB_HOST, DB_DATABASE, and the three ERROR_INJECTION_*
# env vars in-stream. The source file is never modified (no `sed -i`),
# so re-running the script is safe and `git status` stays clean.
#
# Usage:
#   apply_local_job                                   # all defaults
#   apply_local_job <image>                           # override image
#   apply_local_job <image> <err1> <err2> <err_type>  # override env vars
#   apply_local_job <image> <err1> <err2> <err_type> <db_host>             # also DB_HOST
#   apply_local_job <image> <err1> <err2> <err_type> <db_host> <db_name>   # also DB_DATABASE
#
# Defaults:
#   image        = $LOCAL_IMAGE
#   error_step1  = false
#   error_step2  = false
#   error_type   = PERMANENT
#   db_host      = 192.168.232.128 (matches the pre-placeholder value
#                  hard-coded in k8s/job.yaml so existing flows keep
#                  working; override here if your cluster reaches a
#                  different PostgreSQL server)
#   db_name      = testdb (matches the ${DB_DATABASE:testdb} default in
#                  src/main/resources/application.yml)
apply_local_job() {
  local image="${1:-$LOCAL_IMAGE}"
  local error_step1="${2:-false}"
  local error_step2="${3:-false}"
  local error_type="${4:-PERMANENT}"
  local db_host="${5:-192.168.232.128}"
  local db_name="${6:-testdb}"
  local image_no_tag="${image%:*}"

  # Delete the existing Job first so the new manifest can replace it
  # (Job's pod-template is immutable after first pod is created).
  kubectl -n "$NAMESPACE" delete job "$JOB_NAME" --ignore-not-found >/dev/null 2>&1 || true

  # Stream-sed the manifest: swap the registry image for the local
  # one, flip the pull policy so k8s doesn't try to pull a local tag
  # from any registry, substitute the DB_HOST and DB_DATABASE
  # placeholders, and override the error-injection env vars. The
  # ERROR_INJECTION_* lines are pairs (- name: / value: "..."), so we
  # use sed's `N` to read the next line into the pattern space, then
  # substitute only the value portion. The image line is matched on
  # indentation (k8s/job.yaml has it at 8 spaces) because the local
  # image name doesn't share a prefix with the registry image.
  sed -e "s|^\([[:space:]]*\)image: .*|\1image: ${image}|" \
      -e "s|imagePullPolicy: Always|imagePullPolicy: IfNotPresent|" \
      -e "s|__DB_HOST__|${db_host}|g" \
      -e "s|__DB_DATABASE__|${db_name}|g" \
      -e "/^[[:space:]]*- name: ERROR_INJECTION_STEP1\$/{N; s|value: \".*\"|value: \"${error_step1}\"|}" \
      -e "/^[[:space:]]*- name: ERROR_INJECTION_STEP2\$/{N; s|value: \".*\"|value: \"${error_step2}\"|}" \
      -e "/^[[:space:]]*- name: ERROR_TYPE\$/{N; s|value: \".*\"|value: \"${error_type}\"|}" \
      "$PROJECT_DIR/k8s/job.yaml" \
    | kubectl -n "$NAMESPACE" apply -f - >/dev/null
}
