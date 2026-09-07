#!/bin/sh
# Fail a release whose tag and Cargo.toml disagree.
#
# The app takes its version from the git tag (app/build.gradle.kts) but
# `cargo publish` takes the crate's from Cargo.toml, so the two can drift
# silently and ship an APK and a crate that disagree about what they are.
set -eu

if [ $# -ne 1 ]; then
    echo "usage: check-version.sh <version>   # e.g. v0.2.0 or 0.2.0" >&2
    exit 1
fi

wanted=${1#v}
manifest=$(dirname "$0")/../cli/Cargo.toml
found=$(sed -n 's/^version = "\(.*\)"/\1/p' "$manifest" | head -1)

if [ "$wanted" != "$found" ]; then
    echo "version mismatch: tag says $wanted, $manifest says $found" >&2
    echo "bump the manifest, commit, then tag." >&2
    exit 1
fi
