FROM openjdk:21-jdk-slim

WORKDIR /app

COPY target/quickdrop.jar /app/quickdrop.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/quickdrop.jar"]
