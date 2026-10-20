#!/usr/bin/env bash
set -euo pipefail
# Run serially with any other heavy Maven/science workload.
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root/server"
./mvnw -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/full-chain-classpath.txt
full_chain_classpath="target/classes:$(cat target/full-chain-classpath.txt)"
exec java -Xmx2g -cp "$full_chain_classpath" fuelprint.science.calibration.FullChainCalibrationRunner "$@"
