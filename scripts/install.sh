#!/bin/sh
# Install the headroom CLI.
#
#   curl -fsSL https://raw.githubusercontent.com/andriykohut/headroom/main/scripts/install.sh | sh
#
# Honours HEADROOM_VERSION (default: the latest release), HEADROOM_INSTALL_DIR
# (default: ~/.local/bin) and HEADROOM_DRY_RUN=1 (print the URL, install
# nothing). Never needs sudo.
set -eu

REPO=andriykohut/headroom

detect_target() {
    os=$1
    arch=$2
    case "$os $arch" in
        "Darwin arm64")            echo "aarch64-apple-darwin" ;;
        "Darwin x86_64")           echo "x86_64-apple-darwin" ;;
        "Linux x86_64")            echo "x86_64-unknown-linux-musl" ;;
        "Linux aarch64"|"Linux arm64") echo "aarch64-unknown-linux-musl" ;;
        *)
            echo "headroom: no prebuilt binary for $os $arch." >&2
            echo "Build from source instead: https://github.com/$REPO" >&2
            return 1
            ;;
    esac
}

# expected_sum <sumsfile> <asset>
#
# Requires GNU-coreutils-format lines: "<hash>  <basename>" (two spaces, no
# directory component). The release job that produces SHA256SUMS runs
# `sha256sum` on an Ubuntu runner specifically so its output matches this -
# macOS `shasum -a 256` formats its own manifest lines the same way, but if
# that generating job ever moved to macOS and used a different tool, this
# lookup would silently stop matching. The trailing `\$` anchor also matters:
# it stops a manifest entry for "$asset-old" (or any other longer name that
# merely starts with $asset) from satisfying a lookup for "$asset".
expected_sum() {
    sumsfile=$1
    asset=$2
    grep " $asset\$" "$sumsfile" | cut -d' ' -f1
}

latest_version() {
    # The releases/latest page redirects to the tag. No API, no rate limit.
    url=$(curl -fsSLI -o /dev/null -w '%{url_effective}' \
        "https://github.com/$REPO/releases/latest")
    version=${url##*/}
    # A repo with no releases yet redirects /releases/latest to /releases (the
    # index page), not to a tag, so ${url##*/} would return the literal
    # string "releases" instead of a version. Refuse to turn that into a
    # download URL.
    case "$version" in
        v[0-9]*) echo "$version" ;;
        *)
            echo "headroom: no published release found for $REPO." >&2
            exit 1
            ;;
    esac
}

main() {
    if ! command -v curl >/dev/null 2>&1; then
        echo "headroom: curl is required to install headroom but was not found on PATH." >&2
        exit 1
    fi

    target=$(detect_target "$(uname -s)" "$(uname -m)")

    if [ -n "${HEADROOM_VERSION:-}" ]; then
        version=$HEADROOM_VERSION
        case "$version" in
            v*) ;;
            *) version="v$version" ;;
        esac
    else
        version=$(latest_version)
    fi

    base="https://github.com/$REPO/releases/download/$version"
    asset="headroom-$target"

    if [ "${HEADROOM_DRY_RUN:-}" = "1" ]; then
        echo "$base/$asset"
        return 0
    fi

    dir=${HEADROOM_INSTALL_DIR:-$HOME/.local/bin}
    mkdir -p "$dir"

    # Staged on the destination filesystem, not under mktemp -d's $TMPDIR:
    # $TMPDIR is commonly tmpfs while $dir is not, and a cross-filesystem `mv`
    # degrades from a single rename(2) to copy-then-unlink. If that copy were
    # interrupted partway, chmod'ing before that copy would leave a broken but
    # executable "headroom" already sitting on PATH under its final name.
    # Staging here and chmod'ing only right before a same-filesystem rename
    # keeps the visible move atomic, and the trap below removes the partial
    # file on any exit so a failed install never leaves it behind.
    partial="$dir/.headroom.partial"
    sums=$(mktemp)
    trap 'rm -f "$partial" "$sums"' EXIT

    echo "headroom: fetching $version for $target"
    curl -fsSL "$base/$asset" -o "$partial"
    curl -fsSL "$base/SHA256SUMS" -o "$sums"

    # Verify before installing, never after: the point of a checksum is to
    # decide whether this file is allowed to become an executable on PATH.
    expected=$(expected_sum "$sums" "$asset")
    if [ -z "$expected" ]; then
        echo "headroom: $asset is not listed in SHA256SUMS; refusing to install." >&2
        exit 1
    fi
    if command -v sha256sum >/dev/null 2>&1; then
        actual=$(sha256sum "$partial" | cut -d' ' -f1)
    else
        actual=$(shasum -a 256 "$partial" | cut -d' ' -f1)
    fi
    if [ "$expected" != "$actual" ]; then
        echo "headroom: checksum mismatch; refusing to install." >&2
        echo "  expected $expected" >&2
        echo "  got      $actual" >&2
        exit 1
    fi

    chmod +x "$partial"
    mv "$partial" "$dir/headroom"
    echo "headroom: installed to $dir/headroom"

    case ":$PATH:" in
        *":$dir:"*) ;;
        *) echo "headroom: $dir is not on your PATH; add it to use 'headroom'." >&2 ;;
    esac
}

# Sourced by the tests to reach detect_target and expected_sum without
# installing anything.
[ "${HEADROOM_INSTALL_LIB:-}" = "1" ] || main "$@"
