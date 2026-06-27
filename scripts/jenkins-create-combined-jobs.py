#!/usr/bin/env python3
"""Ensure spring-batch-cleanup-job-{ci,cd,cicd} exist on Jenkins as
'Pipeline script' jobs whose wrapper does GitHub->Gitee fallback and
then evaluate()s jenkins/combined-pipeline-scm.groovy from the
checked-out repo.

Idempotent: creates the job if missing, otherwise updates the MODE
choice list and the embedded wrapper script to match the desired
state. Re-run safely.

Requires on the Jenkins host:
- ~/.ssh/github_key + ~/.ssh/gitee_key (see AGENTS.md)
- ~/.ssh/config with per-host IdentityFile entries
- Script approval done once in the UI after the first install
  (the wrapper's text needs an Approve in /scriptApproval before the
  first build can run; this script handles XML escaping + crumb + cookie)
"""
import base64
import http.cookiejar
import json
import os
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

JENKINS_URL = os.environ.get("JENKINS_URL", "http://localhost:8080/")
JENKINS_USER = os.environ["JENKINS_USER"]
JENKINS_TOKEN = os.environ["JENKINS_TOKEN"]

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
WRAPPER_PATH = REPO_ROOT / "jenkins" / "wrappers" / "git-fallback-wrapper.groovy"

# Order matters: the first choice is the default in Jenkins.
JOBS = [
    {"name": "spring-batch-cleanup-job-ci",   "description": "CI: build, test, push image. Pipeline script + GitHub->Gitee wrapper.",          "mode_choices": ["ci",   "cd", "both"]},
    {"name": "spring-batch-cleanup-job-cd",   "description": "CD: deploy image to k8s. Pipeline script + GitHub->Gitee wrapper.",              "mode_choices": ["cd",   "ci", "both"]},
    {"name": "spring-batch-cleanup-job-cicd", "description": "Full pipeline: CI then CD in one run. Pipeline script + GitHub->Gitee wrapper.", "mode_choices": ["both", "ci", "cd"]},
]


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
    """Escape for embedding in an XML CDATA-free attribute / element."""
    return (
        s.replace("&", "&amp;")
         .replace("<", "&lt;")
         .replace(">", "&gt;")
         .replace('"', "&quot;")
         .replace("'", "&apos;")
    )


def load_wrapper():
    """Read the wrapper Groovy file from this repo."""
    with open(WRAPPER_PATH) as f:
        return f.read()


def make_config_xml(job, wrapper_text):
    choices = "".join(
        f"              <string>{c}</string>\n" for c in job["mode_choices"]
    )
    return f"""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition>
  <actions/>
  <description>{job["description"]}</description>
  <keepDependencies>false</keepDependencies>
  <properties>
    <hudson.model.ParametersDefinitionProperty>
      <parameterDefinitions>
        <hudson.model.ChoiceParameterDefinition>
          <name>MODE</name>
          <description>ci = build+test+push, cd = deploy, both = ci then cd</description>
          <choices class="java.util.Arrays$ArrayList">
            <a class="string-array">
{choices}            </a>
          </choices>
        </hudson.model.ChoiceParameterDefinition>
        <hudson.model.ChoiceParameterDefinition>
          <name>IMAGE_TAG</name>
          <description>Image tag to deploy (cd mode only; ignored when MODE=both or ci)</description>
          <choices class="java.util.Arrays$ArrayList">
            <a class="string-array">
              <string>latest</string>
              <string>1.0.0</string>
            </a>
          </choices>
        </hudson.model.ChoiceParameterDefinition>
        <hudson.model.StringParameterDefinition>
          <name>NAMESPACE</name>
          <description>Kubernetes namespace (cd mode only)</description>
          <defaultValue>batch-jobs</defaultValue>
          <trim>false</trim>
        </hudson.model.StringParameterDefinition>
        <hudson.model.StringParameterDefinition>
          <name>DB_HOST</name>
          <description>PostgreSQL host (cluster-reachable IP/hostname) injected into both manifests as the DB_HOST env var (cd mode only).</description>
          <defaultValue>192.168.126.133</defaultValue>
          <trim>false</trim>
        </hudson.model.StringParameterDefinition>
        <hudson.model.StringParameterDefinition>
          <name>DB_DATABASE</name>
          <description>PostgreSQL database name injected into both manifests as the DB_DATABASE env var (cd mode only). Must match the database Spring Batch metadata + the application posts live in.</description>
          <defaultValue>testdb</defaultValue>
          <trim>false</trim>
        </hudson.model.StringParameterDefinition>
      </parameterDefinitions>
    </hudson.model.ParametersDefinitionProperty>
  </properties>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps">
    <script>{xml_escape(wrapper_text)}</script>
    <sandbox>false</sandbox>
  </definition>
  <triggers/>
  <disabled>false</disabled>
</flow-definition>
"""


