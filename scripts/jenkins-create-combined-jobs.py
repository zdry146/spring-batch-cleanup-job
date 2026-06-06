#!/usr/bin/env python3
"""Ensure spring-batch-cleanup-job-{ci,cd,cicd} exist on Jenkins as
'Pipeline script from SCM' jobs pointing at this repo, and that their
MODE parameter offers all three choices (ci, cd, both).

Idempotent: creates the job if missing, otherwise updates the MODE
choice list to match the desired state. Re-run safely.
"""
import base64
import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

JENKINS_URL = os.environ.get("JENKINS_URL", "http://192.168.232.128:8080/")
JENKINS_USER = os.environ["JENKINS_USER"]
JENKINS_TOKEN = os.environ["JENKINS_TOKEN"]
GIT_URL = "https://github.com/zdry146/spring-batch-cleanup-job.git"
GIT_CRED_ID = "git-cred"
BRANCH = "*/main"
SCRIPT_PATH = "jenkins/combined-pipeline-scm.groovy"

# Order matters: the first choice is the default in Jenkins.
JOBS = [
    {"name": "spring-batch-cleanup-job-ci",   "description": "CI: build, test, push image. Pipeline from SCM.",            "mode_choices": ["ci",   "cd", "both"]},
    {"name": "spring-batch-cleanup-job-cd",   "description": "CD: deploy image to k8s. Pipeline from SCM.",                "mode_choices": ["cd",   "ci", "both"]},
    {"name": "spring-batch-cleanup-job-cicd", "description": "Full pipeline: CI then CD in one run. Pipeline from SCM.",   "mode_choices": ["both", "ci", "cd"]},
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


def make_config_xml(job):
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
      </parameterDefinitions>
    </hudson.model.ParametersDefinitionProperty>
  </properties>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition">
    <scm class="hudson.plugins.git.GitSCM">
      <userRemoteConfigs>
        <hudson.plugins.git.UserRemoteConfig>
          <url>{GIT_URL}</url>
          <credentialsId>{GIT_CRED_ID}</credentialsId>
        </hudson.plugins.git.UserRemoteConfig>
      </userRemoteConfigs>
      <branches>
        <hudson.plugins.git.BranchSpec>
          <name>{BRANCH}</name>
        </hudson.plugins.git.BranchSpec>
      </branches>
      <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>
      <submoduleCfg class="list"/>
      <extensions/>
    </scm>
    <scriptPath>{SCRIPT_PATH}</scriptPath>
    <lightweight>true</lightweight>
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
    """Fetch config.xml, add any missing MODE choices, POST back. Returns
    (http_status, current_choices)."""
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
        return 200, current  # already up to date

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


def main():
    opener = make_opener()
    crumb_f, crumb_v = fetch_crumb(opener)
    for job in JOBS:
        name = job["name"]
        if job_exists(opener, name):
            status, current = update_mode_choices(opener, crumb_f, crumb_v, name, job["mode_choices"])
            if status == 200:
                print(f"UPDATED: {name} (MODE = {current})")
            else:
                print(f"FAILED to update {name}: HTTP {status} {current}", file=sys.stderr)
                sys.exit(1)
        else:
            status, body = create_job(opener, crumb_f, crumb_v, name, make_config_xml(job))
            if status in (200, 201):
                print(f"CREATED: {name} (MODE default = {job['mode_choices'][0]})")
            else:
                print(f"FAILED to create {name}: HTTP {status}", file=sys.stderr)
                print(body, file=sys.stderr)
                sys.exit(1)


if __name__ == "__main__":
    main()
