FROM maven:3.9.9 AS builder

WORKDIR /build
COPY . .
RUN mvn clean package

FROM amazoncorretto:21.0.6-alpine AS corretto-jdk

RUN apk add --no-cache binutils
RUN $JAVA_HOME/bin/jlink \
         --verbose \
         --add-modules ALL-MODULE-PATH \
         --strip-debug \
         --no-man-pages \
         --no-header-files \
         --compress=zip-4 \
         --output /slim_jre

FROM alpine:latest
ENV JAVA_HOME=/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=corretto-jdk /slim_jre $JAVA_HOME

COPY --from=builder /build/target/quickdrop.jar /app/quickdrop.jar

WORKDIR /app

VOLUME ["/app/db", "/app/log", "/app/files"]

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/quickdrop.jar"]