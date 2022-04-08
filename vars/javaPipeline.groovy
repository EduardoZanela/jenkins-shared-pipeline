def isBuildAReplay() {
    def replyClassName = "org.jenkinsci.plugins.workflow.cps.replay.ReplayCause"
    !currentBuild.rawBuild.getCauses().any{ cause -> cause.toString().contains(replyClassName) }
}

def isDeployDevCommit(){
    def message = sh(returnStdout: true, script: 'git log --format=format:"%s %b" -1 ${GIT_COMMIT}')
    def changeId = env.CHANGE_ID
    message.contains('#deploydev') &&  !isPullRequest()
}

def isPullRequest(){
    def changeId = env.CHANGE_ID
    changeId?.trim()
}

def isForDeploy(){
    isPullRequest() || isDeployDevCommit() || env.GIT_BRANCH == 'main'
}

def call() {
    pipeline {
        agent any
        tools {
            maven 'apache-maven'
        }
        stages {
            stage("Building Application") {
                steps {
                    sh "mvn clean install -DskipTests=true"
                }
            }
            stage("Running Unit Tests") {
                steps {
                    sh "mvn test"
                }
            }
            stage("SonarQube Analysis") {
                steps {
                    script {
                        withSonarQubeEnv {
                            sh "mvn verify sonar:sonar -DskipTests=true -Dintegration-tests.skip=true -Dmaven.test.failure.ignore=true"
                        }
                    }
                }
            }
            stage('SonarQube Quality Gate') {
                steps {
                    timeout(time: 2, unit: 'MINUTES') {
                        retry(3) {
                            script {
                                def qg = waitForQualityGate()
                                if (qg.status != 'OK') {
                                    error "Pipeline aborted due to quality gate failure: ${qg.status}"
                                }
                            }
                        }
                    }
                }
            }
            stage("Build/Push Docker Image AWS ECR") {
                when {expression {isForDeploy()}}
                steps {
                    script {
                        withCredentials([usernamePassword(credentialsId: 'docker_login', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                            def imagetag = env.GIT_BRANCH + '.' + env.BUILD_NUMBER
                            echo imagetag
                            //sh 'docker login -u $USERNAME -p $PASSWORD'
                            sh "mvn docker:build -Ddocker.image.tag=$imagetag -Ddocker.auth.user=$USERNAME -Ddocker.auth.pass=$PASSWORD"          
                            sh "mvn docker:push -Ddocker.image.tag=$imagetag -Ddocker.auth.user=$USERNAME -Ddocker.auth.pass=$PASSWORD"
                        }
                    }
                }
            }
            stage("Deploy DEV") {
                when {expression {isDeployDevCommit()}}
                steps {
                    echo 'Deploying DEV'
                }
            }
            stage("Deploy TEST") {
                when {expression {isPullRequest()}}
                steps {
                    echo 'Deploying TEST'
                }
            }
            stage("Deploy PROD") {
                when {branch 'main'}
                steps {
                    echo 'Deploying PROD'
                }
            }
        }
        post {
            always {
            echo 'always'
            }
            failure {
            echo 'failure'
            }
            success {
            echo 'sucess'
            }
        }
    }
}