#!/usr/bin/env bash
# 在电脑上编译并运行原生播放核心的测试（需要先 bash native/third_party/build.sh host）。
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT="$HERE/.."
DEPS="$ROOT/../third_party/out/host"
OUT="$HERE/data"
mkdir -p "$OUT"
[ -f "$OUT/menu_dvd.iso" ] || bash "$HERE/make_menu_dvd.sh" "$OUT/menu_dvd.iso" > "$OUT/make.log" 2>&1
[ -f "$OUT/lpcm_dvd.iso" ] || bash "$HERE/make_menu_dvd.sh" "$OUT/lpcm_dvd.iso" lpcm > "$OUT/make_lpcm.log" 2>&1
cc -std=gnu11 -O1 -g -fsanitize=address,undefined -Wall -Wextra -Wno-unused-parameter -I"$DEPS/include" \
  "$ROOT/src/dp_core.c" "$ROOT/src/dp_spu.c" "$ROOT/src/dp_av.c" "$ROOT/src/dp_dvd.c" "$HERE/dp_host_test.c" \
  -L"$DEPS/lib" -ldvdnav -ldvdread -lavfilter -lswscale -lswresample -lavcodec -lavutil -lpthread -lm -ldl \
  -o "$OUT/dp_host_test"
"$OUT/dp_host_test" "$OUT/menu_dvd.iso" "$OUT/lpcm_dvd.iso"

# 视频帧时间轴（软下拉电影的均匀显示、告诉系统的帧率）
cc -std=gnu11 -O1 -g -fsanitize=address,undefined -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -I"$DEPS/include" \
  "$ROOT/src/dp_core.c" "$HERE/dp_av_timeline_test.c" \
  -L"$DEPS/lib" -lavfilter -lswscale -lswresample -lavcodec -lavutil -lpthread -lm -ldl \
  -o "$OUT/dp_av_timeline_test"
"$OUT/dp_av_timeline_test"

# 蓝光
[ -f "$OUT/test_bd.iso" ] || python3 "$HERE/make_bd.py" "$OUT/test_bd.iso" > "$OUT/make_bd.log" 2>&1
cc -std=gnu11 -O1 -g -fsanitize=address,undefined -Wall -Wextra -Wno-unused-parameter -I"$DEPS/include" \
  "$ROOT/src/dp_core.c" "$ROOT/src/dp_av.c" "$ROOT/src/dp_bd.c" "$HERE/dp_bd_host_test.c" \
  -L"$DEPS/lib" -lbluray -ludfread -lavfilter -lswscale -lswresample -lavcodec -lavutil -lpthread -lm -ldl \
  -o "$OUT/dp_bd_host_test"
"$OUT/dp_bd_host_test" "$OUT/test_bd.iso"

# 蓝光解密（端到端）：要先编电脑版的解密组件（bash plugins/decrypt/build.sh host aacs）；没有就跳过
AACS_LIB="$ROOT/../../plugins/decrypt/out/host/libaacs.so"
if [ -f "$AACS_LIB" ]; then
  [ -f "$OUT/aacs_bd.iso" ] || python3 "$HERE/make_bd.py" "$OUT/aacs_bd.iso" --aacs "$OUT/aacs_keydb" > "$OUT/make_aacs_bd.log" 2>&1
  [ -f "$OUT/aacs_uhd_bd.iso" ] || python3 "$HERE/make_bd.py" "$OUT/aacs_uhd_bd.iso" --aacs "$OUT/aacs_uhd_keydb" --uhd > "$OUT/make_aacs_uhd_bd.log" 2>&1
  cc -std=gnu11 -O1 -g -fsanitize=address,undefined -Wall -Wextra -Wno-unused-parameter -I"$DEPS/include" \
    "$ROOT/src/dp_core.c" "$ROOT/src/dp_av.c" "$ROOT/src/dp_bd.c" "$HERE/dp_bd_aacs_host_test.c" \
    -L"$DEPS/lib" -lbluray -ludfread -lavfilter -lswscale -lswresample -lavcodec -lavutil -lpthread -lm -ldl \
    -o "$OUT/dp_bd_aacs_host_test"
  "$OUT/dp_bd_aacs_host_test" "$OUT/aacs_bd.iso" "$OUT/aacs_uhd_bd.iso" "$OUT/aacs_keydb" "$AACS_LIB"
else
  echo "（跳过蓝光解密测试：没有 $AACS_LIB）"
fi
