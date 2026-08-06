# syntax=docker/dockerfile:1

# Stage 1 — build the application jar
# Pinned explicitly: the previous "maven:3.9.9" tag resolved its JDK by Docker Hub
# alias, so the build's Java version could drift without a commit here.
FROM maven:3.9.11-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Cached layer: only reruns when pom.xml changes.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package

# Stage 2 — build a minimal JRE containing only the modules we actually use
FROM amazoncorretto:21.0.6-alpine AS jre-builder

RUN apk add --no-cache binutils

# Replaces ALL-MODULE-PATH (all 69 modules, ~98 MB). Base list is from
# `jdeps --print-module-deps` against the packaged jar; jdk.crypto.ec,
# java.naming, java.security.sasl, java.management and jdk.zipfs are added on
# top because they're reached reflectively/via service loaders and jdeps
# misses them. jdk.crypto.ec matters most: without it, TLS to S3/Azure/SMTP
# fails to negotiate. Re-run jdeps after adding a dependency rather than guessing.
RUN "$JAVA_HOME/bin/jlink" \
        --add-modules java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.xml,jdk.crypto.ec,jdk.jfr,jdk.management,jdk.net,jdk.unsupported,jdk.xml.dom,jdk.zipfs,jdk.localedata \
        --include-locales=en,de,es,fr,it,bg,ja,zh \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress=zip-9 \
        --output /slim_jre

# Stage 3 — runtime
FROM alpine:3.22

ENV JAVA_HOME=/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=jre-builder /slim_jre $JAVA_HOME
COPY --from=builder /build/target/quickdrop.jar /app/quickdrop.jar

WORKDIR /app

VOLUME ["/app/db", "/app/db-backups", "/app/log", "/app/files"]

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/quickdrop.jar"]
