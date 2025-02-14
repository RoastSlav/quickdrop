pipeline {
    agent any

    environment {
        MAVEN_HOME = tool name: 'Maven', type: 'hudson.tasks.Maven$MavenInstallation'
        DOCKER_IMAGE = "roastslav/quickdrop:latest"
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

        stage('Docker Build and Push Multi-Arch') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: DOCKER_CREDENTIALS_ID,
                        passwordVariable: 'DOCKER_PASS', usernameVariable: 'DOCKER_USER')]) {

                        sh """
                          BUILDER_NAME=\$(docker buildx create --driver docker-container)
                          docker buildx use \$BUILDER_NAME
                          docker buildx inspect --bootstrap

                          # Login
                          echo "\$DOCKER_PASS" | docker login -u "\$DOCKER_USER" --password-stdin

                          # Build & push multi-arch
                          docker buildx build \\
                              --platform linux/amd64,linux/arm64 \\
                              -t ${DOCKER_IMAGE} \\
                              --push .

                          # Logout
                          docker logout

                          # Remove the ephemeral builder
                          docker buildx rm \$BUILDER_NAME || true
                        """
                    }
                }
            }
        }

        stage('Cleanup') {
            steps {
                sh "docker system prune -f"
            }
        }
    }
}
