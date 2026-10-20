#!/usr/bin/env bash
# Decide which seeds a run of the library simulation (docs/SIMULATION.md) covers, and write them as
# step outputs for simulation.yml: `first`, `seeds` and `steps`.
#
# A manual run takes its inputs (FIRST_SEED empty: start where the run number says). A nightly run
# moves on to seeds no run took before, NIGHTLY_SEEDS of them from (run number - 1) * NIGHTLY_SEEDS
# + 1000, so the seeds CI runs on every push (1..10) are left to it.
set -euo pipefail

seeds="${SEEDS:-${NIGHTLY_SEEDS:?}}"
steps="${STEPS:-${NIGHTLY_STEPS:?}}"
first="${FIRST_SEED:-}"
if [[ -z "$first" ]]; then
  first=$(( (${RUN_NUMBER:?} - 1) * seeds + 1000 ))
fi

for value in "$first" "$seeds" "$steps"; do
  if ! [[ "$value" =~ ^[0-9]+$ ]]; then
    echo "Not a whole number: '$value'" >&2
    exit 1
  fi
done

echo "Simulating seeds $first..$(( first + seeds - 1 )), $steps steps each"
{
  echo "first=$first"
  echo "seeds=$seeds"
  echo "steps=$steps"
} >> "${GITHUB_OUTPUT:?}"