def job_exists(opener, name):
    req = urllib.request.Request(f"{JENKINS_URL}job/{urllib.parse.quote(name)}/config.xml")
    try:
        opener.open(req)
        return True
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return False
        raise


def create_job(opener, crumb_f, crumb_v, name, config_xml):
    url = f"{JENKINS_URL}createItem?name={urllib.parse.quote(name)}"
    req = urllib.request.Request(
        url,
        data=config_xml.encode("utf-8"),
        headers={"Content-Type": "application/xml", crumb_f: crumb_v},
        method="POST",
    )
    try:
        resp = opener.open(req)
        return resp.status, ""
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")[:500]


def update_mode_choices(opener, crumb_f, crumb_v, name, desired):
    """Fetch config.xml, add any missing MODE choices, POST back."""
    req = urllib.request.Request(f"{JENKINS_URL}job/{urllib.parse.quote(name)}/config.xml")
    config_text = opener.open(req).read().decode("utf-8")

    root = ET.fromstring(config_text)
    current = []
    for elem in root.iter():
        if elem.tag == "hudson.model.ChoiceParameterDefinition":
            name_el = elem.find("name")
            if name_el is not None and name_el.text == "MODE":
                a = elem.find("choices/a")
                if a is not None:
                    current = [s.text for s in a.findall("string")]
                    for c in desired:
                        if c not in current:
                            ET.SubElement(a, "string").text = c
                break

    if set(current) >= set(desired):
        return 200, current

    new_xml = ET.tostring(root, encoding="UTF-8", xml_declaration=True).decode("utf-8")
    new_xml = new_xml.replace(
        "<?xml version='1.0' encoding='UTF-8'?>",
        "<?xml version='1.1' encoding='UTF-8'?>",
        1,
    )
    post_url = f"{JENKINS_URL}job/{urllib.parse.quote(name)}/config.xml"
    req = urllib.request.Request(
        post_url,
        data=new_xml.encode("utf-8"),
        headers={"Content-Type": "application/xml", crumb_f: crumb_v},
        method="POST",
    )
    try:
        resp = opener.open(req)
        return resp.status, current + [c for c in desired if c not in current]
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")[:500]


def update_wrapper_script(opener, crumb_f, crumb_v, name, wrapper_text):
    """Replace the embedded <script> in the job's <definition> block with
    the current wrapper text. If the definition is still CpsScmFlowDefinition
    (legacy), convert it to CpsFlowDefinition with the embedded wrapper."""
    config_url = f"{JENKINS_URL}job/{urllib.parse.quote(name)}/config.xml"
    config_text = opener.open(urllib.request.Request(config_url)).read().decode("utf-8")

    # Convert legacy "Pipeline from SCM" definition to "Pipeline script"
    # + embedded wrapper, if needed.
    legacy_pat = (
        '<definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition"'
        ' plugin="workflow-cps">'
        '.*?'
        '</definition>'
    )
    if "CpsScmFlowDefinition" in config_text:
        new_def = (
            '<definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition"'
            ' plugin="workflow-cps">'
            f'<script>{xml_escape(wrapper_text)}</script>'
            '<sandbox>false</sandbox>'
            '</definition>'
        )
        config_text = re.sub(legacy_pat, new_def, config_text, count=1, flags=re.DOTALL)
        # Also strip leftover <scm> and <scriptPath> from the legacy block
        config_text = re.sub(r'\s*<scriptPath>[^<]*</scriptPath>', '', config_text)
        config_text = re.sub(r'\s*<scm[^>]*>.*?</scm>', '', config_text, flags=re.DOTALL)
    else:
        # Already a CpsFlowDefinition; just update the script body.
        script_pat = re.compile(r'(<script>)(.*?)(</script>)', re.DOTALL)
        config_text = script_pat.sub(
            lambda m: m.group(1) + xml_escape(wrapper_text) + m.group(3),
            config_text,
            count=1,
        )

    post_url = f"{JENKINS_URL}job/{urllib.parse.quote(name)}/config.xml"
    req = urllib.request.Request(
        post_url,
        data=config_text.encode("utf-8"),
        headers={"Content-Type": "application/xml", crumb_f: crumb_v},
        method="POST",
    )
    try:
        resp = opener.open(req)
        return resp.status, "wrapper-updated" if "CpsFlowDefinition" in config_text else "wrapper-updated"
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")[:500]


