pipeline {
    agent any
    tools {
        maven 'maven'
        jdk 'java'
    }
    environment {
        ALIYUN_REGISTRY = 'crpi-e2h2rfj3kunrwe5n.cn-hangzhou.personal.cr.aliyuncs.com'
        ALIYUN_USERNAME = 'zdry146'
        ALIYUN_NAMESPACE = 'mike-docker-registry'
        ALIYUN_IMAGE_NAME = 'spring-batch-cleanup-job'
        ALIYUN_DOCKER_CREDS = credentials('aliyun-docker-login')
    }
    stages {
        stage('Check out git') {
            steps {
                dir('spring-batch-cleanup-job') {
                    git branch: 'main',
                    url: 'https://github.com/zdry146/spring-batch-cleanup-job.git',
                    credentialsId: 'git-cred'
                }
            }
        }
        stage('package') {
            steps {
                dir('spring-batch-cleanup-job') {
                    sh 'mvn clean package -DskipTests'
                    echo "package success"
                }
            }
        }
        stage('Test') {
            steps {
                dir('spring-batch-cleanup-job') {
                    sh 'mvn test'
                }
            }
        }
        stage('Build Docker Image') {
            steps {
                dir('spring-batch-cleanup-job') {
                    script {
                        def imageVersion = sh(script: "mvn help:evaluate -Dexpression=project.version -q -DforceStdout", returnStdout: true).trim()
                        env.IMAGE_VERSION = imageVersion
                        echo "Image version: ${imageVersion}"
                        sh "sg docker -c 'docker build -t ${env.ALIYUN_IMAGE_NAME} .'"
                    }
                }
            }
        }
        stage('Push to Aliyun Registry') {
            steps {
                sh "sg docker -c \"docker tag ${env.ALIYUN_IMAGE_NAME} ${env.ALIYUN_REGISTRY}/${env.ALIYUN_NAMESPACE}/${env.ALIYUN_IMAGE_NAME}:${env.IMAGE_VERSION}\""
                sh "sg docker -c \"docker tag ${env.ALIYUN_IMAGE_NAME} ${env.ALIYUN_REGISTRY}/${env.ALIYUN_NAMESPACE}/${env.ALIYUN_IMAGE_NAME}:latest\""
                sh "sg docker -c \"echo \${ALIYUN_DOCKER_CREDS_PSW} | docker login --username=\${ALIYUN_DOCKER_CREDS_USR} ${env.ALIYUN_REGISTRY} --password-stdin\""
                sh "sg docker -c \"docker push ${env.ALIYUN_REGISTRY}/${env.ALIYUN_NAMESPACE}/${env.ALIYUN_IMAGE_NAME}:${env.IMAGE_VERSION}\""
                sh "sg docker -c \"docker push ${env.ALIYUN_REGISTRY}/${env.ALIYUN_NAMESPACE}/${env.ALIYUN_IMAGE_NAME}:latest\""
            }
        }
    }
    post {
        always {
            junit allowEmptyResults: true, testResults: 'spring-batch-cleanup-job/target/surefire-reports/*.xml'
        }
        success {
            echo "Image pushed: ${env.ALIYUN_REGISTRY}/${env.ALIYUN_NAMESPACE}/${env.ALIYUN_IMAGE_NAME}:${env.IMAGE_VERSION}"
        }
    }
}
