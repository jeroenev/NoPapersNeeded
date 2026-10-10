#!/usr/bin/env bash
# Refuses to release the checked-out commit unless CI passed on it, or it is a release bump: a commit
# that changes nothing but the npn.version line of gradle.properties, on top of a main commit CI
# passed on. That is what `mise release` pushes, and the release starts before CI on the bump itself
# has finished. release.yml runs this before it builds anything. Needs the parent commit and
# origin/main in the checkout, and gh with a token that can read the repository's Actions runs.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

props=gradle.properties
main=origin/main

refuse() {
    echo "::error::$*" >&2
    exit 1
}

# Whether ci.yml completed successfully on commit $1.
ci_passed() {
    local runs
    runs=$(gh run list --workflow ci.yml --commit "$1" --json status,conclusion \
        --jq '.[] | "\(.status) \(.conclusion)"') || refuse "Could not list the CI runs on $1."
    grep -qx "completed success" <<<"$runs"
}

# gradle.properties at commit $1 without its npn.version line.
props_without_version() {
    git show "$1:$props" | grep -v '^npn\.version=' || true
}

head=$(git rev-parse HEAD)
if ci_passed "$head"; then
    echo "CI passed on $head."
    exit 0
fi

parent=$(git rev-parse --verify --quiet "$head^") ||
    refuse "CI has not passed on $head, and its parent is not in the checkout."
changed=$(git diff --no-renames --name-only "$parent" "$head")
if [ "$changed" != "$props" ] || ! cmp -s <(props_without_version "$parent") <(props_without_version "$head"); then
    refuse "CI has not passed on $head, and it changes more than npn.version. Release with 'mise release <version>'."
fi
git rev-parse --verify --quiet "$main^{commit}" >/dev/null || refuse "$main is not in the checkout."
git merge-base --is-ancestor "$parent" "$main" ||
    refuse "$head only changes npn.version, but its parent $parent is not on main."
ci_passed "$parent" ||
    refuse "$head only changes npn.version, but CI has not passed on its parent $parent."
echo "$head only changes npn.version, on top of $parent, which is on main and passed CI."
