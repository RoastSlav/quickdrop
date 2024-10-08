pipeline {
    agent any

    environment {
        MAVEN_HOME = tool name: 'Maven', type: 'hudson.tasks.Maven$MavenInstallation'
        DOCKER_IMAGE = "quickdrop:latest"
        CONTAINER_NAME = "quickdrop-1"
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
                            // Updated volume mappings with standard directories
                            sh """
                            docker run -d --name ${CONTAINER_NAME} \
                            -p 8083:8080 \
                            -v /var/lib/quickdrop/db:/app/db \
                            -v /var/log/quickdrop:/app/log \
                            -v /srv/quickdrop/files:/files \
                            ${DOCKER_IMAGE}
                            """
                        }
                    }
                }
    }

    post {
        success {
            echo 'Deployment was successful!'
        }
        failure {
            echo 'Deployment failed, please check the logs.'
        }
    }
}
