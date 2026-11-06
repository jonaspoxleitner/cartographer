# Multi-stage build for the Spring Boot server: Maven and the JDK stay in the
# build stage, the runtime image is a plain JRE.

# Stage 1: build the repackaged server jar. The pom files are copied on their
# own first so a source-only change reuses the cached dependency download layer.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml ./
COPY cartographer-core/pom.xml cartographer-core/
COPY cartographer-cli/pom.xml cartographer-cli/
COPY cartographer-server/pom.xml cartographer-server/
RUN mvn -B -ntp dependency:go-offline

COPY cartographer-core/src cartographer-core/src
COPY cartographer-cli/src cartographer-cli/src
COPY cartographer-server/src cartographer-server/src
# Tests are skipped on purpose: GpxRouteLoaderTest in cartographer-core reads
# routes/komoot-4321335.gpx from the repository root, and routes/ is git-ignored user
# data that is excluded from the build context, so the build must not need it.
# Run the tests locally with `make package`.
RUN mvn -B -ntp package -DskipTests

# Stage 2: runtime. Debian-based on purpose: sqlite-jdbc bundles native SQLite
# linked against glibc, which a musl (Alpine) image would break.
FROM eclipse-temurin:21-jre

# curl backs the container healthcheck against GET /tiles/tilejson.json. This base
# image ships it already; installing it explicitly keeps the image self-contained.
# setpriv is asserted here because the entrypoint's privilege drop depends on it:
# a base-image change has to fail the build instead of producing an image that
# cannot switch to the host uid at run time.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && command -v setpriv

# The server writes here: uploaded .gpx files and the MBTiles tile cache. Both
# are mounted over at run time, so the directories exist for a bare docker run.
#
# The uid/gid the container runs as is a run-time decision (PUID/PGID, see the
# entrypoint), not a build-time one, because it has to match the host user that
# owns the bind-mounted routes/ directory. The `cartographer` user below is only
# the fallback for the no-env and `docker run --user cartographer` cases, so its
# ids are fixed rather than taken from build args.
#
# An existing group id (the base image ships system groups) is reused instead of
# failing the build; the ids, not the names, do the work.
RUN getent group 10001 > /dev/null || groupadd --gid 10001 cartographer \
 && useradd --create-home --uid 10001 --gid 10001 --shell /usr/sbin/nologin cartographer \
 && mkdir -p /data/routes /data/tiles \
 && chown -R 10001:10001 /data

# Absolute paths sidestep the working-directory-relative resolution of the
# `routes` and `tiles.mbtiles` defaults.
ENV CARTOGRAPHER_ROUTES_DIR=/data/routes \
    CARTOGRAPHER_MBTILES_FILE=/data/tiles/tiles.mbtiles

WORKDIR /app
COPY --from=build /build/cartographer-server/target/cartographer-server-*.jar /app/cartographer-server.jar

# The image deliberately stays on the default (root) user: the entrypoint needs
# it to chown the mounted /data directories before it drops to PUID/PGID. What
# the server itself runs as is decided there, not here.
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod 0755 /usr/local/bin/docker-entrypoint.sh

EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
