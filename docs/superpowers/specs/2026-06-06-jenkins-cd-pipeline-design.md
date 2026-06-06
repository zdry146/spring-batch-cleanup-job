# Jenkins CD Pipeline for Spring Batch Cleanup Job

**Date:** 2026-06-06
**Status:** Approved (brainstorming)
**Author:** (assistant)

## Goal

Add a Jenkins CD pipeline that pulls the Spring Batch cleanup image from the
Aliyun container registry and deploys it to the local minikube cluster.

## Context

The repo currently has:

- `spring-batch-cleanup-job` Jenkins pipeline (inline script) that runs on
  `http://192.168.232.128:8080/`, builds the project with Maven, runs tests,
  builds a Docker image, and pushes two tags to Aliyun:
  - `crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com/mike-docker-registry/spring-batch-cleanup-job:${IMAGE_VERSION}`
  - `crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com/mike-docker-registry/spring-batch-cleanup-job:latest`
  where `IMAGE_VERSION` is the Maven `project.version` (currently `1.0.0`).
  The pipeline uses the Jenkins credentials `aliyun-docker-login` for the
  registry login and `git-cred` for the GitHub checkout.

- k8s manifests in `k8s/`:
  - `namespace.yaml` — namespace `batch-jobs`
  - `secret.yaml` — `db-credentials` opaque secret
  - `cronjob.yaml` — `cleanup-cron` (daily at midnight), image `cleanup-batch:1.0.0`
  - `job.yaml` — `cleanup-manual`, image `cleanup-batch:1.0.0`

  Both workload manifests currently use the local-only image
  `cleanup-batch:1.0.0` with `imagePullPolicy: IfNotPresent` and have no
  `imagePullSecrets`. They are not yet wired to the Aliyun registry.

- Jenkins runs in a Docker container on the same Linux host as minikube.
  The container currently has these binds:
  - `/home/openclaw/jenkins_home` -> `/var/jenkins_home`
  - `/var/run/docker.sock` -> `/var/run/docker.sock`
  - `/usr/bin/docker` -> `/usr/bin/docker`

- The host has `kubectl` v1.22.17 at `/usr/local/bin/kubectl` and a working
  kubeconfig at `/root/.kube/config` whose current context is
  `kubernetes-admin@minikube`. Minikube may be stopped at any time; the user
  will start it before deploying.

## Architecture

```
+----------------------+      +----------------------+
| spring-batch-        | push | Aliyun container     |
| cleanup-job (build)  +----->| registry             |
+----------------------+      +-----------+----------+
                                           |
                                           | pull
                                           v
                              +------------+----------+
                              | spring-batch-         |
                              | cleanup-job-deploy    |
                              | (new Jenkins job)     |
                              +------------+----------+
                                           |
                                           | kubectl apply
                                           v
                              +------------+----------+
                              | minikube (local host) |
                              +-----------------------+
```

Two Jenkins jobs on the same server, triggered independently:

- **Build job** (`spring-batch-cleanup-job`, existing): builds, tests, pushes
  the image. Unchanged.
- **Deploy job** (`spring-batch-cleanup-job-deploy`, new): pulls the chosen
  tag from Aliyun and deploys it to minikube. Triggered manually.

## Manifest changes (one-time, committed to the repo)

In **`k8s/cronjob.yaml`** and **`k8s/job.yaml`** (apply the same diff to both):

- Replace `image: cleanup-batch:1.0.0` with
  `image: crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com/mike-docker-registry/spring-batch-cleanup-job:latest`.
- Change `imagePullPolicy: IfNotPresent` to `Always` (so remote updates are
  picked up regardless of tag).
- Add to the pod spec:

  ```yaml
  imagePullSecrets:
    - name: aliyun-registry-cred
  ```

No new manifest file is required for the image pull secret. The deploy job
creates/updates it on every run with:

```bash
kubectl -n ${params.NAMESPACE} create secret docker-registry aliyun-registry-cred \
    --docker-server=${env.ALIYUN_REGISTRY} \
    --docker-username=${ALIYUN_DOCKER_CREDS_USR} \
    --docker-password=${ALIYUN_DOCKER_CREDS_PSW} \
    --dry-run=client -o yaml | kubectl apply -f -
```

This is idempotent and picks up the `aliyun-docker-login` Jenkins credential
without committing any secret material to the repo.

## Deploy job (declarative pipeline)

A new Jenkins pipeline job `spring-batch-cleanup-job-deploy` with the
following inline script:

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

## Image update strategy

