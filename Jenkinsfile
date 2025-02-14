pipeline {
    agent any
    environment {
        MAVEN_HOME = tool(name: 'Maven', type: 'hudson.tasks.Maven$MavenInstallation')
        DOCKER_IMAGE = "roastslav/quickdrop"
        DOCKER_CREDENTIALS_ID = 'dockerhub-credentials'
        IMAGE_TAG = "latest"
    }
    stages {
        stage('Checkout') {
            steps { checkout scm }
        }
        stage('Build and Test') {
            steps { sh "${MAVEN_HOME}/bin/mvn -B clean package" }
        }
        stage('Docker Build') {
            steps {
                script {
                    def builder = sh(script: "docker buildx create --driver docker-container", returnStdout: true).trim()
                    sh "docker buildx use ${builder}"
                    sh "docker buildx inspect --bootstrap"
                    sh "docker buildx build --platform linux/amd64 -t ${DOCKER_IMAGE}:${IMAGE_TAG} -o type=docker ."
                    sh "docker buildx rm ${builder} || true"
                }
            }
        }
        stage('Docker Push') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: DOCKER_CREDENTIALS_ID, passwordVariable: 'DOCKER_PASS', usernameVariable: 'DOCKER_USER')]) {
                        sh 'echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin'
                    }
                    sh "docker push ${DOCKER_IMAGE}:${IMAGE_TAG}"
                    sh "docker logout"
                }
            }
        }
    }
    post {
        always { sh "docker system prune -f || true" }
    }
}
