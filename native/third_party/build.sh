#!/usr/bin/env bash
# 编译播放器要用的第三方库（静态库），输出到 native/third_party/out/<目标>/{include,lib}。
#   目标 android-arm64：用 NDK 交叉编译，给 App 用；
#   目标 host：本机 x86_64，给原生播放核心的单元测试用。
# 解密库（libdvdcss、libaacs）不在这里：按需求做成单独的可选组件，主 App 不内置。
# 用法：bash build.sh android-arm64|host    （已编过的库会跳过；删掉 out/<目标> 重新编）
set -euo pipefail
TARGET=${1:?目标：android-arm64 或 host}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/out/$TARGET"
SRC="$HERE/out/src"
WORK="$HERE/out/build-$TARGET"
mkdir -p "$OUT" "$SRC" "$WORK"
JOBS=$(nproc)

# ---- 源码（版本固定，核对 SHA-256）----
fetch() { # 名字 网址 sha256
  local f="$SRC/$(basename "$2")"
  if [ ! -f "$f" ]; then curl -fsSL --retry 4 --retry-delay 2 -o "$f.part" "$2" && mv "$f.part" "$f"; fi
  echo "$3  $f" | sha256sum -c --quiet - || { echo "校验失败：$f" >&2; rm -f "$f"; exit 1; }
}
fetch dvdread  https://download.videolan.org/pub/videolan/libdvdread/7.1.1/libdvdread-7.1.1.tar.xz a0d47876548bec806774bbf8dbf20bb19ba139464383156b32eb8e59915b90a9
fetch dvdnav   https://download.videolan.org/pub/videolan/libdvdnav/7.0.0/libdvdnav-7.0.0.tar.xz a2a18f5ad36d133c74bf9106b6445806fa253b09141a46392550394b647b221e
fetch udfread  https://download.videolan.org/pub/videolan/libudfread/libudfread-1.2.0.tar.xz bb477cbd4cfbfc7787d9d05b71ee5e70430f5cfebf1297497f7e83547958050f
fetch bluray   https://download.videolan.org/pub/videolan/libbluray/1.5.0/libbluray-1.5.0.tar.xz f676408e91a5d321abf8b8d4dfdae36205c297dab5c54c3ec519639025f474a2
fetch ffmpeg   https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz 8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e

unpack() { # 压缩包名 目录名
  if [ ! -d "$WORK/$2" ]; then
    tar -xJf "$SRC/$1" -C "$WORK"
    for p in "$HERE"/patches/"${2%%-[0-9]*}"-*.patch; do
      if [ -f "$p" ]; then patch -d "$WORK/$2" -p1 < "$p"; fi
    done
  fi
}

MESON=${MESON:-meson}
# 没有 meson / ninja 就装进 out/venv（只给这个脚本用）
if ! command -v "$MESON" > /dev/null 2>&1 || ! command -v ninja > /dev/null 2>&1; then
  VENV="$HERE/out/venv"
  [ -x "$VENV/bin/meson" ] && [ -x "$VENV/bin/ninja" ] || { python3 -m venv "$VENV" && "$VENV/bin/pip" install -q meson ninja; }
  export PATH="$VENV/bin:$PATH"
  MESON="$VENV/bin/meson"
