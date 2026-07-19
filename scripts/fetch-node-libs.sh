#!/usr/bin/env bash
# Downloads the prebuilt on-device Node.js native libraries (~33MB compressed)
# from the GitHub release that hosts them, verifies the checksum, and unpacks
# them into app/src/main/jniLibs/. The .so files are not tracked in git — see
# docs/BUILDING.md.
#
# Usage: ./scripts/fetch-node-libs.sh [--force]
set -euo pipefail

REPO="8crsk/openclaw-android"
TAG="node-libs-v1"
ASSET="jniLibs-arm64-v8a.tar.gz"
URL="https://github.com/${REPO}/releases/download/${TAG}/${ASSET}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${ROOT}/app/src/main/jniLibs"
SUMS="${ROOT}/scripts/node-libs.sha256"
MARKER="${DEST}/arm64-v8a/libnode.so"

if [[ "${1:-}" != "--force" && -f "$MARKER" ]] && \
   [[ "$(stat -c%s "$MARKER" 2>/dev/null || stat -f%z "$MARKER")" -gt 1000000 ]]; then
    echo "Node libs already present ($MARKER) — nothing to do. Use --force to re-fetch."
    exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading ${ASSET} from ${TAG} release..."
curl -fL --retry 3 -o "${TMP}/${ASSET}" "$URL"

echo "Verifying checksum..."
(cd "$TMP" && sha256sum -c "$SUMS")

echo "Extracting into ${DEST}..."
mkdir -p "$DEST"
tar -xzf "${TMP}/${ASSET}" -C "$DEST"

echo "Done. $(ls "$DEST"/arm64-v8a/*.so | wc -l) libraries in ${DEST}/arm64-v8a/"
