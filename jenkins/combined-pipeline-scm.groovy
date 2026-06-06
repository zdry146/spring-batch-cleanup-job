pipeline {
    agent any
    parameters {
        choice(
            name: 'MODE',
            choices: ['ci', 'cd'],
            description: 'ci = build+test+push image, cd = deploy image to k8s'
        )
        choice(
            name: 'IMAGE_TAG',
            choices: ['latest', '1.0.0'],
            description: 'Image tag to deploy (CD mode only)'
        )
        string(
            name: 'NAMESPACE',
            defaultValue: 'batch-jobs',
            description: 'Kubernetes namespace (CD mode only)'
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
        stage('Build & test') {
            when { expression { params.MODE == 'ci' } }
            steps {
                sh 'mvn -B clean verify'
            }
        }
        stage('Determine image version') {
            when { expression { params.MODE == 'ci' } }
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
            when { expression { params.MODE == 'ci' } }
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

        stage('Ensure image pull secret') {
            when { expression { params.MODE == 'cd' } }
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
            when { expression { params.MODE == 'cd' } }
            steps {
                sh """
                kubectl apply -f k8s/namespace.yaml
                kubectl apply -f k8s/secret.yaml
                kubectl apply -f k8s/cronjob.yaml
                kubectl apply -f k8s/job.yaml
                """
            }
        }
        stage('Set image tag') {
            when { expression { params.MODE == 'cd' } }
            steps {
                sh """
                set -euo pipefail
                if ! kubectl -n ${params.NAMESPACE} set image \\
                    cronjob/cleanup-cron cleanup-batch=${env.FULL_IMAGE}:${params.IMAGE_TAG}; then
                    echo "WARN: failed to set image on cronjob/cleanup-cron"
                fi
                if ! kubectl -n ${params.NAMESPACE} set image \\
                    job/cleanup-manual cleanup-batch=${env.FULL_IMAGE}:${params.IMAGE_TAG}; then
                    echo "WARN: failed to set image on job/cleanup-manual. \\
                          The Job may be immutable after completion. \\
                          Delete it with: kubectl -n ${params.NAMESPACE} delete job cleanup-manual"
                    exit 1
                fi
                """
            }
        }
        stage('Verify') {
            when { expression { params.MODE == 'cd' } }
            steps {
                sh """
                set -euo pipefail
                echo "CronJob image:"
                kubectl -n ${params.NAMESPACE} get cronjob cleanup-cron \\
                    -o jsonpath='{.spec.jobTemplate.spec.template.spec.containers[0].image}{"\\n"}'
                echo "Job image:"
                kubectl -n ${params.NAMESPACE} get job cleanup-manual \\
                    -o jsonpath='{.spec.template.spec.containers[0].image}{"\\n"}'
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
                if (params.MODE == 'cd') {
                    sh "kubectl -n ${params.NAMESPACE} get all || true"
                }
            }
        }
    }
}
