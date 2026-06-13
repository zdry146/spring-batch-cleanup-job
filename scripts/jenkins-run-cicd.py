#!/usr/bin/env python3
"""Trigger spring-batch-cleanup-job-cicd with MODE=both and stream the log."""
import os
import sys
import time
import base64
import json
import urllib.request
import urllib.error
import http.cookiejar

JENKINS_URL = os.environ.get("JENKINS_URL", "http://localhost:8080/")
JOB = "spring-batch-cleanup-job-cicd"
PARAM = "both"
POLL_INTERVAL = 5
LOG_TAIL_BYTES = 60 * 1024  # last 60 KiB on each tail
STREAM_POLL = 2


def make_opener():
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    token = base64.b64encode(
        f"{os.environ['JENKINS_USER']}:{os.environ['JENKINS_TOKEN']}".encode()
    ).decode()
    opener.addheaders = [("Authorization", f"Basic {token}")]
    return opener


def fetch_crumb(opener):
    try:
        with opener.open(f"{JENKINS_URL}crumbIssuer/api/json") as r:
            d = json.loads(r.read())
            return d["crumbRequestField"], d["crumb"]
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None, None
        raise


def trigger(opener, field, crumb):
    url = f"{JENKINS_URL}job/{JOB}/buildWithParameters"
    data = f"MODE={PARAM}".encode()
    headers = {"Content-Type": "application/x-www-form-urlencoded"}
    if field and crumb:
        headers[field] = crumb
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    with opener.open(req) as r:
        if r.status not in (200, 201):
            sys.exit(f"trigger failed: HTTP {r.status}")
        loc = r.headers.get("Location", "")
        # Location like /queue/item/N/ or /job/<name>/N/
        return loc


def wait_for_number(opener, queue_loc, timeout=60):
    """Resolve the queue item to a build number, or wait briefly if build not yet started."""
    deadline = time.time() + timeout
    path = queue_loc.replace(JENKINS_URL, "")
    # /queue/item/N/ -> poll the queue item until executable.url appears
    while time.time() < deadline:
        try:
            with opener.open(f"{JENKINS_URL}{path}api/json") as r:
                d = json.loads(r.read())
            exe = d.get("executable")
            if exe and exe.get("url"):
                return exe["number"], exe["url"]
        except urllib.error.HTTPError:
            pass
        time.sleep(1)
    sys.exit(f"timed out waiting for {queue_loc} to start a build")


def stream_log(opener, build_url, last_offset=0):
    """Stream the progressive log from `last_offset`. Returns (new_text, new_offset, is_finished)."""
    log_url = f"{build_url}logText/progressiveText"
    if last_offset:
        log_url += f"?start={last_offset}"
    try:
        with opener.open(log_url, timeout=30) as r:
            text = r.read().decode("utf-8", errors="replace")
            new_offset = int(r.headers.get("X-Text-Size", "0"))
            more = r.headers.get("X-More-Data", "false").lower() == "true"
            return text, new_offset, not more
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return "", last_offset, False
        raise


def wait_result(opener, build_url, build_num):
    deadline = time.time() + 30 * 60  # pipeline timeout is 30 min
    offset = 0
    printed = 0
    while time.time() < deadline:
        text, offset, finished = stream_log(opener, build_url, offset)
        if text:
            sys.stdout.write(text)
            sys.stdout.flush()
            printed += len(text)
        # check status
        with opener.open(f"{build_url}api/json") as r:
            j = json.loads(r.read())
        if not j.get("inProgress") and finished:
            return j
        time.sleep(STREAM_POLL)
    sys.exit("timed out waiting for build to finish")


def main():
    opener = make_opener()
    field, crumb = fetch_crumb(opener)
    print(f"[trigger] {JOB} MODE={PARAM} (csrf={'on' if field else 'off'})")
    queue_loc = trigger(opener, field, crumb)
    print(f"[trigger] queued: {queue_loc}")
    if "queue/item" in queue_loc:
        num, build_url = wait_for_number(opener, queue_loc)
        print(f"[trigger] started build #{num}: {build_url}")
    else:
        # direct build URL was returned (rare)
        num = int(queue_loc.rstrip("/").split("/")[-1])
        build_url = queue_loc if queue_loc.endswith("/") else queue_loc + "/"
    result = wait_result(opener, build_url, num)
    print()
    print(f"[result] #{num} result={result.get('result')} duration={result.get('duration')}ms")
    sys.exit(0 if result.get("result") == "SUCCESS" else 1)


if __name__ == "__main__":
    main()
