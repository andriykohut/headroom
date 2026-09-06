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

# Scoped to the app deliberately. The CLI runs on the user's own machine and
# must know where Claude Code keeps its credentials - that is its entire job.
# It is the shipped app that may carry no identifiers.
printf 'Checking the app for provider identifiers... '
if hits=$(grep -rniE 'anthropic|claude\.ai|sk-ant|client_id *= *"' \
        app/src/main domain/src/main 2>/dev/null \
        | grep -v 'dev.andrii.headroom'); then
    fail "provider identifiers must travel in the scanned payload, never in source"
    printf '%s\n' "$hits"
else
    printf 'clean\n'
fi

# Also app-only: the CLI prints by design, which is how the QR reaches a
# terminal at all.
printf 'Checking the app for logging calls... '
if hits=$(grep -rniE 'Log\.[dviwe]\(|println\(' \
        app/src/main domain/src/main 2>/dev/null); then
    fail "a credential must not be able to reach a log; review each of these"
    printf '%s\n' "$hits"
else
    printf 'clean\n'
fi

printf 'Checking the wire contract still matches the CLI... '
if cmp -s fixtures/payload_golden.txt domain/src/test/resources/payload_golden.txt \
   && cmp -s fixtures/payload_golden.txt app/src/test/resources/payload_golden.txt; then
    printf 'identical\n'
else
    fail "the golden payload fixtures have drifted; the app can no longer read what the CLI writes"
fi

# The usage endpoint returns roughly twenty keys, including unreleased product
# codenames and a spend object. Only `limits` is drawn, and only `limits` has
# any business being copied onto a relay or cached on a phone. The trim is one
# deleted line away from silently un-happening, so it is checked.
printf 'Checking the pusher still sends only what is drawn... '
if grep -q 'fn trim(' cli/src/push.rs \
   && grep -q 'response.get("limits")' cli/src/push.rs \
   && grep -q 'enrichment_leaves_spending_and_codenames_behind' cli/src/push.rs; then
    printf 'trimmed\n'
else
    fail "cli/src/push.rs no longer trims the usage response to its limits array"
fi

# A relay is reached over HTTPS because the phone sends its shared secret on
# every request. Debug builds relax that so a laptop relay with no certificate
# can be paired against, and the ONLY thing scoping that to debug is which
# directory the file sits in - <debug-overrides> cannot express it. One
# misplaced edit and every release build permits cleartext, silently.
printf 'Checking release builds still refuse cleartext... '
main_config=app/src/main/res/xml/network_security_config.xml
if [ -f "$main_config" ] \
   && grep -q 'cleartextTrafficPermitted="false"' "$main_config" \
   && ! grep -q 'cleartextTrafficPermitted="true"' "$main_config"; then
    printf 'refused\n'
else
    fail "$main_config must set cleartextTrafficPermitted=\"false\" and nothing else"
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
