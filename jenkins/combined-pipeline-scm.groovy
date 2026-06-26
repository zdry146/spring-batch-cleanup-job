pipeline {
    agent any
    options {
        timeout(time: 30, unit: 'MINUTES')
    }
    tools {
        maven 'maven'
        jdk 'java'
    }
    parameters {
        choice(
            name: 'MODE',
            choices: ['ci', 'cd', 'both'],
            description: 'ci = build+test+push, cd = deploy to k8s, both = ci then cd in one run'
        )
        choice(
            name: 'IMAGE_TAG',
            choices: ['latest', '1.0.0'],
            description: 'Image tag to deploy (cd mode only; ignored when MODE=both or ci)'
        )
        string(
            name: 'NAMESPACE',
            defaultValue: 'batch-jobs',
            description: 'Kubernetes namespace (cd mode only)'
        )
        string(
            name: 'DB_HOST',
            defaultValue: '192.168.232.128',
            description: 'PostgreSQL host (cluster-reachable IP/hostname) injected into both manifests as the DB_HOST env var (cd mode only).'
        )
        string(
            name: 'DB_DATABASE',
            defaultValue: 'testdb',
            description: 'PostgreSQL database name injected into both manifests as the DB_DATABASE env var (cd mode only). Must match the database Spring Batch metadata + the application posts live in.'
        )
    }
    environment {
        ALIYUN_REGISTRY  = 'crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com'
        ALIYUN_NAMESPACE = 'mike-docker-registry'
        ALIYUN_IMAGE     = 'spring-batch-cleanup-job'
        FULL_IMAGE       = "${env.ALIYUN_REGISTRY}/${env.ALIYUN_NAMESPACE}/${env.ALIYUN_IMAGE}"
        ALIYUN_DOCKER_CREDS = credentials('aliyun-docker-login')
        DB_PASSWORD      = credentials('db-password')
    }
    stages {
        stage('Build & test') {
            when { expression { params.MODE == 'ci' || params.MODE == 'both' } }
            steps { sh 'mvn -B clean verify' }
        }
        stage('SonarQube analysis') {
            when { expression { params.MODE == 'ci' || params.MODE == 'both' } }
            steps {
                script {
                    withSonarQubeEnv('local-sonarqube') {
                        sh '''
                        set -euo pipefail
                        mvn -B \
                          org.sonarsource.scanner.maven:sonar-maven-plugin:5.7.0.6970:sonar \
                          -Dsonar.projectKey=spring-batch-cleanup-job \
                          -Dsonar.projectName='Spring Batch Cleanup Job' \
                          -Dsonar.java.binaries=target/classes \
                          -Dsonar.java.test.binaries=target/test-classes \
                          -Dsonar.exclusions='target/**,docs/**,.opencode/**'
                        '''
                        // ---- Quality Gate check (webhook-based) ----
                        // SonarQube has a webhook registered pointing to
                        // http://192.168.232.128:8080/sonarqube-webhook/.
                        //
                        // waitForQualityGate() must be called OUTSIDE the
                        // withSonarQubeEnv block: SonarBuildWrapper's
                        // AddBuildInfo.Disposer reads target/sonar/report-task.txt
                        // and adds the SonarAnalysisAction build action in its
                        // tearDown, which runs AFTER this block exits. If we
                        // call waitForQualityGate() here, the action has not yet
                        // been added and it throws:
                        //   IllegalStateException: No previous SonarQube
                        //   analysis found on this pipeline execution.
                        //
                        // SonarQube 26.x defaults sonar.validateWebhooks=true,
                        // which rejects local-network URLs. We set
                        // sonar.validateWebhooks=false in sonar.properties and
                        // also keep the webhook row inserted via DB as a
                        // belt-and-braces measure (see earlier diagnostic).
                        //
                        // We pin sonar-maven-plugin to 5.7.0.6970 because the
                        // 4.0.0.4121 line (resolved by default from the
                        // codehaus.mojo -> sonarsource.scanner.maven relocation)
                        // forks a SonarScanner CLI subprocess and never
                        // produces report-task.txt in some configurations; the
                        // 5.x line uses the scanner-engine bridge and reliably
                        // writes it.
                        //
                        // Status semantics from the SonarQube Jenkins plugin:
                        //   OK    -> build passes
                        //   WARN  -> build marked UNSTABLE (yellow)
                        //   ERROR -> build fails (red)
                    }
                }
                // waitForQualityGate() must run AFTER withSonarQubeEnv so
                // AddBuildInfo.tearDown() has registered the analysis.
                timeout(time: 5, unit: 'MINUTES') {
                    def qg = waitForQualityGate()
                    if (qg.status == 'ERROR') {
                        error("SonarQube Quality Gate failed (status=${qg.status})")
                    } else if (qg.status == 'WARN') {
                        unstable("SonarQube Quality Gate warning (status=${qg.status})")
                    }
                }
            }
        }
        stage('Determine image version') {
            when { expression { params.MODE == 'ci' || params.MODE == 'both' } }
            steps {
                script {
                    env.IMAGE_VERSION = sh(
                        label: 'Read project version',
                        script: 'mvn -B -q -DforceStdout -Dexpression=project.version help:evaluate',
                        returnStdout: true
                    ).trim()
                }
            }
        }
        stage('Build & push image') {
            when { expression { params.MODE == 'ci' || params.MODE == 'both' } }
            steps {
                sh """
                set -euo pipefail
                echo "\$ALIYUN_DOCKER_CREDS_PSW" | docker login -u "\$ALIYUN_DOCKER_CREDS_USR" --password-stdin "\$ALIYUN_REGISTRY"
                docker build -t "\$FULL_IMAGE:\$IMAGE_VERSION" -t "\$FULL_IMAGE:latest" .
                docker push "\$FULL_IMAGE:\$IMAGE_VERSION"
                docker push "\$FULL_IMAGE:latest"
                """
            }
        }

        stage('Resolve deploy tag') {
            when { expression { params.MODE == 'cd' || params.MODE == 'both' } }
            steps {
                script {
                    env.DEPLOY_TAG = (params.MODE == 'both') ? env.IMAGE_VERSION : params.IMAGE_TAG
                    echo "Deploying tag: ${env.DEPLOY_TAG} (MODE=${params.MODE})"
                }
            }
        }

        stage('Ensure image pull secret') {
            when { expression { params.MODE == 'cd' || params.MODE == 'both' } }
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
            when { expression { params.MODE == 'cd' || params.MODE == 'both' } }
            steps {
                sh """
                set -euo pipefail
                kubectl apply -f k8s/namespace.yaml
                # db-credentials Secret is generated from the Jenkins 'db-password'
                # credential at deploy time; k8s/secret.yaml is gitignored.
                kubectl -n ${params.NAMESPACE} create secret generic db-credentials \\
                    --from-literal=password="\${DB_PASSWORD}" \\
                    --dry-run=client -o yaml | kubectl apply -f -
                # Substitute the __DB_HOST__ / __DB_DATABASE__ placeholders
                # with the build-time parameters so the same YAML can be
                # deployed against any cluster-reachable PostgreSQL server
                # and database.
                sed -e "s|__DB_HOST__|${params.DB_HOST}|g" \\
                    -e "s|__DB_DATABASE__|${params.DB_DATABASE}|g" \\
                    k8s/cronjob.yaml | kubectl -n ${params.NAMESPACE} apply -f -
                """
            }
        }
        stage('Set image tag') {
            when { expression { params.MODE == 'cd' || params.MODE == 'both' } }
            steps {
                sh """
                set -euo pipefail
                if ! kubectl -n ${params.NAMESPACE} set image \\
                    cronjob/cleanup-cron cleanup-batch=${env.FULL_IMAGE}:${env.DEPLOY_TAG}; then
                    echo "WARN: failed to set image on cronjob/cleanup-cron"
                fi
                kubectl -n ${params.NAMESPACE} delete job cleanup-manual --ignore-not-found
                sed -e "s|__DB_HOST__|${params.DB_HOST}|g" \\
                    -e "s|__DB_DATABASE__|${params.DB_DATABASE}|g" \\
                    -e "s|image: ${env.FULL_IMAGE}:.*|image: ${env.FULL_IMAGE}:${env.DEPLOY_TAG}|" \\
                    k8s/job.yaml | kubectl -n ${params.NAMESPACE} apply -f -
                """
            }
        }
        stage('Verify') {
            when { expression { params.MODE == 'cd' || params.MODE == 'both' } }
            steps {
                sh """
                set -euo pipefail
                EXPECTED='${env.FULL_IMAGE}:${env.DEPLOY_TAG}'
                EXPECTED_DB='${params.DB_HOST}'
                EXPECTED_DBNAME='${params.DB_DATABASE}'
                CRON_IMAGE=\$(kubectl -n ${params.NAMESPACE} get cronjob cleanup-cron \\
                    -o jsonpath='{.spec.jobTemplate.spec.template.spec.containers[0].image}')
                JOB_IMAGE=\$(kubectl -n ${params.NAMESPACE} get job cleanup-manual \\
                    -o jsonpath='{.spec.template.spec.containers[0].image}')
                CRON_DB=\$(kubectl -n ${params.NAMESPACE} get cronjob cleanup-cron \\
                    -o jsonpath='{.spec.jobTemplate.spec.template.spec.containers[0].env[?(@.name=="DB_HOST")].value}')
                JOB_DB=\$(kubectl -n ${params.NAMESPACE} get job cleanup-manual \\
                    -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="DB_HOST")].value}')
                CRON_DBNAME=\$(kubectl -n ${params.NAMESPACE} get cronjob cleanup-cron \\
                    -o jsonpath='{.spec.jobTemplate.spec.template.spec.containers[0].env[?(@.name=="DB_DATABASE")].value}')
                JOB_DBNAME=\$(kubectl -n ${params.NAMESPACE} get job cleanup-manual \\
                    -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="DB_DATABASE")].value}')
                echo "CronJob image:      \$CRON_IMAGE"
                echo "Job image:          \$JOB_IMAGE"
                echo "Expected image:     \$EXPECTED"
                echo "CronJob DB_HOST:    \$CRON_DB"
                echo "Job DB_HOST:        \$JOB_DB"
                echo "Expected DB_HOST:   \$EXPECTED_DB"
                echo "CronJob DB_DATABASE: \$CRON_DBNAME"
                echo "Job DB_DATABASE:     \$JOB_DBNAME"
                echo "Expected DB_DATABASE: \$EXPECTED_DBNAME"
                [ "\$CRON_IMAGE" = "\$EXPECTED" ] || { echo "FAIL: CronJob image \$CRON_IMAGE does not match expected \$EXPECTED"; exit 1; }
                [ "\$JOB_IMAGE" = "\$EXPECTED" ] || { echo "FAIL: Job image \$JOB_IMAGE does not match expected \$EXPECTED"; exit 1; }
                [ "\$CRON_DB" = "\$EXPECTED_DB" ] || { echo "FAIL: CronJob DB_HOST \$CRON_DB does not match expected \$EXPECTED_DB"; exit 1; }
                [ "\$JOB_DB" = "\$EXPECTED_DB" ] || { echo "FAIL: Job DB_HOST \$JOB_DB does not match expected \$EXPECTED_DB"; exit 1; }
                [ "\$CRON_DBNAME" = "\$EXPECTED_DBNAME" ] || { echo "FAIL: CronJob DB_DATABASE \$CRON_DBNAME does not match expected \$EXPECTED_DBNAME"; exit 1; }
                [ "\$JOB_DBNAME" = "\$EXPECTED_DBNAME" ] || { echo "FAIL: Job DB_DATABASE \$JOB_DBNAME does not match expected \$EXPECTED_DBNAME"; exit 1; }
                echo "All images, DB_HOST and DB_DATABASE match expected"
                echo "Resources:"
                kubectl -n ${params.NAMESPACE} get cronjob,job -o wide
                """
            }
        }
    }
    post {
        success {
            echo "Pipeline (MODE=${params.MODE}) succeeded"
        }
        failure {
            echo "Pipeline (MODE=${params.MODE}) failed"
            script {
                if (params.MODE == 'cd' || params.MODE == 'both') {
                    sh "kubectl -n ${params.NAMESPACE} get all || true"
                }
            }
        }
    }
}
