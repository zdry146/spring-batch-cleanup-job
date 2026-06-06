#!/usr/bin/env bash
# Check whether the local jenkins/deploy-pipeline.groovy matches the
# pipeline script stored in the Jenkins job's config.xml.
#
# Required env:
#   JENKINS_USER   - Jenkins username
#   JENKINS_TOKEN  - Jenkins API token (or password)
# Optional env:
#   JENKINS_URL    - default http://192.168.232.128:8080/
#   JOB_NAME       - default spring-batch-cleanup-job-deploy
#   SCRIPT_PATH    - default jenkins/deploy-pipeline.groovy
#   SHOW_DIFF      - true|false (default true)
#
# Exit codes:
#   0 - local and remote are identical
#   1 - local and remote differ (drift detected)
#   2 - error (auth, network, missing file, etc.)
set -euo pipefail

JENKINS_URL="${JENKINS_URL:-http://192.168.232.128:8080/}"
JOB_NAME="${JOB_NAME:-spring-batch-cleanup-job-deploy}"
SCRIPT_PATH="${SCRIPT_PATH:-jenkins/deploy-pipeline.groovy}"
SHOW_DIFF="${SHOW_DIFF:-true}"

: "${JENKINS_USER:?JENKINS_USER is required}"
: "${JENKINS_TOKEN:?JENKINS_TOKEN is required}"

if [ ! -f "$SCRIPT_PATH" ]; then
  echo "Error: script not found: $SCRIPT_PATH" >&2
  exit 2
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "Fetching $JENKINS_URL/job/$JOB_NAME/config.xml ..."
HTTP_CODE=$(curl -sS -o "$WORK/config.xml" -w "%{http_code}" \
  -u "$JENKINS_USER:$JENKINS_TOKEN" \
  "$JENKINS_URL/job/$JOB_NAME/config.xml")

if [ "$HTTP_CODE" != "200" ]; then
  echo "Error: HTTP $HTTP_CODE from Jenkins" >&2
  head -c 500 "$WORK/config.xml" >&2 || true
  exit 2
fi

python3 - "$WORK/config.xml" "$WORK/current-script.groovy" <<'PY'
import sys
import xml.etree.ElementTree as ET

config_path, out_path = sys.argv[1], sys.argv[2]
tree = ET.parse(config_path)
root = tree.getroot()

for elem in root.iter():
    if elem.tag == 'script' or elem.tag.endswith('}script'):
        with open(out_path, 'w') as f:
            f.write(elem.text or '')
        break
else:
    print("Error: no <script> element found in config.xml", file=sys.stderr)
    sys.exit(2)
PY

LOCAL_HASH=$(sha256sum "$SCRIPT_PATH" | cut -d' ' -f1)
REMOTE_HASH=$(sha256sum "$WORK/current-script.groovy" | cut -d' ' -f1)
LOCAL_SIZE=$(wc -c < "$SCRIPT_PATH")
REMOTE_SIZE=$(wc -c < "$WORK/current-script.groovy")

printf "  local:  %-50s %s  %d bytes\n" "$SCRIPT_PATH" "$LOCAL_HASH" "$LOCAL_SIZE"
printf "  remote: %-50s %s  %d bytes\n" "$JENKINS_URL/job/$JOB_NAME" "$REMOTE_HASH" "$REMOTE_SIZE"

if [ "$LOCAL_HASH" = "$REMOTE_HASH" ]; then
  echo "Status: in sync"
  exit 0
fi

echo "Status: DRIFT (local differs from Jenkins job)"
if [ "$SHOW_DIFF" = "true" ]; then
  echo "--- diff (remote -> local) ---"
  diff -u "$WORK/current-script.groovy" "$SCRIPT_PATH" || true
fi
exit 1
