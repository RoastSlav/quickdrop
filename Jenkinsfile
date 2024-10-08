pipeline {
    agent any

    environment {
        // Setting Maven, Docker image name, and container name
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
                // Using Maven to build and test
                sh "${MAVEN_HOME}/bin/mvn clean install"
            }
        }

        stage('Docker Build') {
            steps {
                // Building the Docker image for the app
                sh """
                docker build -t ${DOCKER_IMAGE} .
                """
            }
        }

        stage('Stop and Remove Old Container') {
            steps {
                script {
                    // Stop and remove the old container if it exists
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
                    // Run the new container with the updated Docker image
                    sh """
                    docker run -d --name ${CONTAINER_NAME} -p 8083:8080 ${DOCKER_IMAGE}
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
