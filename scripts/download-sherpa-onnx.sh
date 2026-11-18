#!/usr/bin/env bash
# Downloads the sherpa-onnx Android AAR (on-device speech recognition runtime) into app/libs/.
# Not published to Maven Central, so it is fetched from the official GitHub release and SHA-256 checked.
set -euo pipefail

VERSION="1.13.0"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar"
EXPECTED_SHA256="28e9b348736d84b610361daf161f5904440d6d516531e896bf9ee8b0ad02764e"
DEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/app/libs"
DEST="${DEST_DIR}/sherpa-onnx-${VERSION}.aar"

mkdir -p "${DEST_DIR}"
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }

if [[ -f "${DEST}" && "$(sha "${DEST}")" == "${EXPECTED_SHA256}" ]]; then echo "OK ${DEST}"; exit 0; fi
tmp="${DEST}.part"
curl --fail --location --proto '=https' --tlsv1.2 --progress-bar -o "${tmp}" "${URL}"
[[ "$(sha "${tmp}")" == "${EXPECTED_SHA256}" ]] || { rm -f "${tmp}"; echo "SHA-256 mismatch" >&2; exit 1; }
mv -f "${tmp}" "${DEST}"
echo "Saved ${DEST}"
