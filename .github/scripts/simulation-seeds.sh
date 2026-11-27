#!/usr/bin/env bash
# Decide which seeds a run of the library simulation (docs/SIMULATION.md) covers, and write them as
# step outputs for simulation.yml: `first`, `seeds` and `steps`.
#
# A run takes its inputs. With FIRST_SEED empty it takes the day's block: SEEDS seeds from
# 1000 + (days since the epoch) * SEEDS, so a run on a later day covers fresh seeds, and the seeds CI
# runs on every push (1..10) are left to it. Two runs on one day with the same SEEDS repeat the block.
#
# A run must fit in the Simulate step's 30 minutes: SEEDS * STEPS may be at most MAX_SEED_STEPS, or
# the run is refused here, before anything is built, rather than cut off by the timeout with no
# result. A hosted runner simulated 100 seeds of 400 steps in 18 to 22 minutes; the budget, 80 seeds
# of 400 steps, leaves room for a slower runner. More seeds take several runs, each with its own
# FIRST_SEED.
set -euo pipefail

MAX_SEED_STEPS=32000

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

if (( seeds * steps > MAX_SEED_STEPS )); then
  echo "$seeds seeds of $steps steps is more than one run fits in 30 minutes" \
    "(seeds * steps at most $MAX_SEED_STEPS, e.g. $(( MAX_SEED_STEPS / steps )) seeds of $steps steps)." \
    "Start several runs, each with its own first_seed." >&2
  exit 1
fi

echo "Simulating seeds $first..$(( first + seeds - 1 )), $steps steps each"
{
  echo "first=$first"
  echo "seeds=$seeds"
  echo "steps=$steps"
} >> "${GITHUB_OUTPUT:?}"
