#!/usr/bin/env bash
# 编译电脑版组件，用模拟光驱检查认证命令。
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
OUT="$HERE/../out/host"
PREFIX="$HERE/../out/build-host/aacs-prefix"
[ -f "$OUT/libdvdcss.so" ] || bash "$HERE/../build.sh" host css > /dev/null
[ -f "$OUT/libaacs.so" ] && [ -f "$PREFIX/lib/libgcrypt.a" ] || bash "$HERE/../build.sh" host aacs > /dev/null
mkdir -p "$HERE/../out/test"
cc -std=gnu11 -Wall -Wextra -Wno-unused-parameter -I"$ROOT/native/diskplayer/src" "$HERE/css_host_test.c" -ldl -o "$HERE/../out/test/css_host_test"
echo "==== libdvdcss（CSS）"
"$HERE/../out/test/css_host_test" "$OUT/libdvdcss.so"
# 测试程序自己要用 AES / SHA-1 造测试盘：链接编组件时顺带编出来的 libgcrypt 静态库
cc -std=gnu11 -Wall -Wextra -Wno-unused-parameter -I"$ROOT/native/diskplayer/src" -I"$PREFIX/include" "$HERE/aacs_host_test.c" \
  "$PREFIX/lib/libgcrypt.a" "$PREFIX/lib/libgpg-error.a" -ldl -lpthread -o "$HERE/../out/test/aacs_host_test"
echo "==== libaacs（AACS）"
"$HERE/../out/test/aacs_host_test" "$OUT/libaacs.so"
