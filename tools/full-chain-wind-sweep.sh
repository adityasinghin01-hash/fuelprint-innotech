#!/usr/bin/env bash
set -euo pipefail
# Fixed reviewed baseline cutoff; every cell has a distinct predeclared FPWIND seed range.
# Usage: tools/full-chain-wind-sweep.sh OUTPUT_DIRECTORY BASELINE_CUTOFF [ELIGIBLE_PER_CELL=60]
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
output_dir=${1:?Supply an output directory outside runtime resources}
baseline_cutoff=${2:?Supply the reviewed baseline cutoff}
requested_eligible=${3:-60}
mkdir -p "$output_dir"
output_dir=$(cd -- "$output_dir" && pwd)
cd "$repo_root/server"
./mvnw -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/full-chain-classpath.txt
full_chain_classpath="target/classes:$(cat target/full-chain-classpath.txt)"
expected_algorithm=$(java -cp "$full_chain_classpath" fuelprint.science.calibration.FullChainCalibrationRunner version)
for direction_sd in 0.5 1 3 5; do
    for time_scale in 30 200 600; do
        cell_output="$output_dir/wind-sd-${direction_sd}-tau-${time_scale}.json"
        if [[ -s "$cell_output" ]] && python3 - "$cell_output" "$requested_eligible" "$baseline_cutoff" "$direction_sd" "$time_scale" "$expected_algorithm" <<'PY'
import json, sys
try:
    data = json.load(open(sys.argv[1]))
    matches = (data.get('algorithm') == sys.argv[6] and data.get('mode') == 'sensitivity' and data.get('requested_eligible_reached') is True
        and data.get('requested') == int(sys.argv[2]) and data.get('cutoff') == float(sys.argv[3])
        and data.get('domain', {}).get('directionSdDeg') == float(sys.argv[4])
        and data.get('domain', {}).get('windTimeScaleS') == float(sys.argv[5]))
except (OSError, ValueError, TypeError):
    matches = False
sys.exit(0 if matches else 1)
PY
        then
            echo "Completed matching cell retained: $cell_output"
            continue
        fi
        echo "Starting fixed-cutoff wind cell SD=$direction_sd tau=$time_scale"
        java -Xmx2g -cp "$full_chain_classpath" fuelprint.science.calibration.FullChainCalibrationRunner \
            sensitivity "$requested_eligible" "$cell_output" "$direction_sd" "$time_scale" "$baseline_cutoff"
    done
done
