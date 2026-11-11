#!/usr/bin/env bash
# 编译电脑版组件，用模拟光驱检查认证命令。
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
OUT="$HERE/../out/host"
[ -f "$OUT/libdvdcss.so" ] || bash "$HERE/../build.sh" host css > /dev/null
mkdir -p "$HERE/../out/test"
cc -std=gnu11 -Wall -Wextra -Wno-unused-parameter -I"$ROOT/native/diskplayer/src" "$HERE/css_host_test.c" -ldl -o "$HERE/../out/test/css_host_test"
"$HERE/../out/test/css_host_test" "$OUT/libdvdcss.so"
