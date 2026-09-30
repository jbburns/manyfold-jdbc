# GUI validation image. NOT part of CI. Build from the repository root:
#
#   docker build -f validation/Dockerfile -t manyfold-gui-validation .
#   docker run --rm -v "$PWD/validation/out:/out" manyfold-gui-validation
#
# Screenshots and RESULT.txt land in ./validation/out. The container exits non-zero on FAIL.

# Stage 1: build the driver bundle (manyfold jar + H2 jar) with the repository's Gradle wrapper.
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /src
COPY . .
RUN ./gradlew clientBundle --no-daemon -q

# Stage 2: SQuirreL SQL, a virtual X server and the tools that drive it.
FROM eclipse-temurin:21-jre
ARG SQUIRREL_VERSION=5.1.0
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      xvfb xdotool imagemagick x11-utils fonts-dejavu curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/validation
COPY README.md ./README.md
COPY validation/run.sh validation/auto-install.xml ./validation/
COPY --from=builder /src/build/client /opt/client

# Install SQuirreL at build time with the same script the container runs later.
# run.sh pins SQUIRREL_VERSION itself; the ARG only has to match it, so fail if it does not.
ENV CACHE_DIR=/opt/cache \
    SQUIRREL_HOME=/opt/squirrel-sql \
    BUNDLE_DIR=/opt/client \
    OUT_DIR=/out \
    SKIP_BUILD=1
RUN grep -q "^SQUIRREL_VERSION=${SQUIRREL_VERSION}\$" validation/run.sh \
 && chmod +x validation/run.sh \
 && validation/run.sh --install-only \
 && rm -rf /opt/cache

VOLUME /out
ENTRYPOINT ["/opt/validation/validation/run.sh"]
