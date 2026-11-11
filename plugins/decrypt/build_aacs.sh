# 蓝光解密组件：libaacs 0.12.0 + 补丁（认证命令经 App 发给光驱），libgcrypt、libgpg-error 静态链接进同一个 libaacs.so。
# 由 build.sh 引入：用它的 TARGET / HERE / OUT / SRC / WORK / JOBS / fetch / STRIP，Android 目标还有 TC / API。
# 上游源码按 SHA-256 固定（libgcrypt、libgpg-error 的 SHA-256 是核对过 GnuPG 发布签名之后记下的）。

GPGERR_V=1.61
GCRYPT_V=1.12.4
AACS_V=0.12.0
fetch "https://www.gnupg.org/ftp/gcrypt/libgpg-error/libgpg-error-$GPGERR_V.tar.bz2" \
  7a85413f2bc354f4f8aa832b718af122e48965e9e0eb9012ee659c13c6385c93
fetch "https://www.gnupg.org/ftp/gcrypt/libgcrypt/libgcrypt-$GCRYPT_V.tar.bz2" \
  d77f68f48879510e79a2f65977ccc68981781ea0923e5bdffac2a193ea3d660e
fetch "https://download.videolan.org/pub/videolan/libaacs/$AACS_V/libaacs-$AACS_V.tar.xz" \
  967e5ca408ac78f0d0abe5c05753b04cafcc4e868efd1d5e2cb5dc60e1c4f4e0

AACS_PREFIX="$WORK/aacs-prefix"
rm -rf "$AACS_PREFIX"
mkdir -p "$AACS_PREFIX"

case "$TARGET" in
  android-arm64)
    AACS_CONF_HOST=(--host=aarch64-linux-android)
    AACS_ENV=(CC="$TC/bin/aarch64-linux-android$API-clang" AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib"
              NM="$TC/bin/llvm-nm" STRIP="$TC/bin/llvm-strip" CFLAGS="-O2 -fPIC")
    AACS_LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--build-id=sha1"
    ;;
  host)
    AACS_CONF_HOST=()
    AACS_ENV=(CC="${CC:-cc}" CFLAGS="-O2 -fPIC")
    AACS_LDFLAGS="-Wl,--exclude-libs,ALL -Wl,--gc-sections"
    ;;
esac

# 只导出 libaacs 的接口（aacs_*，含补丁加的 aacs_set_host）；静态库和解析器的全局变量都不导出
printf '{\n  global: aacs_*; aacs2_*;\n  local: *;\n};\n' > "$WORK/libaacs.map"
AACS_LDFLAGS="$AACS_LDFLAGS -Wl,--version-script=$WORK/libaacs.map"

# 只在自己的前缀里找 pkg-config 文件（不用系统里的 libgcrypt / libudfread）
export PKG_CONFIG_LIBDIR="$AACS_PREFIX/lib/pkgconfig"
export PKG_CONFIG_PATH=""

aacs_configure() { # 目录 日志名 参数…
  local dir=$1 name=$2
  shift 2
  (cd "$dir" && env "${AACS_ENV[@]}" ./configure "${AACS_CONF_HOST[@]}" --prefix="$AACS_PREFIX" "$@" \
      > "$WORK/$name.configure.log" 2>&1) || { tail -40 "$WORK/$name.configure.log"; exit 1; }
  make -C "$dir" -j"$JOBS" > "$WORK/$name.make.log" 2>&1 || { tail -60 "$WORK/$name.make.log"; exit 1; }
}

echo "==== 编译 libgpg-error $GPGERR_V（$TARGET）"
rm -rf "$WORK/libgpg-error-$GPGERR_V"
tar -xjf "$SRC/libgpg-error-$GPGERR_V.tar.bz2" -C "$WORK"
if [ "$TARGET" = android-arm64 ]; then
  # 交叉编译要现成的锁对象头文件。64 位 Android 的那份在源码包里叫 …-androideabi.h（40 字节，和 bionic 在
  # aarch64 上的 pthread_mutex_t 一样大），而 configure 得出的三元组是 aarch64-unknown-linux-android：复制成这个名字
  cp "$WORK/libgpg-error-$GPGERR_V/src/syscfg/lock-obj-pub.aarch64-unknown-linux-androideabi.h" \
     "$WORK/libgpg-error-$GPGERR_V/src/syscfg/lock-obj-pub.aarch64-unknown-linux-android.h"
fi
aacs_configure "$WORK/libgpg-error-$GPGERR_V" libgpg-error --enable-static --disable-shared --with-pic \
  --disable-nls --disable-doc --disable-tests --disable-languages --disable-install-gpg-error-config
make -C "$WORK/libgpg-error-$GPGERR_V" install > "$WORK/libgpg-error.install.log" 2>&1 || { tail -20 "$WORK/libgpg-error.install.log"; exit 1; }

echo "==== 编译 libgcrypt $GCRYPT_V（$TARGET）"
rm -rf "$WORK/libgcrypt-$GCRYPT_V"
tar -xjf "$SRC/libgcrypt-$GCRYPT_V.tar.bz2" -C "$WORK"
# libaacs 只用到 AES（ECB / CBC / CMAC）、SHA-1 和 ECDSA（椭圆曲线），其余算法不编（BLAKE2 是 libgcrypt 自己的 KDF 模块要的）
aacs_configure "$WORK/libgcrypt-$GCRYPT_V" libgcrypt --enable-static --disable-shared --with-pic --disable-doc \
  --with-libgpg-error-prefix="$AACS_PREFIX" --enable-ciphers=aes --enable-pubkey-ciphers=ecc \
  --enable-digests=sha1,sha256,sha512,sha3,blake2 --enable-kdfs=pkdf2 --disable-jent-support
make -C "$WORK/libgcrypt-$GCRYPT_V" install > "$WORK/libgcrypt.install.log" 2>&1 || { tail -20 "$WORK/libgcrypt.install.log"; exit 1; }

echo "==== 编译 libaacs $AACS_V + DiskAndroid 补丁（$TARGET）"
rm -rf "$WORK/libaacs-$AACS_V"
tar -xJf "$SRC/libaacs-$AACS_V.tar.xz" -C "$WORK"
patch -d "$WORK/libaacs-$AACS_V" -p1 < "$HERE/patches/libaacs-$AACS_V-host-scsi.patch" > /dev/null
# 词法 / 语法分析器源码包里已经生成好了：时间戳摆正，免得 make 去找 flex / bison
touch "$WORK/libaacs-$AACS_V"/src/file/keydbcfg-parser.[ch] "$WORK/libaacs-$AACS_V"/src/file/keydbcfg-lexer.c
aacs_configure "$WORK/libaacs-$AACS_V" libaacs --enable-shared --disable-static --with-pic \
  --with-libgcrypt-prefix="$AACS_PREFIX" --with-gpg-error-prefix="$AACS_PREFIX" \
  LDFLAGS="$AACS_LDFLAGS" LIBS="-L$AACS_PREFIX/lib -lgcrypt -lgpg-error"
so=$(ls "$WORK"/libaacs-$AACS_V/.libs/libaacs.so.*.*.* 2> /dev/null | head -1 || true)
[ -n "$so" ] || so="$WORK/libaacs-$AACS_V/.libs/libaacs.so"
cp "$so" "$OUT/libaacs.so"
"$STRIP" --strip-unneeded "$OUT/libaacs.so"
echo "完成：$OUT/libaacs.so"