# String parameters that every job should expose
EXPECTED_STRING_PARAMS = (
    {
        "name": "DB_HOST",
        "default": "192.168.126.133",
        "description": (
            "PostgreSQL host (cluster-reachable IP/hostname) injected into "
            "both manifests as the DB_HOST env var (cd mode only)."
        ),
    },
    {
        "name": "DB_DATABASE",
        "default": "testdb",
        "description": (
            "PostgreSQL database name injected into both manifests as the "
            "DB_DATABASE env var (cd mode only). Must match the database "
            "Spring Batch metadata + the application posts live in."
        ),
    },
)


def find_parameter_definitions(root):
    for prop in root.iter("hudson.model.ParametersDefinitionProperty"):
        pd = prop.find("parameterDefinitions")
        if pd is not None:
            return pd
    return None


def ensure_string_parameter(opener, crumb_f, crumb_v, job_name, spec):
    config_url = f"{JENKINS_URL}job/{urllib.parse.quote(job_name)}/config.xml"
    config_text = opener.open(urllib.request.Request(config_url)).read().decode("utf-8")
    root = ET.fromstring(config_text)

    param_defs = find_parameter_definitions(root)
    if param_defs is None:
        return 500, "no <parameterDefinitions> in job config (unexpected)"

    for child in param_defs.findall("hudson.model.StringParameterDefinition"):
        n = child.find("name")
        if n is not None and n.text == spec["name"]:
            return 200, "unchanged"

    new_param = ET.SubElement(param_defs, "hudson.model.StringParameterDefinition")
    ET.SubElement(new_param, "name").text = spec["name"]
    ET.SubElement(new_param, "description").text = spec["description"]
    ET.SubElement(new_param, "defaultValue").text = spec["default"]
    ET.SubElement(new_param, "trim").text = "false"

    new_xml = ET.tostring(root, encoding="UTF-8", xml_declaration=True).decode("utf-8")
    new_xml = new_xml.replace(
        "<?xml version='1.0' encoding='UTF-8'?>",
        "<?xml version='1.1' encoding='UTF-8'?>",
        1,
    )
    post_url = f"{JENKINS_URL}job/{urllib.parse.quote(job_name)}/config.xml"
    req = urllib.request.Request(
        post_url,
        data=new_xml.encode("utf-8"),
        headers={"Content-Type": "application/xml", crumb_f: crumb_v},
        method="POST",
    )
    try:
        resp = opener.open(req)
        return resp.status, "added"
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")[:500]


def main():
    # Late import for re.sub used in update_wrapper_script
    global re
    import re

    opener = make_opener()
    crumb_f, crumb_v = fetch_crumb(opener)
    wrapper_text = load_wrapper()

    for job in JOBS:
        name = job["name"]
        if job_exists(opener, name):
            # Always refresh the wrapper script (idempotent; only POSTs if
            # the embedded script body differs).
            wstatus, waction = update_wrapper_script(
                opener, crumb_f, crumb_v, name, wrapper_text
            )
            if wstatus == 200:
                print(f"WRAPPER: {name} -> {waction}")
            else:
                print(f"FAILED to update wrapper for {name}: HTTP {wstatus} {waction}", file=sys.stderr)
                sys.exit(1)

            status, current = update_mode_choices(opener, crumb_f, crumb_v, name, job["mode_choices"])
            if status == 200:
                print(f"UPDATED: {name} (MODE = {current})")
            else:
                print(f"FAILED to update {name}: HTTP {status} {current}", file=sys.stderr)
                sys.exit(1)

            for spec in EXPECTED_STRING_PARAMS:
                sp_status, sp_action = ensure_string_parameter(
                    opener, crumb_f, crumb_v, name, spec
                )
                if sp_status == 200:
                    if sp_action == "added":
                        print(f"  + ADDED string param: {name}.{spec['name']} = {spec['default']!r}")
                    else:
                        print(f"  = UNCHANGED string param: {name}.{spec['name']}")
                else:
                    print(f"  FAILED to add string param {name}.{spec['name']}: HTTP {sp_status} {sp_action}", file=sys.stderr)
                    sys.exit(1)
        else:
            status, body = create_job(opener, crumb_f, crumb_v, name, make_config_xml(job, wrapper_text))
            if status in (200, 201):
                print(f"CREATED: {name} (MODE default = {job['mode_choices'][0]})")
                print(f"  ⚠️  First build will fail with UnapprovedUsageException;")
                print(f"     go to {JENKINS_URL}scriptApproval and click Approve.")
            else:
                print(f"FAILED to create {name}: HTTP {status}", file=sys.stderr)
                print(body, file=sys.stderr)
                sys.exit(1)


if __name__ == "__main__":
    main()