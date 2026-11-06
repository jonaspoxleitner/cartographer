#!/bin/sh
# Runtime entrypoint. The image starts as root so it can take ownership of the
# mounted data directories, then drops to PUID/PGID and runs the server as that
# user. Neither the uid nor the gid needs a /etc/passwd entry, so the same image
# serves any host uid without a rebuild.
#
# Set PUID/PGID at run time (compose forwards them from the environment); the
# defaults match the fallback `cartographer` user the image ships.
set -eu

JAR=/app/cartographer-server.jar
PUID="${PUID:-10001}"
PGID="${PGID:-10001}"

if [ "$(id -u)" -ne 0 ]; then
  # Already unprivileged (docker run --user, or an orchestrator that sets its own
  # security context): there is nothing to hand over, just start the server.
  exec java -jar "$JAR" "$@"
fi

# The named tile volume keeps the ownership it was created with, so re-chowning
# here is what lets a changed PUID/PGID work without dropping the volume. The
# paths are the ones the image's ENV points the server at.
mkdir -p /data/routes /data/tiles

# Deliberately split. /data/routes is a bind mount of the host's routes/
# directory and contains host-owned user data (the GPX files), so it is chowned
# non-recursively: owning the directory is enough to create uploaded files in
# it, and the existing files must never change ownership. /data/tiles is the
# named volume holding the MBTiles file plus its -wal/-shm siblings, and the
# tile cache has to rewrite all of them, so it is chowned recursively.
chown "$PUID:$PGID" /data /data/routes
chown -R "$PUID:$PGID" /data/tiles

# --clear-groups drops the supplementary groups along with the ids; without it
# setpriv would have to look up the user's groups in /etc/group, which an
# arbitrary host uid has no entry in.
exec setpriv --reuid="$PUID" --regid="$PGID" --clear-groups java -jar "$JAR" "$@"
