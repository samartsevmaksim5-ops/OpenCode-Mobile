#!/usr/bin/env bash
# Fetches the static wireproxy binary (a userspace WireGuard client that exposes SOCKS5/HTTP proxies) for the WARP
# tunnel and places it into the jniLibs directory under its Android library name, so the APK extracts it into the
# native library directory where executing it is allowed.
# Usage: tools/fetch-wireproxy.sh <out-dir>   (e.g. out/jniLibs/arm64-v8a)
set -euo pipefail

dest="${1:?usage: fetch-wireproxy.sh <out-dir>}"
version="${WIREPROXY_VERSION:-1.1.3}"
repo="${WIREPROXY_REPO:-windtf/wireproxy}"

mkdir -p "$dest"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

url="https://github.com/${repo}/releases/download/v${version}/wireproxy_linux_arm64.tar.gz"

# Pinned checksum of the v1.1.3 arm64 asset (verified against the official release): the binary carries the tunnel's
# WireGuard keys and ships inside the APK, so a silently retagged release must not slip in.
expected_sha="370e00bd2167960d1ecd1c3c1439715bbaa94a0a110a2040468670c9af6021b6"

echo "fetch-wireproxy: $url"
curl -fsSL --retry 3 --max-time 300 -o "$work/wireproxy.tar.gz" "$url"

echo "$expected_sha  $work/wireproxy.tar.gz" | sha256sum -c - >/dev/null || { echo "wireproxy checksum mismatch" >&2; exit 1; }

tar -xzf "$work/wireproxy.tar.gz" -C "$work" wireproxy

# The machine check the fetch-proot.sh contract uses: has to be an aarch64 static binary.
file "$work/wireproxy" | grep -qi "statically linked" || { echo "wireproxy is not statically linked" >&2; exit 1; }

install -m 0755 "$work/wireproxy" "$dest/libwireproxy.so"
echo "fetch-wireproxy: installed $dest/libwireproxy.so ($(wc -c < "$dest/libwireproxy.so") bytes)"
