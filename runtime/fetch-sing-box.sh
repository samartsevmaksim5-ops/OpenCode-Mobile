#!/usr/bin/env bash
# Fetch a pinned official sing-box Linux ARM64 binary for VLESS/Hysteria2 local proxy support.
# The binary is stored under Android's nativeLibraryDir so ProcessBuilder can execute it directly.
#
# IMPORTANT: v1.12.9 is the newest release whose linux-arm64 binary is STATICALLY LINKED. Starting with v1.13 the
# official builds link against glibc and ship libcronet.so, which Android cannot load without a root filesystem -
# a dynamically linked binary would fail here AND would not run on the device at all.
set -euo pipefail

dest="${1:?usage: runtime/fetch-sing-box.sh <out-jniLibs-dir>}"
version="${SING_BOX_VERSION:-1.12.9}"
mkdir -p "$dest"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
archive="sing-box-${version}-linux-arm64.tar.gz"
url="https://github.com/SagerNet/sing-box/releases/download/v${version}/${archive}"
# Release asset digest of the official SagerNet/sing-box GitHub release for v1.12.9 (statically linked build).
if [ "$version" = "1.12.9" ]; then
  expected_sha="0d571bf961c651cc5a4eaffe9715d7759edb484b90473f3fa25aaab87fa11961"
else
  echo "No checksum pinned for sing-box v${version}; refusing an unverified binary." >&2
  exit 1
fi

echo "fetch-sing-box: $url"
curl -fsSL --retry 3 --max-time 300 -o "$work/$archive" "$url"
echo "$expected_sha  $work/$archive" | sha256sum -c - >/dev/null || { echo "sing-box checksum mismatch" >&2; exit 1; }
tar -xzf "$work/$archive" -C "$work"
binary="$(find "$work" -type f -name sing-box -perm /111 -print -quit)"
[ -n "$binary" ] || { echo "sing-box binary missing from archive" >&2; exit 1; }
file "$binary" | grep -qi 'statically linked' || { echo "sing-box binary must be statically linked to run on Android without root; refusing a glibc build" >&2; file "$binary" >&2; exit 1; }
file "$binary" | grep -Eqi 'ARM aarch64|ARM64|aarch64' || { echo "unexpected sing-box architecture" >&2; file "$binary" >&2; exit 1; }
install -m 0755 "$binary" "$dest/libsingbox.so"
echo "fetch-sing-box: installed $dest/libsingbox.so ($(wc -c < "$dest/libsingbox.so") bytes)"
