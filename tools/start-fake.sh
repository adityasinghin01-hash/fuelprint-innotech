#!/usr/bin/env bash
# Starts the server in FAKE mode on port 8080: it plays the recorded scenarios SC1, SC2, SC3 again and again, as
# if live. Ops and cases commands still run. Data goes to data/runtime/fake (wiped at each start; --keep keeps it).
# Usage: tools/start-fake.sh [--keep] [--speed N]
. "$(dirname "$0")/lib.sh"

KEEP=0
SPEED=1
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP=1 ;;
    --speed) SPEED="$2"; shift ;;
    *) echo "Unknown option $1. Usage: tools/start-fake.sh [--keep] [--speed N]" >&2; exit 2 ;;
  esac
  shift
done

DATA="$ROOT/data/runtime/fake"
if [ "$KEEP" = 0 ] && [ -d "$DATA" ]; then
  say "starting with fresh fake data (use --keep to keep it)"
  rm -rf "$DATA"
fi
build_server
say "fake server on http://localhost:8080 (stop with Ctrl+C). Station PINs: server/config/stations.example.json"
cd "$SERVER"
exec java -jar "$JAR" --fp.mode=replay --fp.replay.speed="$SPEED" --fp.data-dir="$DATA"
