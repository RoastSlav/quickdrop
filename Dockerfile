FROM openjdk:21-jdk-slim

WORKDIR /app

COPY /var/lib/jenkins/workspace/quickdrop/target/quickdrop-0.0.1-SNAPSHOT.jar /app/quickdrop.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/quickdrop.jar"]
