pipeline {
    agent any

    environment {
        MAVEN_HOME = tool name: 'Maven', type: 'hudson.tasks.Maven$MavenInstallation'
        DOCKER_IMAGE = "quickdrop:latest"
        CONTAINER_NAME = "quickdrop-1"
        DOCKER_CREDENTIALS_ID = 'dockerhub-credentials'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build and Test') {
            steps {
                sh "${MAVEN_HOME}/bin/mvn clean package"
            }
        }

        stage('Docker Build') {
            steps {
                sh """
                ls -al ${WORKSPACE}/target/
                docker build -t ${DOCKER_IMAGE} .
                """
            }
        }

        stage('Push to Docker Hub') {
                    steps {
                        script {
                            withCredentials([usernamePassword(credentialsId: DOCKER_CREDENTIALS_ID, passwordVariable: 'DOCKER_PASS', usernameVariable: 'DOCKER_USER')]) {
                                sh """
                                echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin
                                docker push ${DOCKER_IMAGE}
                                docker logout
                                """
                            }
                        }
                    }
                }

        stage('Stop and Remove Old Container') {
            steps {
                script {
                    sh """
                    docker ps -q --filter name=${CONTAINER_NAME} | grep -q . && docker stop ${CONTAINER_NAME} || true
                    docker ps -aq --filter name=${CONTAINER_NAME} | grep -q . && docker rm ${CONTAINER_NAME} || true
                    """
                }
            }
        }

        stage('Run New Container') {
                    steps {
                        script {
                            sh """
                            docker run -d --name ${CONTAINER_NAME} \
                            -p 8083:8080 \
                            -v /var/lib/quickdrop/db:/app/db \
                            -v /var/log/quickdrop:/app/log \
                            -v /srv/quickdrop/files:/app/files \
                            ${DOCKER_IMAGE}
                            """
                        }
                    }
                }
    }
}
