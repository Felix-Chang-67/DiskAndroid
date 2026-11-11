#!/usr/bin/env bash
# 编译解密组件（不进 APK、不进仓库：输出在 plugins/decrypt/out/，由用户在 App 里"导入组件"）。
#   libdvdcss 1.6.0 + 补丁 patches/libdvdcss-1.6.0-host-scsi.patch：流模式下经 App 的回调向 USB 光驱发 CSS 认证命令
#   libaacs（见 build_aacs）：同理，AACS 认证；依赖 libgpg-error、libgcrypt，静态链接进同一个 .so
# 用法：bash build.sh android-arm64|host [css|aacs|all]
set -euo pipefail
TARGET=${1:?目标：android-arm64 或 host}
WHAT=${2:-all}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT="$HERE/out/$TARGET"
SRC="$HERE/out/src"
WORK="$HERE/out/build-$TARGET"
mkdir -p "$OUT" "$SRC" "$WORK"
JOBS=$(nproc)

fetch() { # 网址 sha256
  local f="$SRC/$(basename "$1")"
  if [ ! -f "$f" ]; then curl -fsSL --retry 4 --retry-delay 2 -o "$f.part" "$1" && mv "$f.part" "$f"; fi
  echo "$2  $f" | sha256sum -c --quiet - || { echo "校验失败：$f" >&2; rm -f "$f"; exit 1; }
}

# meson / ninja：没有就装进 third_party 的 venv（和播放器第三方库共用）
MESON=${MESON:-meson}
if ! command -v "$MESON" > /dev/null 2>&1 || ! command -v ninja > /dev/null 2>&1; then
  VENV="$ROOT/native/third_party/out/venv"
  [ -x "$VENV/bin/meson" ] && [ -x "$VENV/bin/ninja" ] || { python3 -m venv "$VENV" && "$VENV/bin/pip" install -q meson ninja; }
  export PATH="$VENV/bin:$PATH"
  MESON="$VENV/bin/meson"
fi

case "$TARGET" in
  android-arm64)
    NDK=${ANDROID_NDK_HOME:-$(ls -d /root/Android/Sdk/ndk/*/ | sort -V | tail -1)}
    TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
    API=29
    CC="$TC/bin/aarch64-linux-android$API-clang"
    CROSS="$WORK/cross.txt"
    cat > "$CROSS" <<CROSSEOF
[binaries]
c = '$CC'
ar = '$TC/bin/llvm-ar'
strip = '$TC/bin/llvm-strip'
pkg-config = 'pkg-config'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'
[built-in options]
c_args = ['-fPIC', '-O2']
c_link_args = ['-Wl,-z,max-page-size=16384']
CROSSEOF
    MESON_CROSS=(--cross-file "$CROSS")
    STRIP="$TC/bin/llvm-strip"
    ;;
  host)
    MESON_CROSS=()
    STRIP=strip
    ;;
  *) echo "未知目标 $TARGET" >&2; exit 1 ;;
esac

build_css() {
  fetch https://download.videolan.org/pub/videolan/libdvdcss/1.6.0/libdvdcss-1.6.0.tar.xz \
    7ea556c846b7bfc32d47b41cae56d1863a6b6d5f706bb162778d6f298490977c
  rm -rf "$WORK/libdvdcss-1.6.0"
  tar -xJf "$SRC/libdvdcss-1.6.0.tar.xz" -C "$WORK"
  patch -d "$WORK/libdvdcss-1.6.0" -p1 < "$HERE/patches/libdvdcss-1.6.0-host-scsi.patch" > /dev/null
  echo "==== 编译 libdvdcss（$TARGET）"
  (cd "$WORK/libdvdcss-1.6.0" && "$MESON" setup _build "${MESON_CROSS[@]}" --buildtype=release --default-library=shared \
      -Denable_docs=false > "$WORK/libdvdcss.meson.log" 2>&1 || { tail -40 "$WORK/libdvdcss.meson.log"; exit 1; })
  ninja -C "$WORK/libdvdcss-1.6.0/_build" -j"$JOBS" > "$WORK/libdvdcss.ninja.log" 2>&1 || { tail -40 "$WORK/libdvdcss.ninja.log"; exit 1; }
  local so
  # 电脑上是 libdvdcss.so.2.x.y；Android 上 meson 不加版本号（SONAME 就是 libdvdcss.so，App 按绝对路径加载，不靠 SONAME）
  so=$(ls "$WORK"/libdvdcss-1.6.0/_build/src/libdvdcss.so.2.*.* 2> /dev/null | head -1 || true)
  [ -n "$so" ] || so="$WORK/libdvdcss-1.6.0/_build/src/libdvdcss.so"
  cp "$so" "$OUT/libdvdcss.so"
  "$STRIP" --strip-unneeded "$OUT/libdvdcss.so"
  echo "完成：$OUT/libdvdcss.so"
}

case "$WHAT" in
  css) build_css ;;
  aacs) source "$HERE/build_aacs.sh" ;;
  all)
    build_css
    if [ -f "$HERE/build_aacs.sh" ]; then source "$HERE/build_aacs.sh"; fi
    ;;
  *) echo "未知组件 $WHAT" >&2; exit 1 ;;
esac
ls -la "$OUT"
