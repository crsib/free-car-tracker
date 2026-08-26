#!/usr/bin/env bash
# Cross-compile the viewer for the Linux container. The SPA is embedded by go:embed, so
# there is no frontend build step - the whole app is one static binary.
# Usage:  scripts/build.sh [version] [arch]
set -euo pipefail

version="${1:-dev}"
arch="${2:-amd64}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

commit="$(git -C "$root" rev-parse --short HEAD 2>/dev/null || echo none)"

mkdir -p "$root/dist"
CGO_ENABLED=0 GOOS=linux GOARCH="$arch" go build \
    -trimpath \
    -ldflags "-s -w -X main.buildVersion=$version -X main.buildCommit=$commit" \
    -o "$root/dist/fct-viewer" "$root"

printf 'built %s/dist/fct-viewer  linux/%s  %s / %s  (%s)\n' \
    "$root" "$arch" "$version" "$commit" \
    "$(du -h "$root/dist/fct-viewer" | cut -f1)"
