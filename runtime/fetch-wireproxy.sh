#!/usr/bin/env bash
# Fetches the static wireproxy binary (a userspace WireGuard client that exposes SOCKS5/HTTP proxies) for the WARP
# tunnel and places it into the jniLibs directory under its Android library name, so the APK extracts it into the
# native library directory where executing it is allowed.
# Usage: runtime/fetch-wireproxy.sh <out-dir>   (e.g. out/jniLibs/arm64-v8a)
set -euo pipefail

dest="${1:?usage: fetch-wireproxy.sh <out-dir>}"
# v1.0.7 is the last release before wireproxy started sandboxing itself with Landlock. From v1.0.8 on, main() calls
# landlock_create_ruleset (syscall 444) before anything else - even for --version - and Android's app seccomp filter
# answers an unknown syscall with SIGSYS instead of ENOSYS, so the Go runtime dies with a register dump right at start
# ("The tunnel exited while starting", pc just after an svc, x8 = 0x1bc). Same family as golang/go#70508. Neither a flag
# nor the config turns the sandbox off, so newer releases cannot be used here until that changes.
version="${WIREPROXY_VERSION:-1.0.7}"
repo="${WIREPROXY_REPO:-windtf/wireproxy}"

mkdir -p "$dest"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

url="https://github.com/${repo}/releases/download/v${version}/wireproxy_linux_arm64.tar.gz"

# Pinned checksum of the v1.0.7 arm64 asset (verified against the official release): the binary carries the tunnel's
# WireGuard keys and ships inside the APK, so a silently retagged release must not slip in.
expected_sha="4acadcd4e74a40bb798d207b3d25b4b5f43cfddc39f9beb78fe5badf428b47a6"

echo "fetch-wireproxy: $url"
curl -fsSL --retry 3 --max-time 300 -o "$work/wireproxy.tar.gz" "$url"

echo "$expected_sha  $work/wireproxy.tar.gz" | sha256sum -c - >/dev/null || { echo "wireproxy checksum mismatch" >&2; exit 1; }

tar -xzf "$work/wireproxy.tar.gz" -C "$work" wireproxy

# The machine check the fetch-proot.sh contract uses: has to be an aarch64 static binary.
file "$work/wireproxy" | grep -qi "statically linked" || { echo "wireproxy is not statically linked" >&2; exit 1; }

# A build with the Landlock sandbox crashes on Android at start (see above); refuse to ship one, whatever the pin says.
if grep -qa "landlock" "$work/wireproxy"; then
  echo "wireproxy ${version} contains the Landlock sandbox and crashes on Android; use v1.0.7 or build without it" >&2
  exit 1
fi

install -m 0755 "$work/wireproxy" "$dest/libwireproxy.so"
echo "fetch-wireproxy: installed $dest/libwireproxy.so ($(wc -c < "$dest/libwireproxy.so") bytes)"
