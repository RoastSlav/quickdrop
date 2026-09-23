#!/bin/sh
set -e

PUID="${PUID:-1000}"
PGID="${PGID:-1000}"

CURRENT_UID="$(id -u quickdrop)"
CURRENT_GID="$(id -g quickdrop)"

# Remap the quickdrop user/group in place rather than creating a new one, so files
# already chowned to the old UID/GID (from a previous run with a different PUID/PGID)
# are still owned by "quickdrop" as far as the rest of this script is concerned.
if [ "$PUID" != "$CURRENT_UID" ] || [ "$PGID" != "$CURRENT_GID" ]; then
    sed -i "s/^\(quickdrop:[^:]*:\)[0-9]*:[0-9]*/\1${PUID}:${PGID}/" /etc/passwd
    sed -i "s/^\(quickdrop:[^:]*:\)[0-9]*/\1${PGID}/" /etc/group
fi

# Only chown when ownership doesn't already match: files/ can hold a large existing
# upload store, and a recursive chown on every container start would be slow. This only
# inspects each top-level directory, not its full contents, so a partially-mismatched
# tree (e.g. one file left over from a manual edit) won't be caught -- an acceptable
# trade-off given the alternative is a full recursive stat on every boot.
for dir in /app/db /app/log /app/files; do
    mkdir -p "$dir"
    owner="$(stat -c '%u:%g' "$dir")"
    if [ "$owner" != "${PUID}:${PGID}" ]; then
        chown -R quickdrop:quickdrop "$dir"
    fi
done

exec su-exec quickdrop:quickdrop "$@"
