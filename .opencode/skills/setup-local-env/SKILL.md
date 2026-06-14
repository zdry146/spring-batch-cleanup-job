---
name: setup-local-env
description: Use when the user has cloned this repo, says "set up local env", "configure for local run", "help me run the e2e tests", "I just got this code, what do I do", or wants to run scripts/run-and-verify.sh / scripts/test-cicd-e2e.sh for the first time. Also use when the user is on a new machine, hit a connection / authentication / namespace error, or asks how to point the project at a different PostgreSQL server.
---

# Setup local env for spring-batch-cleanup-job

Interactive walkthrough that takes a fresh clone from "I have the code" to "the local-Docker e2e tests pass" by collecting host-specific values, then delegating to the project's own scripts.

## Overview

The project's environment variables live in `.env` (gitignored, copied from `.env.example`). Most knobs are overrideable from the shell, so a new machine rarely needs a code change. Your job is to gather values from the user, put the non-sensitive ones in `.env` for them, and tell them to put the sensitive one in themselves. Then verify with a smoke test.

**Never type a password the user gives you into a file. Never echo it back. Never write it to .env on their behalf.**

## When to Use

User just cloned the repo and wants to run the project / e2e tests on their machine, OR user is on a new machine and existing scripts fail with connection / auth / "host not found" errors, OR user explicitly asks for "local setup", "dev env", "first-time setup", "make the e2e work on my box".

Do NOT use for: Jenkins-side setup (handled by the `jenkins-docker` skill), production cluster changes, or in-cluster debugging.

## Workflow

1. **Confirm they're starting from a clean checkout.** Working tree should be clean and on `main`. If not, stop and ask.

2. **Check the file `.env` exists and is gitignored.** If `.env` is missing, copy from `.env.example` and tell the user: "`cp .env.example .env && chmod 600 .env`". Verify with `grep -q '^\.env$' .gitignore` and surface a warning if not.

3. **Ask for non-sensitive values, one at a time, in this order.** Use the question tool, not free-form text — the user is more likely to give the right value when they see the options.

   - **`DB_HOST`** — the IP/hostname of their PostgreSQL server, reachable from BOTH the host AND from inside a k8s pod on the cluster. Default they should use if unsure: the IP of the machine they're sitting on. Free-form, no options.
   - **`DB_DATABASE`** — name of the database. Default suggestion: `testdb` (matches the placeholder in `application.yml`).
   - **`DB_USERNAME`** — usually `postgres`.
   - **`DB_USERNAME` default?** — if they're using the dev `postgres` superuser, no further question. If they want a dedicated user, ask which.

4. **Tell them to fill `.env` themselves.** After they answer, output a block like:
   ```
   Please edit .env (do not paste these into chat):
     DB_HOST=<the value they gave>
     DB_DATABASE=<the value>
     DB_USERNAME=<the value>
     DB_PASSWORD=<your-real-password>   ← you fill this in
   ```
   Do not write the file yourself unless the user explicitly says "go ahead and write it" AND `.env` doesn't yet exist. If they confirm, write only DB_HOST, DB_DATABASE, DB_USERNAME; leave DB_PASSWORD as the literal `replace-with-the-real-postgres-password` from the example.

5. **For DB_PASSWORD, hand it to the user.** Single sentence: "Set DB_PASSWORD in .env to your real PostgreSQL password. I will never see or store it." If they paste it in chat, acknowledge but warn: "Please rotate that password — it just hit my context. I've used it for the verification step but I'm not persisting it."

6. **Verify host → DB reachability.** Run `PGPASSWORD=$DB_PASSWORD psql -h $DB_HOST -U $DB_USERNAME -d $DB_DATABASE -c "SELECT 1"` and report success or failure. If it fails, do not proceed — give the user the exact error and ask them to fix it. Common fixes: wrong IP, wrong port (default 5432), wrong password, Postgres not listening on the IP, firewall.

