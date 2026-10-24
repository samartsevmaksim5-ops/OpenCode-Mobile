#!/usr/bin/env bash
# Compiles the Android-free Kotlin sources with a test runner and runs it on the JVM.
# Usage: tools/test-jvm.sh [kotlinc] [kotlin-stdlib.jar]
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
kotlinc="${1:-${KOTLINC:-kotlinc}}"
stdlib="${2:-${KOTLIN_STDLIB:-$(dirname "$(readlink -f "$(command -v "$kotlinc")")")/../lib/kotlin-stdlib.jar}}"
src="$root/android/app/src/main/kotlin/dev/opencode/mobile"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

pure="MiniJson Sse Urls Fs TarGz ProotCommand Profiles ConsoleLogin Ota BridgeServer GuestPaths PortPicker WidgetContent Strings OpencodeStore Warp Limits"
files=()
for f in $pure; do files+=("$src/$f.kt"); done

"$kotlinc" -nowarn -jvm-target 17 -d "$work/classes" "${files[@]}" "$root/tools/test-jvm/Tests.kt" 2>&1 | grep -v '^warning:' || true
bash "$root/tools/test-jvm/make-fixtures.sh" "$work/fixtures" >/dev/null
LC_ALL=C.UTF-8 java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -cp "$work/classes:$stdlib" dev.opencode.mobile.TestsKt "$work/fixtures"
