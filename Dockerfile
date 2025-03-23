# Build the application using the maven image
FROM maven:3.9.9 AS builder

WORKDIR /build
COPY . .
RUN mvn clean package

# Create a slimmed version of the Java JRE using the Corretto image
FROM amazoncorretto:21.0.6-alpine AS corretto-jdk

RUN apk add --no-cache binutils
# Build small JRE image
RUN $JAVA_HOME/bin/jlink \
         --verbose \
         --add-modules ALL-MODULE-PATH \
         --strip-debug \
         --no-man-pages \
         --no-header-files \
         --compress=zip-4 \
         --output /slim_jre

# Use a small Linux distro for final image
FROM alpine:latest
ENV JAVA_HOME=/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Copy the JRE into Alpine image
COPY --from=corretto-jdk /slim_jre $JAVA_HOME

# Copy the compiled app into Alpine image
COPY --from=builder build/target/quickdrop-0.0.1-SNAPSHOT.jar app/quickdrop.jar

WORKDIR /app

VOLUME ["/app/db", "/app/log", "/app/files"]

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/quickdrop.jar"]