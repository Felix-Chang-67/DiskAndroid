#!/usr/bin/env bash
# 生成 core:video 单元测试用的 DVD-Video / BDMV 镜像，并保存 lsdvd（libdvdread）和 bd_list_titles（libbluray）的输出作为参考答案。
# 需要：ffmpeg、dvdauthor、lsdvd、genisoimage、python3、libbluray-bin。
# 用法：bash make_fixtures.sh <输出目录>
set -euo pipefail
OUT=$(realpath "${1:?输出目录}")
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
export SOURCE_DATE_EPOCH=1762750800
cd "$WORK"

# ---- DVD：三个标题集 ----
# VTS 1：PAL 16:9，8 秒，章节 0 / 2 / 5 秒，音轨 1 = AC-3（日语），音轨 2 = MPEG-1（英语）
# VTS 2：PAL 4:3，3 秒，LPCM（日语）
# VTS 3：NTSC 16:9，同一段 4 秒的视频接两次（4 个单元），章节 0 / 1.5 秒，AC-3 立体声（英语）
q="-hide_banner -loglevel error"
ffmpeg $q -f lavfi -i testsrc=size=720x576:rate=25:duration=8 -f lavfi -i sine=frequency=440:sample_rate=48000:duration=8 \
  -f lavfi -i sine=frequency=880:sample_rate=48000:duration=8 -map 0:v -map 1:a -map 2:a -c:v mpeg2video -b:v 800k -g 12 -aspect 16:9 \
  -c:a:0 ac3 -b:a:0 192k -c:a:1 mp2 -b:a:1 128k -f dvd main_pal.mpg
ffmpeg $q -f lavfi -i testsrc=size=720x576:rate=25:duration=3 -f lavfi -i sine=frequency=330:sample_rate=48000:duration=3 \
  -c:v mpeg2video -b:v 600k -g 12 -aspect 4:3 -c:a pcm_s16be -f dvd bonus_pal.mpg
ffmpeg $q -f lavfi -i testsrc=size=720x480:rate=30000/1001:duration=4 -f lavfi -i sine=frequency=550:sample_rate=48000:duration=4 \
  -c:v mpeg2video -b:v 600k -g 15 -aspect 16:9 -c:a ac3 -ac 2 -f dvd ntsc.mpg
cat > dvd.xml <<'XML'
<dvdauthor dest="DVD" jumppad="0">
  <vmgm />
  <titleset>
    <titles>
      <video format="pal" aspect="16:9"/>
      <audio lang="ja"/>
      <audio lang="en"/>
      <pgc><vob file="main_pal.mpg" chapters="0,0:02,0:05"/></pgc>
    </titles>
  </titleset>
  <titleset>
    <titles>
      <video format="pal" aspect="4:3"/>
      <audio lang="ja"/>
      <pgc><vob file="bonus_pal.mpg"/></pgc>
    </titles>
  </titleset>
  <titleset>
    <titles>
      <video format="ntsc" aspect="16:9"/>
      <audio lang="en"/>
      <pgc><vob file="ntsc.mpg" chapters="0,0:01.500"/><vob file="ntsc.mpg"/></pgc>
    </titles>
  </titleset>
</dvdauthor>
XML
VIDEO_FORMAT=PAL dvdauthor -x dvd.xml > dvdauthor.log 2>&1
lsdvd -a -c -s -v -x DVD 2>/dev/null > "$OUT/dvd_lsdvd.txt"
# 视频内容对解析 IFO 没用：清零（大小不变），镜像压缩后只有几 KB
for v in DVD/VIDEO_TS/*.VOB; do head -c "$(stat -c %s "$v")" /dev/zero > "$v.z" && mv "$v.z" "$v"; done
find DVD -exec touch -h -d @"$SOURCE_DATE_EPOCH" {} +
genisoimage -quiet -dvd-video -udf -V "LIVE_DVD_TEST" -o dvd.iso DVD
gzip -9n -c dvd.iso > "$OUT/dvd_video.iso.gz"

# ---- BD：按规范拼出的 BDMV（见 make_bd_fixture.py）----
python3 -I "$HERE/make_bd_fixture.py" "$WORK/bd"
bd_list_titles -l "$WORK/bd" > "$OUT/bd_list_titles.txt" 2>&1
find bd -exec touch -h -d @"$SOURCE_DATE_EPOCH" {} +
genisoimage -quiet -udf -iso-level 3 -V "LIVE_BD_TEST" -o bd.iso bd
gzip -9n -c bd.iso > "$OUT/bdmv.iso.gz"
ls -la "$OUT"
