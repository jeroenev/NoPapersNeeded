#!/usr/bin/env bash
# Decide which seeds a run of the library simulation (docs/SIMULATION.md) covers, and write them as
# step outputs for simulation.yml: `first`, `seeds` and `steps`.
#
# A run takes its inputs. With FIRST_SEED empty it takes the day's block: SEEDS seeds from
# 1000 + (days since the epoch) * SEEDS, so a run on a later day covers fresh seeds, and the seeds CI
# runs on every push (1..10) are left to it. Two runs on one day with the same SEEDS repeat the block.
set -euo pipefail

seeds="${SEEDS:?}"
steps="${STEPS:?}"
first="${FIRST_SEED:-}"
if [[ -z "$first" ]]; then
  day=$(( $(date -u +%s) / 86400 ))
  first=$(( 1000 + day * seeds ))
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
