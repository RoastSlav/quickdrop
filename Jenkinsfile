pipeline {
    agent any

    environment {
        DOCKER_IMAGE          = 'roastslav/quickdrop:latest'
        DOCKER_CREDENTIALS_ID = 'dockerhub-credentials'
        MAVEN_CACHE           = "${env.HOME}/.m2"
        BUILDER_NAME          = "buildx-${env.BUILD_TAG}"
    }

    options {
        timestamps()
        ansiColor('xterm')
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                script {
                    docker.image('maven:3.9.9-eclipse-temurin-21')
                          .inside("-v ${MAVEN_CACHE}:/root/.m2") {
                        sh 'mvn -B clean compile'
                    }
                }
            }
        }

        stage('Test') {
            steps {
                script {
                    docker.image('maven:3.9.9-eclipse-temurin-21')
                          .inside("-v ${MAVEN_CACHE}:/root/.m2") {
                        sh 'mvn -B test'
                    }
                }
            }
        }

        stage('Docker Build and Push Multi-Arch') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: DOCKER_CREDENTIALS_ID,
                                                      usernameVariable: 'DOCKER_USER',
                                                      passwordVariable: 'DOCKER_PASS')]) {
                        sh '''
                          set -e
                          echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin

                          # Create/use ephemeral builder
                          docker buildx create --driver docker-container --name "$BUILDER_NAME" --use || docker buildx use "$BUILDER_NAME"
                          docker buildx inspect --bootstrap

                          docker buildx build \
                            --platform linux/amd64,linux/arm64 \
                            -t ${DOCKER_IMAGE} \
                            --push .

                          docker logout
                        '''
                    }
                }
            }
            post {
                always {
                    sh '''
                      docker buildx rm "$BUILDER_NAME" 2>/dev/null || true
                      docker container prune -f      2>/dev/null || true
                      docker image prune -f          2>/dev/null || true
                    '''
                }
            }
        }

        stage('Cleanup') {
            steps {
                sh 'docker system prune -f || true'
            }
        }
    }
}
