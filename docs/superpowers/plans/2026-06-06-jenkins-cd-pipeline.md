# Jenkins CD Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Jenkins CD pipeline (`spring-batch-cleanup-job-deploy`) that pulls the Spring Batch image from the Aliyun container registry and deploys it to the local minikube cluster.

**Architecture:** New declarative Jenkins pipeline that checks out the repo, ensures an `aliyun-registry-cred` image-pull secret exists in the target namespace, applies the updated k8s manifests, then runs `kubectl set image` to pin the chosen tag. Manifests are updated once to reference the full Aliyun registry path; the pipeline changes the live image without mutating the repo. The Jenkins container is rebound with the host `kubectl` binary, `~/.kube`, and `~/.minikube` so the pipeline can talk to minikube.

**Tech Stack:** Jenkins (declarative pipeline), Docker (Jenkins container), kubectl, minikube, Kubernetes, Aliyun container registry (`crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com`).

**Spec:** `docs/superpowers/specs/2026-06-06-jenkins-cd-pipeline-design.md`

---

## File Structure

| File | Action | Purpose |
| --- | --- | --- |
| `k8s/cronjob.yaml` | Modify | Switch image to Aliyun registry, set `imagePullPolicy: Always`, add `imagePullSecrets` |
| `k8s/job.yaml` | Modify | Same diff as cronjob.yaml |
| `jenkins/deploy-pipeline.groovy` | Create | Source-of-truth pipeline script for the new Jenkins job |
| `docs/superpowers/plans/2026-06-06-jenkins-cd-pipeline.md` | Create (this file) | Implementation plan |
| Jenkins job `spring-batch-cleanup-job-deploy` | Create in UI | Pipeline job that runs the groovy script above |

The pipeline script lives in the repo (versioned, diffable) and is pasted into the Jenkins job's "Pipeline script" field. This mirrors how the existing build job is configured.

---

## Task 1: Update `k8s/cronjob.yaml`

**Files:**
- Modify: `k8s/cronjob.yaml`

- [ ] **Step 1: Edit the file**

Open `k8s/cronjob.yaml` and apply these three changes inside the container spec (the only `containers:` entry, named `cleanup-batch`):

Change 1 — replace the image:
```yaml
            image: crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com/mike-docker-registry/spring-batch-cleanup-job:latest
```

Change 2 — replace the image pull policy (line currently reads `imagePullPolicy: IfNotPresent`):
```yaml
            imagePullPolicy: Always
```

Change 3 — add `imagePullSecrets` to the **pod spec** as a sibling of `containers:` and `restartPolicy:` (NOT inside the container — `imagePullSecrets` is a pod-level field in Kubernetes):
```yaml
          restartPolicy: OnFailure
          imagePullSecrets:
            - name: aliyun-registry-cred
          containers:
```

The resulting pod-spec block should look like:
```yaml
        spec:
          restartPolicy: OnFailure
          imagePullSecrets:
            - name: aliyun-registry-cred
          containers:
          - name: cleanup-batch
            image: crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com/mike-docker-registry/spring-batch-cleanup-job:latest
            imagePullPolicy: Always
            env:
            - name: DB_HOST
              value: "192.168.232.128"
            - name: DB_USERNAME
              value: "postgres"
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: db-credentials
                  key: password
```

(Indentation in the file is 10 spaces for `containers:` items and 10 spaces for the new `imagePullSecrets` key as a sibling of `containers:`. Preserve that exactly.)

- [ ] **Step 2: Validate the file**

Run from the repo root:
```bash
kubectl apply --dry-run=client --validate=true -f k8s/cronjob.yaml
```
Expected output (exact text may differ by kubectl version, but the kind/name should match):
```
cronjob.batch/cleanup-cron created (dry run)
```
Or:
```
cronjob.batch/cleanup-cron configured (dry run)
```

If you see a YAML parse error, the indentation in step 1 is wrong — re-check the file with `cat -A k8s/cronjob.yaml` to spot mixed tabs/spaces.

- [ ] **Step 3: Commit**

```bash
git add k8s/cronjob.yaml
git commit -m "k8s/cronjob: pull image from Aliyun registry with imagePullSecret"
```

---

## Task 2: Update `k8s/job.yaml`

**Files:**
- Modify: `k8s/job.yaml`

- [ ] **Step 1: Edit the file**

Apply the same three changes to `k8s/job.yaml`. The resulting pod-spec block should look like:

