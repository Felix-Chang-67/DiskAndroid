#!/usr/bin/env bash
# 在电脑上编译并运行原生播放核心的测试（需要先 bash native/third_party/build.sh host）。
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT="$HERE/.."
DEPS="$ROOT/../third_party/out/host"
OUT="$HERE/data"
mkdir -p "$OUT"
[ -f "$OUT/menu_dvd.iso" ] || bash "$HERE/make_menu_dvd.sh" "$OUT/menu_dvd.iso" > "$OUT/make.log" 2>&1
cc -std=gnu11 -O1 -g -fsanitize=address,undefined -Wall -Wextra -Wno-unused-parameter -I"$DEPS/include" \
  "$ROOT/src/dp_core.c" "$ROOT/src/dp_spu.c" "$ROOT/src/dp_av.c" "$ROOT/src/dp_dvd.c" "$HERE/dp_host_test.c" \
  -L"$DEPS/lib" -ldvdnav -ldvdread -lavfilter -lswscale -lswresample -lavcodec -lavutil -lpthread -lm -ldl \
  -o "$OUT/dp_host_test"
"$OUT/dp_host_test" "$OUT/menu_dvd.iso"
