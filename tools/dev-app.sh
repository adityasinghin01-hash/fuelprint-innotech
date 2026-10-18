#!/usr/bin/env bash
# Runs one app with live reload on http://localhost:5173/<app>/, talking to the server on port 8080
# (start it first with tools/start-fake.sh). Usage: tools/dev-app.sh operations|cases|field|alerts
. "$(dirname "$0")/lib.sh"

APP="${1:-}"
case "$APP" in
  operations|cases|field|alerts) ;;
  *) echo "Usage: tools/dev-app.sh operations|cases|field|alerts" >&2; exit 2 ;;
esac
if [ ! -f "$APPS/$APP/package.json" ]; then
  echo "apps/$APP has no app yet (its job creates it)." >&2
  exit 1
fi
install_apps
say "open http://localhost:5173/$APP/"
cd "$APPS"
exec npm run dev --workspace "$APP"
