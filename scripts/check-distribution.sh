#!/usr/bin/env bash
#
# The constraints public distribution depends on (spec §3).
#
# These are not style checks. Headroom is distributed publicly on the promise
# that it contains no Anthropic identifiers of any kind, and that a credential
# cannot reach a log. Both are one careless line away from being untrue, so
# they are enforced rather than remembered.
#
# Runs identically on a laptop and in CI.

set -euo pipefail

cd "$(dirname "$0")/.."

status=0

fail() {
    printf '\n  FAIL: %s\n' "$1"
    status=1
}

# Scoped to the app deliberately. The generator runs on the user's own
# machine and must know where Claude Code keeps its credentials - that is its
# entire job. It is the shipped app that may carry no identifiers.
printf 'Checking the app for provider identifiers... '
if hits=$(grep -rniE 'anthropic|claude\.ai|sk-ant|client_id *= *"' \
        app/src/main domain/src/main 2>/dev/null \
        | grep -v 'dev.andrii.headroom'); then
    fail "provider identifiers must travel in the scanned payload, never in source"
    printf '%s\n' "$hits"
else
    printf 'clean\n'
fi

# Also app-only: the generator prints by design, which is how the QR reaches
# a terminal at all.
printf 'Checking the app for logging calls... '
if hits=$(grep -rniE 'Log\.[dviwe]\(|println\(' \
        app/src/main domain/src/main 2>/dev/null); then
    fail "a credential must not be able to reach a log; review each of these"
    printf '%s\n' "$hits"
else
    printf 'clean\n'
fi

printf 'Checking the wire contract still matches the generator... '
if cmp -s tools/tests/fixtures/payload_golden.txt domain/src/test/resources/payload_golden.txt \
   && cmp -s tools/tests/fixtures/payload_golden.txt app/src/test/resources/payload_golden.txt; then
    printf 'identical\n'
else
    fail "the golden payload fixtures have drifted; the app can no longer read what the generator writes"
fi

printf 'Checking a licence and notices are present... '
if [ -f LICENSE ] && [ -f NOTICE ] \
   && [ -f app/src/main/res/raw/atkinson_hyperlegible_ofl.txt ]; then
    printf 'present\n'
else
    fail "LICENSE, NOTICE and the bundled font's OFL text must all ship"
fi

if [ "$status" -eq 0 ]; then
    printf '\nAll distribution checks passed.\n'
else
    printf '\nDistribution checks failed. Do not publish this build.\n'
fi

exit "$status"