```yaml
      spec:
        restartPolicy: OnFailure
        imagePullSecrets:
          - name: aliyun-registry-cred
        containers:
        - name: cleanup-batch
          image: crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com/mike-docker-registry/spring-batch-cleanup-job:latest
          imagePullPolicy: Always
          env:
          # Database configuration
          - name: DB_HOST
            value: "192.168.232.128"
          - name: DB_USERNAME
            value: "postgres"
          - name: DB_PASSWORD
            valueFrom:
              secretKeyRef:
                name: db-credentials
                key: password

          # Error injection for testing (default: no error)
          # Set ERROR_INJECTION_STEP1 true to inject error in Step 1
          # Set ERROR_INJECTION_STEP2 true to inject error in Step 2
          - name: ERROR_INJECTION_STEP1
            value: "false"
          - name: ERROR_INJECTION_STEP2
            value: "false"
          # ERROR_TYPE: PERMANENT (fails after retries) or TRANSIENT (recovers on retry)
          - name: ERROR_TYPE
            value: "PERMANENT"
```

(Indentation in `k8s/job.yaml` is 8 spaces for `containers:` items and 8 spaces for the new `imagePullSecrets` key as a sibling of `containers:`. Preserve that exactly.)

- [ ] **Step 2: Validate the file**

```bash
kubectl apply --dry-run=client --validate=true -f k8s/job.yaml
```
Expected output:
```
job.batch/cleanup-manual created (dry run)
```

- [ ] **Step 3: Commit**

```bash
git add k8s/job.yaml
git commit -m "k8s/job: pull image from Aliyun registry with imagePullSecret"
```

---

## Task 3: Add the deploy pipeline script to the repo

**Files:**
- Create: `jenkins/deploy-pipeline.groovy`

- [ ] **Step 1: Create the directory**

```bash
mkdir -p jenkins
```

- [ ] **Step 2: Write the pipeline script**

Create `jenkins/deploy-pipeline.groovy` with exactly the following content:

```groovy
pipeline {
    agent any
    parameters {
        choice(
            name: 'IMAGE_TAG',
            choices: ['latest', '1.0.0'],
            description: 'Image tag to deploy (matches what the build job pushes)'
        )
        string(
            name: 'NAMESPACE',
            defaultValue: 'batch-jobs',
            description: 'Kubernetes namespace'
        )
    }
    environment {
        ALIYUN_REGISTRY  = 'crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com'
        ALIYUN_NAMESPACE = 'mike-docker-registry'
        ALIYUN_IMAGE     = 'spring-batch-cleanup-job'
        FULL_IMAGE       = "${env.ALIYUN_REGISTRY}/${env.ALIYUN_NAMESPACE}/${env.ALIYUN_IMAGE}"
        ALIYUN_DOCKER_CREDS = credentials('aliyun-docker-login')
    }
    stages {
        stage('Checkout') {
            steps {
                dir('spring-batch-cleanup-job') {
                    git branch: 'main',
                        url: 'https://github.com/zdry146/spring-batch-cleanup-job.git',
                        credentialsId: 'git-cred'
                }
            }
        }
        stage('Ensure image pull secret') {
            steps {
                sh """
                set -euo pipefail
                kubectl -n ${params.NAMESPACE} create secret docker-registry aliyun-registry-cred \\
                    --docker-server=${env.ALIYUN_REGISTRY} \\
                    --docker-username=\${ALIYUN_DOCKER_CREDS_USR} \\
                    --docker-password=\${ALIYUN_DOCKER_CREDS_PSW} \\
                    --dry-run=client -o yaml | kubectl apply -f -
                """
            }
        }
        stage('Apply manifests') {
            steps {
                dir('spring-batch-cleanup-job') {
                    sh """
                    kubectl apply -f k8s/namespace.yaml
                    kubectl apply -f k8s/secret.yaml
                    kubectl apply -f k8s/cronjob.yaml
                    kubectl apply -f k8s/job.yaml
                    """
                }
            }
        }
        stage('Set image tag') {
            steps {
                sh """
                set +e
                kubectl -n ${params.NAMESPACE} set image \\
                    cronjob/cleanup-cron cleanup-batch=${env.FULL_IMAGE}:${params.IMAGE_TAG}
                rc=\$?
                if [ \$rc -ne 0 ]; then
                    echo "WARN: failed to set image on cronjob/cleanup-cron (rc=\$rc)"
                fi
                kubectl -n ${params.NAMESPACE} set image \\
                    job/cleanup-manual cleanup-batch=${env.FULL_IMAGE}:${params.IMAGE_TAG}
                rc=\$?
                set -e
                if [ \$rc -ne 0 ]; then
                    echo "WARN: failed to set image on job/cleanup-manual (rc=\$rc). \\
                          The Job may be immutable after completion. \\
                          Delete it with: kubectl -n ${params.NAMESPACE} delete job cleanup-manual"
                fi
                """
            }
        }
        stage('Verify') {
            steps {
                sh "kubectl -n ${params.NAMESPACE} get cronjob,job -o wide"
                sh """
                echo "CronJob image:"
                kubectl -n ${params.NAMESPACE} get cronjob cleanup-cron \\
                    -o jsonpath='{.spec.jobTemplate.spec.template.spec.containers[0].image}{\\"\\n\\"}'
                echo "Job image:"
                kubectl -n ${params.NAMESPACE} get job cleanup-manual \\
                    -o jsonpath='{.spec.template.spec.containers[0].image}{\\"\\n\\"}'
                """
            }
        }
    }
    post {
        failure {
            echo "Deploy failed. Cluster state:"
            sh "kubectl -n ${params.NAMESPACE} get all || true"
        }
    }
}
```