fi
export PKG_CONFIG_LIBDIR="$OUT/lib/pkgconfig"
export PKG_CONFIG_PATH="$OUT/lib/pkgconfig"
case "$TARGET" in
  android-arm64)
    NDK=${ANDROID_NDK_HOME:-$(ls -d /root/Android/Sdk/ndk/*/ | sort -V | tail -1)}
    TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
    API=29
    CC="$TC/bin/aarch64-linux-android$API-clang"
    CXX="$TC/bin/aarch64-linux-android$API-clang++"
    CROSS="$WORK/cross.txt"
    cat > "$CROSS" <<CROSSEOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$TC/bin/llvm-ar'
strip = '$TC/bin/llvm-strip'
pkg-config = 'pkg-config'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'
[built-in options]
c_args = ['-fPIC', '-O2', '-fvisibility=hidden']
c_link_args = ['-Wl,-z,max-page-size=16384']
CROSSEOF
    MESON_CROSS=(--cross-file "$CROSS")
    ;;
  host)
    MESON_CROSS=()
    ;;
  *) echo "未知目标 $TARGET" >&2; exit 1 ;;
esac

meson_lib() { # 目录 安装后检查的文件 选项...
  local dir=$1 check=$2; shift 2
  [ -f "$OUT/$check" ] && { echo "已编好：$dir"; return; }
  echo "==== 编译 $dir（$TARGET）"
  rm -rf "$WORK/$dir/_build"
  (cd "$WORK/$dir" && "$MESON" setup _build "${MESON_CROSS[@]}" --prefix "$OUT" --libdir lib \
      --default-library=static --buildtype=release -Db_staticpic=true "$@" > "$WORK/$dir.meson.log" 2>&1 \
    || { tail -40 "$WORK/$dir.meson.log"; exit 1; })
  ninja -C "$WORK/$dir/_build" -j"$JOBS" install > "$WORK/$dir.ninja.log" 2>&1 || { tail -40 "$WORK/$dir.ninja.log"; exit 1; }
}

unpack libdvdread-7.1.1.tar.xz libdvdread-7.1.1
meson_lib libdvdread-7.1.1 lib/libdvdread.a -Dlibdvdcss=disabled -Denable_docs=false
unpack libdvdnav-7.0.0.tar.xz libdvdnav-7.0.0
meson_lib libdvdnav-7.0.0 lib/libdvdnav.a -Denable_docs=false
unpack libudfread-1.2.0.tar.xz libudfread-1.2.0
meson_lib libudfread-1.2.0 lib/libudfread.a
unpack libbluray-1.5.0.tar.xz libbluray-1.5.0
meson_lib libbluray-1.5.0 lib/libbluray.a -Dbdj_jar=disabled -Dlibxml2=disabled -Dfreetype=disabled -Dfontconfig=disabled \
  -Denable_tools=false -Denable_examples=false -Denable_docs=false

# ---- FFmpeg：只编播放 DVD / BD 要用的解码器（LGPL 部分 + yadif / bwdif 去隔行）----
if [ ! -f "$OUT/lib/libavcodec.a" ]; then
  unpack ffmpeg-9.0.2.tar.xz ffmpeg-9.0.2
  echo "==== 编译 FFmpeg（$TARGET）"
  FF=(--prefix="$OUT" --enable-static --disable-shared --enable-pic
      --disable-programs --disable-doc --disable-network --disable-autodetect --disable-everything
      --disable-avdevice --disable-avformat --enable-avcodec --enable-avutil --enable-swresample --enable-swscale --enable-avfilter
      --enable-decoder=mpeg1video,mpeg2video,h264,hevc,vc1,ac3,eac3,dca,truehd,mlp,mp2,mp3,pcm_dvd,pcm_bluray,dvd_subtitle,pgssub
      --enable-parser=mpegvideo,h264,hevc,vc1,ac3,dca,mlp,mpegaudio,dvd_subtitle
      --enable-filter=yadif,bwdif,buffer,buffersink,format,scale,null
      --disable-debug --enable-optimizations)
  if [ "$TARGET" = android-arm64 ]; then
    FF+=(--target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile --enable-neon
         --cc="$CC" --cxx="$CXX" --ar="$TC/bin/llvm-ar" --nm="$TC/bin/llvm-nm" --ranlib="$TC/bin/llvm-ranlib"
         --strip="$TC/bin/llvm-strip" --extra-cflags="-fPIC -O2" --extra-ldflags="-Wl,-z,max-page-size=16384")
  fi
  (cd "$WORK/ffmpeg-9.0.2" && ./configure "${FF[@]}" > "$WORK/ffmpeg.configure.log" 2>&1 || { tail -30 "$WORK/ffmpeg.configure.log"; tail -30 ffbuild/config.log; exit 1; })
  make -C "$WORK/ffmpeg-9.0.2" -j"$JOBS" > "$WORK/ffmpeg.make.log" 2>&1 || { tail -40 "$WORK/ffmpeg.make.log"; exit 1; }
  make -C "$WORK/ffmpeg-9.0.2" install > "$WORK/ffmpeg.install.log" 2>&1
fi
echo "完成：$OUT"
ls "$OUT/lib"
