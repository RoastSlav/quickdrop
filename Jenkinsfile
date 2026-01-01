pipeline {
  agent any

  environment {
    MAVEN_HOME = tool name: 'Maven', type: 'hudson.tasks.Maven$MavenInstallation'
    DOCKER_REPO = "roastslav/quickdrop"
    DOCKER_CREDENTIALS_ID = 'dockerhub-credentials'
    VERSION_FILE = '.last_version'
    TAG_PREFIX   = 'v'
  }

  stages {
    stage('Build and Test') {
      steps {
        withMaven(maven: 'Maven') {
          sh 'mvn -B -DskipTests clean package'
        }
      }
    }

    stage('Resolve version & decide tags') {
      steps {
        withMaven(maven: 'Maven') {
          script {
            echo "Branch: ${env.BRANCH_NAME} | PR: ${env.CHANGE_ID ?: 'no'}"

            env.APP_VERSION = sh(
              returnStdout: true,
              script: "mvn -q -DforceStdout help:evaluate -Dexpression=project.version"
            ).trim()

            def cleaned = env.APP_VERSION.toLowerCase().replaceAll('[^a-z0-9._-]', '')
            if (cleaned != env.APP_VERSION) {
              echo "Normalizing version '${env.APP_VERSION}' -> '${cleaned}' for Docker tag"
            }
            env.APP_VERSION = cleaned

            env.PREV_VERSION    = fileExists(env.VERSION_FILE) ? readFile(env.VERSION_FILE).trim() : ''
            env.VERSION_CHANGED = (env.APP_VERSION != env.PREV_VERSION) ? 'true' : 'false'

            def verTag = (env.TAG_PREFIX?.trim()) ? "${env.TAG_PREFIX}${env.APP_VERSION}" : env.APP_VERSION
            env.IMAGE_LATEST   = "${env.DOCKER_REPO}:latest"
            env.IMAGE_VERSION  = "${env.DOCKER_REPO}:${verTag}"

            // dev branch tag
            env.IMAGE_DEVELOP  = "${env.DOCKER_REPO}:develop"

            echo "POM version: ${env.APP_VERSION} | Previous: ${env.PREV_VERSION} | Changed: ${env.VERSION_CHANGED}"
            echo "Tags -> develop: ${env.IMAGE_DEVELOP} ; latest: ${env.IMAGE_LATEST} ; version: ${env.IMAGE_VERSION}"
          }
        }
      }
    }

    stage('Docker Build and Push (develop)') {
      when {
        allOf {
          branch 'dev'
          not { changeRequest() }
        }
      }
      steps {
        script {
          withCredentials([usernamePassword(credentialsId: DOCKER_CREDENTIALS_ID,
            passwordVariable: 'DOCKER_PASS', usernameVariable: 'DOCKER_USER')]) {

            sh '''
              set -e
              docker version
              docker buildx version || true
              docker run --privileged --rm tonistiigi/binfmt --install arm64,amd64
              BUILDER_NAME=$(docker buildx create --use || true)
              docker buildx inspect --bootstrap

              echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin

              docker buildx build \
                --platform linux/amd64,linux/arm64 \
                -t "${IMAGE_DEVELOP}" \
                --label "org.opencontainers.image.version=${APP_VERSION}" \
                --label "org.opencontainers.image.revision=${GIT_COMMIT}" \
                --push .

              docker logout
              [ -n "$BUILDER_NAME" ] && docker buildx rm "$BUILDER_NAME" || true
            '''
          }
        }
      }
    }

    stage('Docker Build and Push Multi-Arch') {
      when {
        allOf {
          branch 'master'
          expression { env.VERSION_CHANGED == 'true' }
          not { changeRequest() }
        }
      }
      steps {
        script {
          withCredentials([usernamePassword(credentialsId: DOCKER_CREDENTIALS_ID,
            passwordVariable: 'DOCKER_PASS', usernameVariable: 'DOCKER_USER')]) {

            sh '''
              set -e
              docker version
              docker buildx version || true
              docker run --privileged --rm tonistiigi/binfmt --install arm64,amd64
              BUILDER_NAME=$(docker buildx create --use || true)
              docker buildx inspect --bootstrap

              echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin

              # push latest + version tag
              docker buildx build \
                --platform linux/amd64,linux/arm64 \
                -t "${IMAGE_LATEST}" \
                -t "${IMAGE_VERSION}" \
                --label "org.opencontainers.image.version=${APP_VERSION}" \
                --push .

              docker logout
              [ -n "$BUILDER_NAME" ] && docker buildx rm "$BUILDER_NAME" || true
            '''
          }
        }
      }
    }

    stage('Skip Docker (version unchanged)') {
      when {
        allOf {
          branch 'master'
          expression { env.VERSION_CHANGED != 'true' }
          not { changeRequest() }
        }
      }
      steps {
        echo "Version unchanged (${APP_VERSION}). Skipping Docker build & push."
        script { currentBuild.result = 'SUCCESS' }
      }
    }

    stage('Persist version & Cleanup') {
      steps {
        writeFile file: "${env.VERSION_FILE}", text: env.APP_VERSION + "\n"
        sh "docker system prune -f || true"
      }
    }
  }
}