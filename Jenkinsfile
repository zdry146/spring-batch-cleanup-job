pipeline {
    agent any

    tools {
        maven 'maven'
        jdk 'java'
    }

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 15, unit: 'MINUTES')
    }

    triggers {
        pollSCM('H/5 * * * *')
    }

    environment {
        ALIYUN_REGISTRY   = 'crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com'
        ALIYUN_NAMESPACE  = 'mike-docker-registry'
        ALIYUN_IMAGE_NAME = 'spring-batch-cleanup-job'
        DOCKER_CREDS      = credentials('aliyun-docker-login')
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh 'mvn -B -q clean package -DskipTests'
            }
        }

        stage('Test') {
            steps {
                sh 'mvn -B test'
            }
        }

        stage('Build Docker Image') {
            steps {
                script {
                    def imageVersion = sh(
                        script: "mvn help:evaluate -Dexpression=project.version -q -DforceStdout",
                        returnStdout: true
                    ).trim()
                    env.IMAGE_VERSION = imageVersion
                    echo "Image version: ${imageVersion}"
                    sh "docker build -t ${env.ALIYUN_IMAGE_NAME}:${imageVersion} ."
                }
            }
        }

        stage('Push to Aliyun Registry') {
            steps {
                sh """
                    docker tag ${env.ALIYUN_IMAGE_NAME}:${env.IMAGE_VERSION} \
                        ${env.ALIYUN_REGISTRY}/${env.ALIYUN_NAMESPACE}/${env.ALIYUN_IMAGE_NAME}:${env.IMAGE_VERSION}
                    docker tag ${env.ALIYUN_IMAGE_NAME}:${env.IMAGE_VERSION} \
                        ${env.ALIYUN_REGISTRY}/${env.ALIYUN_NAMESPACE}/${env.ALIYUN_IMAGE_NAME}:latest
                    echo \${DOCKER_CREDS_PSW} | docker login --username=\${DOCKER_CREDS_USR} ${env.ALIYUN_REGISTRY} --password-stdin
                    docker push ${env.ALIYUN_REGISTRY}/${env.ALIYUN_NAMESPACE}/${env.ALIYUN_IMAGE_NAME}:${env.IMAGE_VERSION}
                    docker push ${env.ALIYUN_REGISTRY}/${env.ALIYUN_NAMESPACE}/${env.ALIYUN_IMAGE_NAME}:latest
                    docker logout ${env.ALIYUN_REGISTRY}
                """
            }
        }
    }

    post {
        always {
            junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
        }
        success {
            echo "Image pushed: ${env.ALIYUN_REGISTRY}/${env.ALIYUN_NAMESPACE}/${env.ALIYUN_IMAGE_NAME}:${env.IMAGE_VERSION}"
        }
        failure {
            echo 'Build failed. See console output for details.'
        }
    }
}
