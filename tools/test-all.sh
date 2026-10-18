#!/usr/bin/env bash
# Runs every test in the repository. P1: the server tests. P3 adds the apps.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "== server tests"
cd "$ROOT/server"
./mvnw -q test

echo "== all tests passed"
