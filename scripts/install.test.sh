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

# --- expected_sum(): the lookup that decides whether an unverified binary
# reaches PATH. Fixtures only, no network. ---

sums_dir=$(mktemp -d)
trap 'rm -rf "$sums_dir"' EXIT

sums_ok="$sums_dir/SHA256SUMS"
cat > "$sums_ok" <<'SUMS'
aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  headroom-aarch64-apple-darwin
bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb  headroom-x86_64-unknown-linux-musl
SUMS

check "exact match returns the hash" \
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
    "$(expected_sum "$sums_ok" headroom-aarch64-apple-darwin)"

sums_old="$sums_dir/SHA256SUMS.old-only"
cat > "$sums_old" <<'SUMS'
cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc  headroom-aarch64-apple-darwin-old
SUMS

check "a longer entry must not satisfy a shorter request" \
    "" \
    "$(expected_sum "$sums_old" headroom-aarch64-apple-darwin)"

check "an asset absent from the file yields nothing" \
    "" \
    "$(expected_sum "$sums_ok" headroom-does-not-exist)"

sums_empty="$sums_dir/SHA256SUMS.empty"
: > "$sums_empty"

check "an empty SHA256SUMS yields nothing" \
    "" \
    "$(expected_sum "$sums_empty" headroom-aarch64-apple-darwin)"

[ "$failures" -eq 0 ] || exit 1
echo "all installer checks passed"
