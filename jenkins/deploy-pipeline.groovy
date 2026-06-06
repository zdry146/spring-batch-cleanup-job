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
