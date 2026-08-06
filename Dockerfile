# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 — build the application jar
# ---------------------------------------------------------------------------
# Pinned to an explicit Maven + JDK combination. The previous `maven:3.9.9` tag
# resolved its JDK by Docker Hub alias convention, so the Java version used to
# build could change without any commit here.
FROM maven:3.9.11-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Resolve dependencies against the pom alone first. This layer is cached and
# only re-runs when the pom changes, so ordinary source edits no longer
# re-download the entire dependency tree.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package

# ---------------------------------------------------------------------------
# Stage 2 — build a minimal JRE containing only the modules we actually use
# ---------------------------------------------------------------------------
FROM amazoncorretto:21.0.6-alpine AS jre-builder

RUN apk add --no-cache binutils

# The module list below replaces ALL-MODULE-PATH, which shipped all 69 JDK
# modules (~98 MB) regardless of use. It is the output of
#   jdeps --multi-release 21 --ignore-missing-deps --print-module-deps ...
# run against the packaged jar, plus the modules below that jdeps cannot see
# because they are reached reflectively or through service loaders:
#   java.management   — Actuator / JMX
#   java.naming       — JNDI, used by the mail stack
#   java.security.sasl— SMTP authentication
#   java.sql          — JDBC (SQLite driver)
#   jdk.crypto.ec     — elliptic-curve TLS; without it HTTPS handshakes to S3,
#                       Azure and most SMTP servers fail to negotiate
#   jdk.zipfs         — ZIP FileSystemProvider, used by folder uploads
#   jdk.localedata    — non-English locale data; java.base only carries English,
#                       and QuickDrop ships eight languages
# If a new dependency is added, re-run jdeps rather than guessing.
RUN "$JAVA_HOME/bin/jlink" \
        --add-modules java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.xml,jdk.crypto.ec,jdk.jfr,jdk.management,jdk.net,jdk.unsupported,jdk.xml.dom,jdk.zipfs,jdk.localedata \
        --include-locales=en,de,es,fr,it,bg,ja,zh \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress=zip-9 \
        --output /slim_jre

# ---------------------------------------------------------------------------
# Stage 3 — runtime
# ---------------------------------------------------------------------------
FROM alpine:3.22

ENV JAVA_HOME=/jre
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY --from=jre-builder /slim_jre $JAVA_HOME
COPY --from=builder /build/target/quickdrop.jar /app/quickdrop.jar

WORKDIR /app

VOLUME ["/app/db", "/app/log", "/app/files"]

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/quickdrop.jar"]
