#!/usr/bin/env python3
"""Ensure a Jenkins 'Secret text' credential exists with the given id and value.

Idempotent: creates the credential if missing, updates the value if it
already exists. Re-run safely. Defaults to creating 'db-password' with
the value from the DB_PASSWORD env var.

Env:
  JENKINS_URL    (default: http://localhost:8080/)
  JENKINS_USER   (required)
  JENKINS_TOKEN  (required - API token or password)
  CRED_ID        (default: db-password)
  CRED_DESC      (default: PostgreSQL password for batch jobs)
  CRED_SECRET    (required - the secret value)
"""
import base64
import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

JENKINS_URL = os.environ.get("JENKINS_URL", "http://localhost:8080/")
JENKINS_USER = os.environ["JENKINS_USER"]
JENKINS_TOKEN = os.environ["JENKINS_TOKEN"]
CRED_ID = os.environ.get("CRED_ID", "db-password")
CRED_DESC = os.environ.get("CRED_DESC", "PostgreSQL password for batch jobs")
CRED_SECRET = os.environ["CRED_SECRET"]

DOMAIN_PATH = "credentials/store/system/domain/_"


def make_opener():
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    token = base64.b64encode(f"{JENKINS_USER}:{JENKINS_TOKEN}".encode()).decode()
    opener.addheaders = [("Authorization", f"Basic {token}")]
    return opener


def fetch_crumb(opener):
    req = urllib.request.Request(
        f"{JENKINS_URL}crumbIssuer/api/json",
        headers={"Accept": "application/json"},
    )
    with opener.open(req) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["crumbRequestField"], data["crumb"]


def xml_escape(s):
    return (
        s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def credential_xml(cid, desc, secret):
    return (
        "<org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>"
        "<scope>GLOBAL</scope>"
        f"<id>{xml_escape(cid)}</id>"
        f"<description>{xml_escape(desc)}</description>"
        f"<secret>{xml_escape(secret)}</secret>"
        "</org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>"
    )


def credential_exists(opener, cid):
    url = f"{JENKINS_URL}{DOMAIN_PATH}/credential/{urllib.parse.quote(cid)}/config.xml"
    req = urllib.request.Request(url)
    try:
        opener.open(req)
        return True
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return False
        raise


def create_credential(opener, crumb_f, crumb_v, cid, desc, secret):
    url = f"{JENKINS_URL}{DOMAIN_PATH}/createCredentials"
    req = urllib.request.Request(
        url,
        data=credential_xml(cid, desc, secret).encode("utf-8"),
        headers={"Content-Type": "application/xml", crumb_f: crumb_v},
        method="POST",
    )
    try:
        resp = opener.open(req)
        return resp.status, ""
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")[:500]


def update_credential(opener, crumb_f, crumb_v, cid, desc, secret):
    url = f"{JENKINS_URL}{DOMAIN_PATH}/credential/{urllib.parse.quote(cid)}/config.xml"
    req = urllib.request.Request(
        url,
        data=credential_xml(cid, desc, secret).encode("utf-8"),
        headers={"Content-Type": "application/xml", crumb_f: crumb_v},
        method="POST",
    )
    try:
        resp = opener.open(req)
        return resp.status, ""
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")[:500]


def main():
    opener = make_opener()
    crumb_f, crumb_v = fetch_crumb(opener)
    if credential_exists(opener, CRED_ID):
        status, body = update_credential(opener, crumb_f, crumb_v, CRED_ID, CRED_DESC, CRED_SECRET)
        if status == 200:
            print(f"UPDATED: credential '{CRED_ID}'")
        else:
            print(f"FAILED to update credential '{CRED_ID}': HTTP {status}", file=sys.stderr)
            print(body, file=sys.stderr)
            sys.exit(1)
    else:
        status, body = create_credential(opener, crumb_f, crumb_v, CRED_ID, CRED_DESC, CRED_SECRET)
        if status in (200, 201):
            print(f"CREATED: credential '{CRED_ID}'")
        else:
            print(f"FAILED to create credential '{CRED_ID}': HTTP {status}", file=sys.stderr)
            print(body, file=sys.stderr)
            sys.exit(1)


if __name__ == "__main__":
    main()