7. **Verify pod → DB reachability.** This is the trap that breaks e2e for first-timers. The host can reach the DB but a k8s pod sometimes cannot, especially when minikube is `driver=none` and the pod goes through the docker bridge. Run:
   ```bash
   kubectl run netcheck --rm -it --restart=Never --image=cleanup-batch:1.0.0 \
     --image-pull-policy=Never --command -- sh -c \
     "echo > /dev/tcp/$DB_HOST/5432 && echo OK || echo FAIL"
   ```
   Wait ~30s for the pod to schedule. If `FAIL`, this is the bug from commit `1d748f8` — the host can reach a different IP than the cluster can. Ask the user to confirm `DB_HOST` is the right one for the cluster, not just for the host.

8. **Hand off to the project's own scripts.** Once host + pod both reach the DB, the env is set. Tell the user:
   ```
   Your env is ready. To run the e2e suite:
     bash scripts/setup-local.sh           # one-time: creates batch-jobs ns + db-credentials Secret
     mvn -B clean verify                  # unit + integration tests
     docker build -t cleanup-batch:1.0.0 .
     bash scripts/run-and-verify.sh       # happy path
     bash scripts/test-error-injection.sh
     bash scripts/test-same-day-manual-run.sh
   ```
   Or, if they want the Jenkins-driven registry flow: `bash scripts/test-cicd-e2e.sh` (requires Jenkins + Aliyun ACR — separate setup, see AGENTS.md).

## Hard Rules (for the agent)

- **Do not type a password the user shares with you into any file.** If `.env` doesn't exist, you may create it with non-secret values, but leave `DB_PASSWORD=` empty or as the literal placeholder.
- **Do not auto-write `.env` if the user has not explicitly asked you to write it.** "I gave you the values, just do it" counts as explicit. "I told you the values" does not.
- **Do not run e2e tests until both host AND pod reachability pass.** Running them before pod-reachability works leads to misleading "Read timed out" SSL errors from JDBC that look like code bugs but are network.
- **Do not modify the committed defaults in `scripts/lib-local.sh` or `jenkins/combined-pipeline-scm.groovy` just because the user's IP differs.** Both have shell / build-param overrides. The 5th positional arg of `apply_local_job` is `db_host`; the Jenkins `DB_HOST` build param is editable per build.
- **Do not add the user's DB host or password to any global config, env var that persists, or any file outside `.env`.**

## Common Failure Modes

- **"Read timed out" SSL handshake from the pod** — usually wrong `DB_HOST`: the host can reach one IP, the cluster reaches a different one. Go back to step 7.
- **`Job batch-jobs/db-credentials not found`** — they skipped `scripts/setup-local.sh`. Run it.
- **`no such container: minikube` from `minikube status`** — the cluster runs `driver=none` (no docker wrapper container). Use `kubectl get nodes` to confirm the cluster is actually up; `start-jenkins.sh` + the kubelet systemd unit auto-start it on boot.
- **`Permission denied` on docker / kubectl** — user needs to be in the `docker` group, or use sudo. Don't add them to groups for them.
- **Tests pass locally but Jenkins build fails on git clone** — HTTP/2 issue between the Jenkins container and github.com. Fix is `docker exec -u root jenkins git config --system http.version HTTP/1.1`. Only do this if the user confirms the network isn't already fixed.

## Quick Reference

| User said | You should |
|---|---|
| "set up local env" / "first-time setup" / "I just cloned" | Run the workflow from step 1 |
| "DB host is X, password is Y" | Set DB_HOST/X in `.env` yourself only if they say "write it"; always tell them to set DB_PASSWORD themselves |
| "tests fail with Read timed out" | Jump to step 7 (pod reachability) |
| "I want to use a different DB" | Ask for the 4 values, follow steps 3-7 |
| "I need the Jenkins path to work" | Out of scope — point to AGENTS.md's Jenkins Integration section + the `jenkins-docker` skill |

## When You're Done

Output a one-line summary the user can copy into a paste:

```
local env ready: DB_HOST=<x> DB_DATABASE=<y> DB_USERNAME=<z> — host+pod reach ✓
```

Then list the next command (usually `bash scripts/setup-local.sh`).