Manifests are updated **once** to reference the full Aliyun registry path
with tag `latest`. Subsequent deploys change the live image via
`kubectl set image` and do not mutate the yaml files in the repo. This
keeps the repo as the source of truth for "what should be deployed by
default" while letting the deploy job pin any specific tag (e.g. `1.0.0`)
without leaving drift in git.

## Operational prerequisites (run once on the host)

1. **Mount kubectl, kubeconfig, and minikube certs into the Jenkins
   container.** The container currently binds `/usr/bin/docker`, the docker
   socket, and `/home/openclaw/jenkins_home`. Add:

   ```bash
   docker stop jenkins
   docker run -d --name jenkins --restart=always \
       -p 8080:8080 \
       -v /home/openclaw/jenkins_home:/var/jenkins_home \
       -v /var/run/docker.sock:/var/run/docker.sock \
       -v /usr/bin/docker:/usr/bin/docker \
       -v /usr/local/bin/kubectl:/usr/local/bin/kubectl:ro \
       -v /root/.kube:/root/.kube:ro \
       -v /root/.minikube:/root/.minikube:ro \
       jenkins/jenkins:lts
   ```

   Adjust flags to match the existing run command; only the three new binds
   are the delta. Read-only mounts limit blast radius.

2. **Start minikube** before running the deploy job:
   `minikube start`. The deploy job assumes minikube is up; if it is not,
   `kubectl` fails fast with a clear error.

3. **Jenkins credentials.** `aliyun-docker-login` and `git-cred` already
   exist (used by the build job). No new credentials required.

4. **kubectl client/server skew.** Host `kubectl` is v1.22.17. The minikube
   cluster is likely newer. kubectl tolerates a one-minor-version skew; if
   the warning becomes a hard error after a minikube upgrade, the host
   `kubectl` should be updated (out of scope for this spec).

## Error handling

- **kubectl/apply fails** (e.g. cluster unreachable, minikube down): the
  pipeline stage fails, no rollback is performed. The user can re-run with
  the previous `IMAGE_TAG` to converge.
- **Image pull fails** (e.g. credentials wrong, tag missing in Aliyun): the
  workload pod will sit in `ImagePullBackOff`. The verify stage surfaces
  `kubectl get cronjob,job` so the issue is visible, and a follow-up
  `kubectl describe pod` (run manually or added later) shows the pull error.
- **Minikube not running**: `kubectl` exits with a connection-refused error
  in the first stage that touches the cluster, the pipeline fails, the
  `post.failure` block prints `kubectl get all` for context.
- **Image pull secret already exists** with different content: the
  `kubectl apply -f -` path will update it in place (idempotent).
- **`kubectl set image job/cleanup-manual` fails because the Job is
  immutable** (already started or completed). The deploy job's set-image
  stage treats this case as a warning, prints the error, and continues.
  The user can `kubectl delete job cleanup-manual -n batch-jobs` before
  re-running the deploy with a new tag if they want the change to take
  effect on a fresh run.

## Testing

The pipeline itself is declarative and has no code unit tests. Manual and
end-to-end tests:

1. **Dry-run on a clean cluster.** Stop minikube, start it (`minikube start`),
   then run the deploy job with `IMAGE_TAG=latest`. Confirm the
   `batch-jobs` namespace, `db-credentials` secret, `aliyun-registry-cred`
   secret, `cleanup-cron` CronJob, and `cleanup-manual` Job are all created
   with the correct image and pull secret. Verify stage output should show
   the full Aliyun image path with `:latest`.
2. **CronJob fires and pulls the image.** Wait for the next scheduled run
   (or temporarily edit the schedule to `* * * * *` for the test), then
   `kubectl get pods -n batch-jobs` and confirm the pod reaches `Running`
   and the Spring Batch job completes.
3. **Manual job runs.** `kubectl create job -n batch-jobs manual-test-1
   --from=job/cleanup-manual`, then `kubectl logs -n batch-jobs
   job/manual-test-1` and confirm soft-deletes happen.
4. **Rollback by re-tagging.** Run deploy with `IMAGE_TAG=1.0.0`, confirm
   the verify stage shows the `:1.0.0` image, then re-run with
   `IMAGE_TAG=latest` and confirm the roll-forward.

## Out of scope

- Auto-trigger of the deploy job by the build job (the user requested a
  separate, manually triggered job).
- Rolling restart of pods when only the image tag changes (the
  `kubectl set image` call updates the manifest; the user can `kubectl
  delete pod` to force a refresh, or wait for the CronJob to fire).
- Promotion of the deploy job to a Jenkins Shared Library.
- Updating the host `kubectl` binary to match a future minikube k8s version.
- HTTPS/TLS for the Jenkins UI (assumed already in place or out of scope).