- [ ] **Step 3: Sanity-check the file**

```bash
wc -l jenkins/deploy-pipeline.groovy
```
Expected: roughly 90 lines. If the line count is wildly off (e.g. <70 or >110), something is wrong — re-paste from this plan.

Verify the credential ID is exactly `aliyun-docker-login` and the GitHub URL is `https://github.com/zdry146/spring-batch-cleanup-job.git` (matching the existing build job).

- [ ] **Step 4: Commit**

```bash
git add jenkins/deploy-pipeline.groovy
git commit -m "jenkins: add deploy pipeline script (Aliyun -> minikube)"
```

---

## Task 4: Rebind the Jenkins container with kubectl, kubeconfig, and minikube certs

**Files:** None (operational step on the Jenkins host).

- [ ] **Step 1: Inspect the current `docker run` command**

```bash
docker inspect jenkins --format '{{.Config.Cmd}} {{.Config.Entrypoint}}' 2>/dev/null
docker inspect jenkins --format '{{json .HostConfig.Binds}}' 2>/dev/null
```

Note the existing binds. You will reuse every flag, adding three new binds.

- [ ] **Step 2: Stop the Jenkins container**

```bash
docker stop jenkins
```

- [ ] **Step 3: Remove the stopped container (so the new `docker run` can reuse the name)**

```bash
docker rm jenkins
```

- [ ] **Step 4: Start a new Jenkins container with the additional binds**

Recreate the container with the same flags it had, plus three new binds. The new binds are:

```bash
-v /usr/local/bin/kubectl:/usr/local/bin/kubectl:ro
-v /home/openclaw/.kube:/home/openclaw/.kube:ro
-v /home/openclaw/.minikube:/home/openclaw/.minikube:ro
```

**Why `/home/openclaw` and not `/root`:** the jenkins user inside the
container is uid 1000. `/root/.kube` is mode 0700 owned by root, so the
jenkins user cannot read it. `/home/openclaw/.kube` and
`/home/openclaw/.minikube` are owned by openclaw (uid 1000) and are
readable by the container's jenkins user.

Example full command (adjust to match your existing flags — keep the docker socket and `/home/openclaw/jenkins_home` binds):

```bash
docker run -d --name jenkins --restart=always \
  -p 8080:8080 \
  -p 50000:50000 \
  -v /home/openclaw/jenkins_home:/var/jenkins_home \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /usr/bin/docker:/usr/bin/docker \
  -v /usr/local/bin/kubectl:/usr/local/bin/kubectl:ro \
  -v /home/openclaw/.kube:/home/openclaw/.kube:ro \
  -v /home/openclaw/.minikube:/home/openclaw/.minikube:ro \
  jenkins/jenkins:lts
```

- [ ] **Step 5: Verify the binds are present**

```bash
docker inspect jenkins --format '{{json .HostConfig.Binds}}' | python3 -m json.tool
```

Expected: the list includes `kubectl`, `/home/openclaw/.kube`, and `/home/openclaw/.minikube` binds with `:ro` mode.

- [ ] **Step 6: Verify kubectl is callable inside the container**

```bash
docker exec jenkins kubectl version --client
```

Expected: prints a `Client Version:` line. If the command is not found, the bind target is wrong (most likely `/usr/local/bin` is not on PATH inside the container — fall back to `docker exec jenins /usr/local/bin/kubectl version --client` to confirm the binary is reachable, then adjust the bind to a directory in PATH such as `/usr/bin/kubectl` if needed).

- [ ] **Step 7: Verify Jenkins is back up**

Open `http://192.168.232.128:8080/` in a browser and log in. Confirm the existing `spring-batch-cleanup-job` job is still listed (the `$JENKINS_HOME` volume preserves it).

---

