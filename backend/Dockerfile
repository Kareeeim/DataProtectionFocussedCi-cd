# ===========================================================================
#  DemoBank API - multi-stage Docker build
# ===========================================================================
#  Stage 1 compiles the application with a full JDK + Maven toolchain, and
#  carries an extra `test` stage used only by the test runner.
#  Stage 2 ships only the runnable jar on a minimal JRE image, which keeps the
#  final image small and - more importantly - removes the compiler, the build
#  cache and the Maven repository from the runtime attack surface.
# ===========================================================================

# ---------------------------------------------------------------------------
# Stage 1 - dependencies
# ---------------------------------------------------------------------------
# The POM alone, so that the Maven repository is its own layer and day-to-day
# source changes do not trigger a full dependency download. Both stages below
# start from it and therefore share that layer.
FROM maven:3.9-eclipse-temurin-21 AS dependencies

WORKDIR /build

COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline

# ---------------------------------------------------------------------------
# Stage 1b - test
# ---------------------------------------------------------------------------
# Dependencies plus the sources, and nothing else. Never part of a published
# image: `docker build` stops at the last stage, which is `runtime`. Run it with
#
#     docker compose --profile tests run --rm backend-tests
#
# The suite runs on H2 in memory under the `test` profile, so it needs no
# database container, no port and no credentials.
FROM dependencies AS test

COPY src ./src
CMD ["mvn", "-B", "-ntp", "test"]

# ---------------------------------------------------------------------------
# Stage 1c - build
# ---------------------------------------------------------------------------
# Tests are skipped here on purpose: an image build must never silently depend
# on a test run, and vice versa. The pipeline runs the two as separate jobs, so
# a red suite cannot be hidden behind a green build.
FROM dependencies AS build

COPY src ./src
RUN mvn -B -DskipTests clean package \
    && cp target/demobank-api-*.jar /build/app.jar

# ---------------------------------------------------------------------------
# Stage 2 - runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# Apply the latest OS security patches available for the base image.
RUN apk --no-cache upgrade

# Run as an unprivileged, dedicated system user: if the process is ever
# compromised it owns nothing on the filesystem and cannot install packages.
RUN addgroup -S -g 10001 demobank \
 && adduser  -S -u 10001 -G demobank -s /sbin/nologin -H -D demobank

WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar

# The application reads its configuration and secrets from the environment
# (see docker-compose.yml / .env.example). Nothing secret is baked in here.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

USER 10001:10001
EXPOSE 8080

# `exec` form: the JVM becomes PID 1 and receives Docker's stop signals.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
