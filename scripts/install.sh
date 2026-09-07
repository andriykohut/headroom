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

latest_version() {
    # The releases/latest page redirects to the tag. No API, no rate limit.
    url=$(curl -fsSLI -o /dev/null -w '%{url_effective}' \
        "https://github.com/$REPO/releases/latest")
    echo "${url##*/}"
}

main() {
    target=$(detect_target "$(uname -s)" "$(uname -m)")
    version=${HEADROOM_VERSION:-$(latest_version)}
    base="https://github.com/$REPO/releases/download/$version"
    asset="headroom-$target"

    if [ "${HEADROOM_DRY_RUN:-}" = "1" ]; then
        echo "$base/$asset"
        return 0
    fi

    dir=${HEADROOM_INSTALL_DIR:-$HOME/.local/bin}
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT

    echo "headroom: fetching $version for $target"
    curl -fsSL "$base/$asset" -o "$tmp/headroom"
    curl -fsSL "$base/SHA256SUMS" -o "$tmp/SHA256SUMS"

    # Verify before installing, never after: the point of a checksum is to
    # decide whether this file is allowed to become an executable on PATH.
    expected=$(grep " $asset\$" "$tmp/SHA256SUMS" | cut -d' ' -f1)
    if [ -z "$expected" ]; then
        echo "headroom: $asset is not listed in SHA256SUMS; refusing to install." >&2
        exit 1
    fi
    if command -v sha256sum >/dev/null 2>&1; then
        actual=$(sha256sum "$tmp/headroom" | cut -d' ' -f1)
    else
        actual=$(shasum -a 256 "$tmp/headroom" | cut -d' ' -f1)
    fi
    if [ "$expected" != "$actual" ]; then
        echo "headroom: checksum mismatch; refusing to install." >&2
        echo "  expected $expected" >&2
        echo "  got      $actual" >&2
        exit 1
    fi

    mkdir -p "$dir"
    chmod +x "$tmp/headroom"
    mv "$tmp/headroom" "$dir/headroom"
    echo "headroom: installed to $dir/headroom"

    case ":$PATH:" in
        *":$dir:"*) ;;
        *) echo "headroom: $dir is not on your PATH; add it to use 'headroom'." >&2 ;;
    esac
}

# Sourced by the tests to reach detect_target without installing anything.
[ "${HEADROOM_INSTALL_LIB:-}" = "1" ] || main "$@"
