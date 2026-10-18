#!/usr/bin/env bash
# The stall: builds every app, then starts the real server on 0.0.0.0:8080 serving /operations/, /cases/, /field/,
# /alerts/. Needs server/config/stations.json with the real PINs (never committed); the example PINs are refused.
. "$(dirname "$0")/lib.sh"

if [ ! -f "$SERVER/config/stations.json" ]; then
  echo "Missing server/config/stations.json. Copy server/config/stations.example.json and set real PINs." >&2
  exit 1
fi
install_apps
say "building the apps"
(cd "$APPS" && npm run build --silent)
build_server
IP="$(ipconfig getifaddr en0 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}' || echo 'this-laptop')"
say "stall server: http://$IP:8080/operations/ (cases, field, alerts the same way). Stop with Ctrl+C"
cd "$SERVER"
exec java -jar "$JAR" --spring.profiles.active=real --fp.stations.allow-example=false "$@"
