#!/usr/bin/env bash
# Starts the real server on port 8080 with the Spring profile "real": the real science, field, ai and alerts beans
# (@Profile("!stub")) take over from the stubs. Data in data/runtime. Before join day, use start-fake.sh.
. "$(dirname "$0")/lib.sh"

build_server
say "real server on http://localhost:8080 (stop with Ctrl+C)"
cd "$SERVER"
exec java -jar "$JAR" --spring.profiles.active=real "$@"