## Task 5: Start minikube

**Files:** None (operational step).

- [ ] **Step 1: Confirm the minikube config exists**

```bash
ls /home/openclaw/.kube/config /home/openclaw/.minikube 2>&1 | head
```

Expected: both paths exist. If `/home/openclaw/.minikube` is missing, this host never had a working minikube; stop and re-check with the user before continuing.

- [ ] **Step 2: Start minikube**

```bash
minikube start
```

Expected: ends with `Done! kubectl is now configured to use the "minikube" cluster and the "default" namespace.` (or `batch-jobs` if a profile default is set). Startup can take 1–3 minutes the first time and seconds on subsequent starts.

- [ ] **Step 3: Verify the cluster is reachable from the host**

```bash
kubectl get nodes
```

Expected: one node listed, status `Ready`.

- [ ] **Step 4: Verify the cluster is reachable from inside the Jenkins container**

```bash
docker exec jenkins kubectl get nodes
```

Expected: same node list as step 3. If this fails, the kubeconfig bind from Task 4 is wrong (e.g. wrong source path).

---

## Task 6: Create the `spring-batch-cleanup-job-deploy` Jenkins job

**Files:** None (UI step).

- [ ] **Step 1: Open the Jenkins UI**

Go to `http://192.168.232.128:8080/`, log in as `admin`.

- [ ] **Step 2: Create a new item**

Click **New Item** (left sidebar). Enter the name `spring-batch-cleanup-job-deploy`. Select **Pipeline**. Click **OK**.

- [ ] **Step 3: Configure the job**

On the configuration page:

- **Description:** `Pulls the Spring Batch cleanup image from the Aliyun registry and deploys it to minikube.`
- **Build Triggers:** leave all unchecked (manual only).
- **Pipeline:**
  - **Definition:** `Pipeline script`
  - **Script:** paste the entire contents of `jenkins/deploy-pipeline.groovy` (printed by `cat jenkins/deploy-pipeline.groovy` on the host).

- [ ] **Step 4: Save**

Click **Save** at the bottom.

- [ ] **Step 5: Verify the job appears**

The job should be visible at `http://192.168.232.128:8080/job/spring-batch-cleanup-job-deploy/`. Click into it; the **Build with Parameters** button should be present.

---

## Task 7: Run the deploy job with `IMAGE_TAG=latest` (smoke test)

**Files:** None (UI step).

- [ ] **Step 1: Build with parameters**

On the job page, click **Build with Parameters**. Leave `IMAGE_TAG=latest`, `NAMESPACE=batch-jobs`. Click **Build**.

- [ ] **Step 2: Watch the console output**

Click the build number (e.g. `#1`) then **Console Output**. Expected per stage:

- **Checkout:** `git` output, ends with `HEAD is now at ...`.
- **Ensure image pull secret:** ends with `secret/aliyun-registry-cred configured` (or `created` on first run).
- **Apply manifests:** four lines, each `namespace/batch-jobs unchanged`, `secret/db-credentials unchanged`, `cronjob.batch/cleanup-cron unchanged`, `job.batch/cleanup-manual unchanged` (or `created`/`configured`).
- **Set image tag:** two lines, each `cronjob.batch/cleanup-cron image updated` and `job.batch/cleanup-manual image updated` (or `WARN: ...` for the manual job on first run if it doesn't exist yet — that's fine).
- **Verify:** prints the cronjob + job list, then two image lines that read `crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com/mike-docker-registry/spring-batch-cleanup-job:latest` (CronJob) and `:1.0.0` or `:latest` for the manual Job (whichever is in the yaml — it should now be `:latest`).

- [ ] **Step 3: Verify the image pull secret in the cluster**

```bash
kubectl -n batch-jobs get secret aliyun-registry-cred
kubectl -n batch-jobs get cronjob cleanup-cron -o jsonpath='{.spec.jobTemplate.spec.template.spec.containers[0].image}{\"\n\"}'
kubectl -n batch-jobs get job cleanup-manual -o jsonpath='{.spec.template.spec.containers[0].image}{\"\n\"}'
```

Expected: the secret exists, both images end with `:latest` and the full Aliyun path.

---

## Task 8: Verify the CronJob can pull and run

**Files:** None (operational step).

- [ ] **Step 1: Force a CronJob run for testing**

Edit `k8s/cronjob.yaml` temporarily to set the schedule to `* * * * *` (every minute), commit it, and run the deploy job again. **Do not commit the schedule change** — revert it after the test.

Alternative (no commit): just wait for the next scheduled run, or scale the test by running `kubectl create job -n batch-jobs manual-from-cron --from=cronjob/cleanup-cron`.

