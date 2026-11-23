#!/bin/sh
set -eu
if [ "$#" -ne 2 ]; then
    echo 'Usage: before-server-start.sh /absolute/config.properties /absolute/server.ini' >&2
    exit 1
fi
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
copy=$(mktemp)
trap 'rm -f -- "$copy"' EXIT HUP INT TERM
cp -- "$script_dir/../42/media/java/WorkshopSentinel.jar" "$copy"
"${JAVA:-java}" -cp "$copy" io.workshopsentinel.StagingBootstrap "$1" "$2"
