JAR = cartographer-cli/target/cartographer-cli-1.0-SNAPSHOT.jar
SERVER_JAR = cartographer-server/target/cartographer-server-1.0-SNAPSHOT.jar

# The container writes into the bind-mounted routes/ directory, so on a host that
# checks those writes against its own permissions (Docker Engine on Linux) it has
# to run as the host user. The `docker` target forwards the host ids to the
# container, where the entrypoint drops to them before starting the server.
HOST_UID := $(shell id -u)
HOST_GID := $(shell id -g)

.PHONY: run run-server package clean docker docker-down docker-logs

run: package
	java -jar $(JAR)

run-server: package
	java -jar $(SERVER_JAR)

package:
	mvn package

clean:
	mvn clean

docker:
	PUID=$(HOST_UID) PGID=$(HOST_GID) docker compose up --build -d

docker-down:
	docker compose down

docker-logs:
	docker compose logs -f
