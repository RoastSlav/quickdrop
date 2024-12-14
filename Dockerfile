FROM openjdk:21-jdk-slim

WORKDIR /app

COPY target/quickdrop-0.0.1-SNAPSHOT.jar /app/quickdrop.jar

VOLUME ["/app/db", "/app/log", "/files"]

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/quickdrop.jar"]
