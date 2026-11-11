#!/usr/bin/env bash
# Generates the self-signed TLS certificate bundled into the APK.
#
# The cert is valid for the mDNS hostname (andwebcam.local), so clients that
# resolve the phone by name can verify it. It is intentionally long-lived
# because the phone is often headless / physically inaccessible.
#
# Outputs (into app/src/main/assets):
#   server.crt      PEM certificate
#   server.key.pk8  private key, DER-encoded PKCS#8, unencrypted
#
# Regenerate with: scripts/make-cert.sh
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
out="$here/app/src/main/assets"
name="${1:-andwebcam.local}"

mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$tmp/key.pem" -out "$tmp/cert.pem" \
    -days 3650 -sha256 \
    -subj "/CN=$name/O=andWebCam" \
    -addext "subjectAltName=DNS:$name,DNS:localhost,IP:127.0.0.1" \
    -addext "basicConstraints=critical,CA:FALSE" \
    -addext "keyUsage=critical,digitalSignature,keyEncipherment" \
    -addext "extendedKeyUsage=serverAuth" \
    2>/dev/null

openssl pkcs8 -topk8 -nocrypt -in "$tmp/key.pem" -outform DER -out "$out/server.key.pk8"
cp "$tmp/cert.pem" "$out/server.crt"

echo "wrote $out/server.crt and $out/server.key.pk8"
openssl x509 -in "$out/server.crt" -noout -subject -dates -fingerprint -sha256
