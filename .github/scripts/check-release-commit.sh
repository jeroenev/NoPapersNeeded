#!/usr/bin/env bash
# Refuses to release the checked-out commit unless CI passed on it, or it is a release bump: a commit
# that changes nothing but the npn.version line of gradle.properties, on top of a main commit CI
# passed on. That is what `mise release` pushes, and the release starts before CI on the bump itself
# has finished. release.yml runs this before it builds anything. Needs the parent commit and
# origin/main in the checkout, and gh with a token that can read the repository's Actions runs.
# What counts as CI passing on a commit is ci-status.sh's, shared with `mise release`.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
# shellcheck source=.github/scripts/ci-status.sh
source .github/scripts/ci-status.sh

props=gradle.properties
main=origin/main

refuse() {
    echo "::error::$*" >&2
    exit 1
}

# Sets ci to ci_status of commit $1.
check_ci() {
    ci=$(ci_status "$1") || refuse "Could not list the CI runs on $1."
}

# Why CI is no evidence for commit $1, after check_ci "$1" found it did not pass.
no_ci() {
    if [ "$ci" = pull-request ]; then
        echo "CI ran on $1 only in a pull request, which tested it merged into its base branch, not $1 itself (run CI on it with 'gh workflow run ci.yml --ref <branch>' and rerun this workflow)"
    else
        echo "CI has not passed on $1"
    fi
}

# gradle.properties at commit $1 without its npn.version line.
props_without_version() {
    git show "$1:$props" | grep -v '^npn\.version=' || true
}

head=$(git rev-parse HEAD)
check_ci "$head"
if [ "$ci" = passed ]; then
    echo "CI passed on $head."
    exit 0
fi

parent=$(git rev-parse --verify --quiet "$head^") ||
    refuse "$(no_ci "$head"), and its parent is not in the checkout."
changed=$(git diff --no-renames --name-only "$parent" "$head")
if [ "$changed" != "$props" ] || ! cmp -s <(props_without_version "$parent") <(props_without_version "$head"); then
    refuse "$(no_ci "$head"), and it changes more than npn.version. Release with 'mise release <version>'."
fi
git rev-parse --verify --quiet "$main^{commit}" >/dev/null || refuse "$main is not in the checkout."
git merge-base --is-ancestor "$parent" "$main" ||
    refuse "$head only changes npn.version, but its parent $parent is not on main."
check_ci "$parent"
[ "$ci" = passed ] || refuse "$head only changes npn.version, but $(no_ci "$parent")."
echo "$head only changes npn.version, on top of $parent, which is on main and passed CI."
