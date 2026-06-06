#!/usr/bin/env python3
"""Create spring-batch-cleanup-job-ci and spring-batch-cleanup-job-cd on
Jenkins as "Pipeline script from SCM" jobs pointing at this repo.

Each job is identical except for the default MODE parameter (the first
choice in the list is the default in Jenkins).
"""
import os
import sys
import tempfile
import urllib.request
import urllib.error
import urllib.parse
import base64
import http.cookiejar
import json

JENKINS_URL = os.environ.get("JENKINS_URL", "http://192.168.232.128:8080/")
JENKINS_USER = os.environ["JENKINS_USER"]
JENKINS_TOKEN = os.environ["JENKINS_TOKEN"]
GIT_URL = "https://github.com/zdry146/spring-batch-cleanup-job.git"
GIT_CRED_ID = "git-cred"
BRANCH = "*/main"
SCRIPT_PATH = "jenkins/combined-pipeline-scm.groovy"

JOBS = [
    {
        "name": "spring-batch-cleanup-job-ci",
        "description": "CI: build, test, push image (MODE=ci). Pipeline from SCM.",
        "mode_choices": ["ci", "cd"],
    },
    {
        "name": "spring-batch-cleanup-job-cd",
        "description": "CD: deploy image to k8s (MODE=cd). Pipeline from SCM.",
        "mode_choices": ["cd", "ci"],
    },
]


def auth_header():
    token = base64.b64encode(f"{JENKINS_USER}:{JENKINS_TOKEN}".encode()).decode()
    return {"Authorization": f"Basic {token}"}


def make_opener():
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    opener.addheaders = list(auth_header().items())
    return opener, jar


def fetch_crumb(opener):
    req = urllib.request.Request(
        f"{JENKINS_URL}crumbIssuer/api/json",
        headers={"Accept": "application/json"},
    )
    with opener.open(req) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["crumbRequestField"], data["crumb"]


def make_config_xml(job):
    mode_choices = "".join(
        f"          <string>{c}</string>\n" for c in job["mode_choices"]
    )
    return f"""<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job@latest">
  <actions/>
  <description>{job["description"]}</description>
  <keepDependencies>false</keepDependencies>
  <properties>
    <hudson.model.ParametersDefinitionProperty>
      <parameterDefinitions>
        <hudson.model.ChoiceParameterDefinition>
          <name>MODE</name>
          <description>ci = build+test+push image, cd = deploy image to k8s</description>
          <choices class="java.util.Arrays$ArrayList">
            <a class="string-array">
{mode_choices}            </a>
          </choices>
        </hudson.model.ChoiceParameterDefinition>
        <hudson.model.ChoiceParameterDefinition>
          <name>IMAGE_TAG</name>
          <description>Image tag to deploy (CD mode only)</description>
          <choices class="java.util.Arrays$ArrayList">
            <a class="string-array">
              <string>latest</string>
              <string>1.0.0</string>
            </a>
          </choices>
        </hudson.model.ChoiceParameterDefinition>
        <hudson.model.StringParameterDefinition>
          <name>NAMESPACE</name>
          <description>Kubernetes namespace (CD mode only)</description>
          <defaultValue>batch-jobs</defaultValue>
          <trim>false</trim>
        </hudson.model.StringParameterDefinition>
      </parameterDefinitions>
    </hudson.model.ParametersDefinitionProperty>
  </properties>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps@latest">
    <scm class="hudson.plugins.git.GitSCM" plugin="git@latest">
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
    url = f"{JENKINS_URL}job/{urllib.parse.quote(name)}/config.xml"
    req = urllib.request.Request(url)
    try:
        opener.open(req)
        return True
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return False
        raise


def create_job(opener, crumb_field, crumb_value, name, config_xml):
    url = f"{JENKINS_URL}createItem?name={urllib.parse.quote(name)}"
    req = urllib.request.Request(
        url,
        data=config_xml.encode("utf-8"),
        headers={
            "Content-Type": "application/xml",
            crumb_field: crumb_value,
        },
        method="POST",
    )
    try:
        resp = opener.open(req)
        return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        return e.code, body


def main():
    opener, _ = make_opener()
    crumb_field, crumb_value = fetch_crumb(opener)
    for job in JOBS:
        name = job["name"]
        if job_exists(opener, name):
            print(f"SKIP: {name} already exists")
            continue
        status, body = create_job(opener, crumb_field, crumb_value, name, make_config_xml(job))
        if status in (200, 201):
            print(f"CREATED: {name} (HTTP {status})")
        else:
            print(f"FAILED: {name} (HTTP {status})", file=sys.stderr)
            print(body[:500], file=sys.stderr)
            sys.exit(1)


if __name__ == "__main__":
    main()
