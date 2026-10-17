# shellcheck shell=bash
# How ci.yml went on a commit, for the two places that refuse to release a commit CI has not passed
# on: `mise release` (.mise/tasks/release) and the release workflow's check-release-commit.sh. Both
# source this file from the repository root. Needs gh with a token that can read the Actions runs.
#
# CI passed on a commit when a ci.yml run on a push or a manual dispatch tested it and completed
# successfully. A pull_request run is listed under the pull request's head commit too, but it tested
# that commit merged into its base branch, so its success says nothing about the commit alone.

# Prints how ci.yml went on commit $1, from its push and manual runs:
#   passed        one of them completed successfully
#   running       none passed, and one has not completed yet
#   failed        all of them completed, none successfully
#   pull-request  there are none, only pull_request runs
#   none          ci.yml never ran on it
# Fails when gh cannot list the runs.
ci_status() {
    local runs own
    runs=$(gh run list --workflow ci.yml --commit "$1" --json event,status,conclusion \
        --jq '.[] | "\(.event) \(.status) \(.conclusion)"') || return 1
    own=$(sed -nE 's/^(push|workflow_dispatch) //p' <<<"$runs")
    if grep -qx "completed success" <<<"$own"; then
        echo passed
    elif [ -n "$own" ]; then
        if grep -qv "^completed" <<<"$own"; then echo running; else echo failed; fi
    elif [ -n "$runs" ]; then
        echo pull-request
    else
        echo none
    fi
}