- [ ] **Step 2: Watch the pod start**

```bash
kubectl -n batch-jobs get pods -w
```

Expected within 1–2 minutes: a pod appears, transitions to `Running`, then `Completed` (or stays `Running` while the Spring Batch job executes).

- [ ] **Step 3: Confirm the pod pulled from Aliyun**

```bash
kubectl -n batch-jobs describe pod <pod-name> | grep -A2 'Events:\|Image:'
```

Expected: `Image: crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com/mike-docker-registry/spring-batch-cleanup-job:latest` and an event line `Pulling image "crpi-e2h2rfj3kunrwe5n..."` followed by `Successfully pulled image`.

- [ ] **Step 4: Check job logs**

```bash
kubectl -n batch-jobs logs <pod-name>
```

Expected: Spring Batch startup banner, then the two step logs (`cleanupStep` and `processDeletedPostsStep`) ending in `BUILD SUCCESS`-equivalent Spring Batch output (or whatever the existing logs look like for a successful run — see `scripts/run-and-verify.sh` for the expected end state).

- [ ] **Step 5: Revert the schedule change**

If you changed `k8s/cronjob.yaml`'s schedule, revert it to `0 0 * * *` and commit:

```bash
git checkout -- k8s/cronjob.yaml
# or: edit the file back to "0 0 * * *"
git add k8s/cronjob.yaml
git commit -m "k8s/cronjob: revert schedule to daily midnight after test"
```

Run the deploy job once more to re-apply the reverted manifest:

```bash
# In Jenkins UI: Build with Parameters, IMAGE_TAG=latest
```

---

## Task 9: Verify the manual job can be triggered

**Files:** None (operational step).

- [ ] **Step 1: Create a fresh Job from the manual template**

```bash
kubectl -n batch-jobs create job manual-test-1 --from=job/cleanup-manual
```

Expected: `job.batch/manual-test-1 created`.

- [ ] **Step 2: Watch the new job run**

```bash
kubectl -n batch-jobs get pods -w
```

Expected: a pod is created by the job, transitions to `Running`, then `Completed`.

- [ ] **Step 3: Confirm the soft-delete behavior**

```bash
kubectl -n batch-jobs logs -l job-name=manual-test-1
```

Expected: the same Spring Batch output as Task 8, step 4, with no errors.

If your database has a way to inspect the `posts` table (psql, adminer, etc.), confirm that any unpublished posts older than 30 days are now soft-deleted (e.g. `deleted_at` is set). This is the existing behavior verified by `scripts/run-and-verify.sh`.

---

## Task 10: Verify rollback by re-tagging

**Files:** None (UI step).

- [ ] **Step 1: Deploy with `IMAGE_TAG=1.0.0`**

In Jenkins: **Build with Parameters**, set `IMAGE_TAG=1.0.0`, build.

- [ ] **Step 2: Confirm the image is now `:1.0.0`**

```bash
kubectl -n batch-jobs get cronjob cleanup-cron -o jsonpath='{.spec.jobTemplate.spec.template.spec.containers[0].image}{\"\n\"}'
```

Expected: `crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com/mike-docker-registry/spring-batch-cleanup-job:1.0.0`

- [ ] **Step 3: Re-deploy with `IMAGE_TAG=latest`**

In Jenkins: **Build with Parameters**, set `IMAGE_TAG=latest`, build.

- [ ] **Step 4: Confirm the image is back to `:latest`**

Same command as step 2. Expected: ends with `:latest`.

- [ ] **Step 5: Final commit summary**

```bash
git log --oneline -5
```

Expected: the three commits from Tasks 1, 2, 3 at the top, ahead of the previous tip.

---

## Self-Review Notes

- **Spec coverage:**
  - Manifest changes → Tasks 1, 2 ✓
  - Deploy job script → Task 3 ✓
  - Operational prereqs (kubectl mount, minikube start) → Tasks 4, 5 ✓
  - Jenkins job creation → Task 6 ✓
  - Smoke test (deploy with latest) → Task 7 ✓
  - CronJob pulls + runs → Task 8 ✓
  - Manual job runs → Task 9 ✓
  - Rollback test → Task 10 ✓
  - All four "Testing" items from the spec are covered.
- **Placeholder scan:** no TBDs, no "similar to" hand-waves; every shell command, every yaml edit, and every UI click is spelled out.
- **Type / identifier consistency:** `aliyun-registry-cred`, `batch-jobs`, `cleanup-cron`, `cleanup-manual`, `git-cred`, `aliyun-docker-login`, `spring-batch-cleanup-job-deploy` are used identically across all tasks and match the spec.
