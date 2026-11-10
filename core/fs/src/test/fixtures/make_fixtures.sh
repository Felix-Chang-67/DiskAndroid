#!/usr/bin/env bash
# 生成 core:fs 单元测试用的盘片镜像（用真实的建盘工具，检验读取代码与它们的输出兼容）。
# 需要：genisoimage、mkudffs（udftools）、python3 + pycdlib。生成的镜像 gzip 后放在 ../resources/fixtures。
# 用法：bash make_fixtures.sh <输出目录>
set -euo pipefail
OUT=$(realpath "${1:?输出目录}")
PY=${PYTHON:-python3}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
export SOURCE_DATE_EPOCH=1762750800   # 2025-11-10 05:00:00 UTC：让镜像可重复生成

# ---- 样本文件树 ----
T="$WORK/tree"
mkdir -p "$T/日本語フォルダ/サブ/深い" "$T/many" "$T/VIDEO_TS" "$T/AUDIO_TS" "$T/空"
printf 'hello disc\n' > "$T/README.TXT"
printf 'さくら さくら\n弥生の空は\n' > "$T/日本語フォルダ/歌詞（さくら）.txt"
head -c 5000 /dev/zero | tr '\0' 'D' > "$T/日本語フォルダ/サブ/深い/ファイル.dat"
for i in $(seq -w 1 100); do printf 'file %s\n' "$i" > "$T/many/ファイル番号_$i.txt"; done
"$PY" - "$T/big.bin" <<'PY'
import sys
# 300 000 字节，每个字节 = (位置 * 7 + 位置 // 2048) mod 251：任何错位都会被测出来
n = 300000
sys.stdout = open(sys.argv[1], 'wb')
sys.stdout.write(bytes(((i * 7 + i // 2048) % 251) for i in range(n)))
PY
printf 'DVDVIDEO-VMG' > "$T/VIDEO_TS/VIDEO_TS.IFO"
printf 'DVDVIDEO-VTS' > "$T/VIDEO_TS/VTS_01_0.IFO"
head -c 10240 /dev/zero > "$T/VIDEO_TS/VTS_01_1.VOB"
find "$T" -exec touch -h -d @"$SOURCE_DATE_EPOCH" {} +

# A) ISO 9660 + Joliet + Rock Ridge（Linux 的 genisoimage）；PVD 卷标是 UTF-8 日文（违反 d-字符规定，但真实盘上会遇到）
genisoimage -quiet -input-charset utf-8 -J -joliet-long -R -V "ライブ_LIVE_AT_TEST" -o "$WORK/joliet_rr.iso" "$T"

# B) UDF 1.02 + ISO 9660 桥接（DVD-Video 的结构）
mkdir -p "$WORK/dvd/VIDEO_TS" "$WORK/dvd/AUDIO_TS"
cp "$T/VIDEO_TS/"* "$WORK/dvd/VIDEO_TS/"
printf 'ボーナス映像' > "$WORK/dvd/特典.txt"
find "$WORK/dvd" -exec touch -h -d @"$SOURCE_DATE_EPOCH" {} +
genisoimage -quiet -input-charset utf-8 -udf -J -V "DVD_VIDEO_TEST" -o "$WORK/udf102_bridge.iso" "$WORK/dvd"

# C) UDF 2.60 + ISO 9660 + Joliet（pycdlib），UDF 里是日文文件名
"$PY" "$HERE/make_udf260.py" "$WORK/udf260.iso"

# D) UDF 2.50 + 元数据分区 + VAT（BD-R 增量刻录的结构；mkudffs 只能建空文件系统，2.50 只支持 BD-R / DVD-R）
mkudffs --utf8 --media-type=bdr --udfrev=2.50 --blocksize=2048 --label="ブルーレイ_TEST" --new-file "$WORK/udf250_meta.img" 8192 >/dev/null
# E) UDF 2.01 + 备用（sparing）表（DVD-RW 包写入的结构）
mkudffs --utf8 --media-type=dvdrw --udfrev=2.01 --blocksize=2048 --label="RW_SPARING" --new-file "$WORK/udf201_sparing.img" 8192 >/dev/null
# F) UDF 2.01 + 虚拟分配表（VAT，CD-R 增量刻录的结构）
mkudffs --utf8 --media-type=cdr --udfrev=2.01 --blocksize=2048 --label="CDR_VAT" --new-file "$WORK/udf201_vat.img" 4096 >/dev/null

mkdir -p "$OUT"
for f in joliet_rr.iso udf102_bridge.iso udf260.iso udf250_meta.img udf201_sparing.img udf201_vat.img; do
  gzip -9n -c "$WORK/$f" > "$OUT/$f.gz"
  echo "$f: $(stat -c %s "$WORK/$f") 字节 → $(stat -c %s "$OUT/$f.gz")"
done
