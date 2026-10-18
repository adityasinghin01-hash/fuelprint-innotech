#!/usr/bin/env bash
# Runs every test: the Java server tests, every app's type check and Vitest tests, and the headless end-to-end test
# against a fake-mode server. Usage: tools/test-all.sh [--no-e2e]
. "$(dirname "$0")/lib.sh"

E2E=1
[ "${1:-}" = "--no-e2e" ] && E2E=0

say "server tests"
(cd "$SERVER" && ./mvnw -q package)

if [ -f "$APPS/package.json" ]; then
  install_apps
  say "screen type checks"
  (cd "$APPS" && npm run typecheck --silent)
  say "screen tests"
  (cd "$APPS" && npm test --silent)
  if [ "$E2E" = 1 ]; then
    "$ROOT/tools/test-e2e.sh"
  fi
fi

say "all tests passed"
