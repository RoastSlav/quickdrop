pipeline {
    agent any
    environment {
        // Docker image repository (replace with your Docker Hub username and image name)
        DOCKER_IMAGE = "your-dockerhub-username/your-image-name"
    }
    stages {
        stage('Checkout SCM') {
            steps {
                checkout scm
            }
        }
        stage('Build') {
            agent {
                docker {
                    image 'maven:3.9.9-eclipse-temurin-21'
                    args '-v $HOME/.m2:/root/.m2'
                    reuseNode true
                }
            }
            steps {
                sh 'mvn clean compile'
            }
        }
        stage('Test') {
            agent {
                docker {
                    image 'maven:3.9.9-eclipse-temurin-21'
                    args '-v $HOME/.m2:/root/.m2'
                    reuseNode true
                }
            }
            steps {
                sh 'mvn test'
            }
        }
        stage('Docker Build and Push Multi-Arch') {
            steps {
                // Log in to Docker registry using Jenkins credentials
                withCredentials([usernamePassword(credentialsId: 'docker-hub-credentials',
                                                 usernameVariable: 'DOCKER_USER',
                                                 passwordVariable: 'DOCKER_PASS')]) {
                    sh 'echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin'
                }
                // Create and use a Docker Buildx builder for multi-arch build
                sh 'docker buildx create --use --name multiarchbuilder'
                // Build and push multi-architecture image (linux/amd64 and linux/arm64) with 'latest' tag only
                sh 'docker buildx build --platform linux/amd64,linux/arm64 --push -t $DOCKER_IMAGE:latest .'
            }
            post {
                always {
                    // Remove the buildx builder to clean up even if the build fails
                    sh 'docker buildx rm multiarchbuilder || true'
                }
            }
        }
        stage('Cleanup') {
            steps {
                sh 'docker system prune -f'
            }
        }
    }
}
