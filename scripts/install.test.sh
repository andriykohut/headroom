#!/bin/sh
# The installer's decisions, exercised without a network.
set -eu

HEADROOM_INSTALL_LIB=1
export HEADROOM_INSTALL_LIB
# shellcheck source=SCRIPTDIR/install.sh
. "$(dirname "$0")/install.sh"

failures=0
check() {
    # check <description> <expected> <actual>
    if [ "$2" = "$3" ]; then
        echo "ok   $1"
    else
        echo "FAIL $1: expected '$2', got '$3'" >&2
        failures=$((failures + 1))
    fi
}

check "mac arm"    "aarch64-apple-darwin"        "$(detect_target Darwin arm64)"
check "mac intel"  "x86_64-apple-darwin"         "$(detect_target Darwin x86_64)"
check "linux x86"  "x86_64-unknown-linux-musl"   "$(detect_target Linux x86_64)"
check "linux arm"  "aarch64-unknown-linux-musl"  "$(detect_target Linux aarch64)"
check "linux arm64 spelling" "aarch64-unknown-linux-musl" "$(detect_target Linux arm64)"

# An unsupported platform must fail loudly rather than download something wrong.
if detect_target OpenBSD vax >/dev/null 2>&1; then
    echo "FAIL unsupported platform was accepted" >&2
    failures=$((failures + 1))
else
    echo "ok   unsupported platform is refused"
fi

[ "$failures" -eq 0 ] || exit 1
echo "all installer checks passed"
