#!/usr/bin/env bash
# Push the local jenkins/deploy-pipeline.groovy into the Jenkins job's
# inline pipeline script via the Jenkins REST API.
#
# Required env:
#   JENKINS_USER   - Jenkins username
#   JENKINS_TOKEN  - Jenkins API token (or password)
# Optional env:
#   JENKINS_URL    - default http://192.168.232.128:8080/
#   JOB_NAME       - default spring-batch-cleanup-job-deploy
#   SCRIPT_PATH    - default jenkins/deploy-pipeline.groovy
#   DRY_RUN        - true to show diff without posting (default false)
#
# After the update, run the check script to confirm the round trip.
set -euo pipefail

JENKINS_URL="${JENKINS_URL:-http://192.168.232.128:8080/}"
JOB_NAME="${JOB_NAME:-spring-batch-cleanup-job-deploy}"
SCRIPT_PATH="${SCRIPT_PATH:-jenkins/deploy-pipeline.groovy}"
DRY_RUN="${DRY_RUN:-false}"

: "${JENKINS_USER:?JENKINS_USER is required}"
: "${JENKINS_TOKEN:?JENKINS_TOKEN is required}"

if [ ! -f "$SCRIPT_PATH" ]; then
  echo "Error: script not found: $SCRIPT_PATH" >&2
  exit 1
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
COOKIES="$WORK/cookies"

# Acquire CSRF crumb if Jenkins emits one. Use a cookie jar so the crumb
# stays valid for the subsequent POST (crumb is session-scoped).
CRUMB_ARGS=()
if CRUMB_JSON=$(curl -fsS -c "$COOKIES" -b "$COOKIES" \
    -u "$JENKINS_USER:$JENKINS_TOKEN" \
    "$JENKINS_URL/crumbIssuer/api/json" 2>/dev/null); then
  CRUMB_FIELD=$(printf '%s' "$CRUMB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['crumbRequestField'])")
  CRUMB_VALUE=$(printf '%s' "$CRUMB_JSON" | python3 -c "import sys,json; print(json.load(sys.stdin)['crumb'])")
  CRUMB_ARGS=(-H "$CRUMB_FIELD: $CRUMB_VALUE")
fi

echo "Fetching $JENKINS_URL/job/$JOB_NAME/config.xml ..."
HTTP_CODE=$(curl -sS -c "$COOKIES" -b "$COOKIES" \
  -o "$WORK/config.xml" -w "%{http_code}" \
  -u "$JENKINS_USER:$JENKINS_TOKEN" \
  "$JENKINS_URL/job/$JOB_NAME/config.xml")

if [ "$HTTP_CODE" != "200" ]; then
  echo "Error: HTTP $HTTP_CODE from Jenkins" >&2
  head -c 500 "$WORK/config.xml" >&2 || true
  exit 1
fi

python3 - "$WORK/config.xml" "$SCRIPT_PATH" "$WORK/new-config.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

config_path, script_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]

tree = ET.parse(config_path)
root = tree.getroot()

with open(script_path) as f:
    new_script = f.read()

replaced = False
for elem in root.iter():
    if elem.tag == 'script' or elem.tag.endswith('}script'):
        elem.text = new_script
        replaced = True
        break

if not replaced:
    print("Error: no <script> element found in config.xml", file=sys.stderr)
    sys.exit(1)

tree.write(out_path, xml_declaration=True, encoding='UTF-8')
print(f"Injected {len(new_script)} bytes from {script_path} into <script>")
PY

if [ "$DRY_RUN" = "true" ]; then
  echo "DRY RUN: would POST $WORK/new-config.xml to $JENKINS_URL/job/$JOB_NAME/config.xml"
  diff -u "$WORK/config.xml" "$WORK/new-config.xml" || true
  exit 0
fi

echo "Uploading updated config.xml ..."
HTTP_CODE=$(curl -sS -c "$COOKIES" -b "$COOKIES" \
  -o "$WORK/response.xml" -w "%{http_code}" \
  -X POST -u "$JENKINS_USER:$JENKINS_TOKEN" \
  "${CRUMB_ARGS[@]}" \
  -H "Content-Type: text/xml" \
  --data-binary "@$WORK/new-config.xml" \
  "$JENKINS_URL/job/$JOB_NAME/config.xml")

if [ "$HTTP_CODE" != "200" ]; then
  echo "Error: HTTP $HTTP_CODE from Jenkins" >&2
  head -c 1000 "$WORK/response.xml" >&2 || true
  exit 1
fi

echo "Updated $JOB_NAME with $SCRIPT_PATH"
echo "Run scripts/jenkins-check-deploy-job.sh to verify."
