// GitHub->Gitee fallback - clone INTO workspace root using bash
node {
    def workspace = pwd()
    def gitUrl = null

    stage('Checkout GitHub->Gitee fallback') {
        echo "Workspace: ${workspace}"
        echo 'Attempting GitHub (30s timeout)...'
        def githubOk = false
        try {
            timeout(time: 30, unit: 'SECONDS') {
                sh '''#!/bin/bash
                    set -e
                    cd ''' + workspace + '''
                    rm -rf .cloned-tmp
                    git clone --depth=1 -b main https://github.com/zdry146/spring-batch-cleanup-job.git .cloned-tmp
                    # Overwrite workspace contents with cloned repo (handles existing .git correctly)
                    shopt -s dotglob
                    cp -a .cloned-tmp/. .
                    rm -rf .cloned-tmp
                '''
            }
            githubOk = true
            gitUrl = 'https://github.com/zdry146/spring-batch-cleanup-job.git'
        } catch (Exception e) {
            echo "GitHub unreachable: ${e.message}"
        }

        if (!githubOk) {
            echo 'Falling back to Gitee (SSH)...'
            sh '''#!/bin/bash
                set -e
                cd ''' + workspace + '''
                rm -rf .cloned-tmp
                git clone --depth=1 -b main git@gitee.com:zdry146/spring-batch-cleanup-job.git .cloned-tmp
                shopt -s dotglob
                cp -a .cloned-tmp/. .
                rm -rf .cloned-tmp
            '''
            gitUrl = 'git@gitee.com:zdry146/spring-batch-cleanup-job.git'
        }
        echo "Checked out from: ${gitUrl}"
        sh "ls -la ${workspace}/ | head -25"
    }

    def jfPath = "${workspace}/jenkins/combined-pipeline-scm.groovy"
    def jfText = readFile(jfPath)
    echo "Loaded Jenkinsfile (${jfText.size()} chars) from ${gitUrl}"

    stage('Run pipeline from Jenkinsfile') {
        def jfTextModified = jfText.replace("agent any", "agent none")
        echo "Modified: agent any -> agent none"
        evaluate(jfTextModified)
    }
}