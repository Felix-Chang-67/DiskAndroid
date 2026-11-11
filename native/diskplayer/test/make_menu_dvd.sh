#!/usr/bin/env bash
# 生成一张带菜单的测试 DVD（原生播放器在电脑上的测试用）：
#   首播 → 根菜单（静止画面，一直停住，两个按钮：「主片」「特典」，下 / 上键互相切换）
#   标题 1：NTSC 16:9，6 秒，章节 0 / 2 / 4 秒，音轨 1 = AC-3（日语），音轨 2 = LPCM（英语）；播完回菜单
#   标题 2：NTSC 16:9，3 秒，AC-3（日语）；播完回菜单
# 需要：ffmpeg、dvdauthor（含 spumux）、genisoimage。用法：bash make_menu_dvd.sh <输出 .iso> [lpcm]
#   lpcm：两个标题都只有一条不标语言的 LPCM 音轨（光盘程序也不选音轨，音轨寄存器一直是 15）——
#         N3 真机上那张商业 DVD 就是这样，以前按 AC-3 去找，正片没有声音
set -euo pipefail
OUT=$(realpath -m "${1:?输出 .iso}")
VARIANT=${2:-}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
export SOURCE_DATE_EPOCH=1762750800
q="-hide_banner -loglevel error"
NTSC="-c:v mpeg2video -b:v 600k -g 15 -aspect 16:9 -r 30000/1001"

# 菜单背景：1 秒蓝底 + 静音 AC-3
ffmpeg $q -f lavfi -i color=c=0x203060:s=720x480:r=30000/1001:d=1 -f lavfi -i anullsrc=r=48000:cl=stereo -t 1 $NTSC -c:a ac3 -b:a 128k -f dvd menu.mpg
# 按钮图（只有透明 + 一种颜色）：普通白色，高亮黄色，按下红色
box() { ffmpeg $q -f lavfi -i "color=c=black@0.0:s=720x480,format=rgba,drawbox=x=100:y=100:w=200:h=60:color=$1@1:t=fill:replace=1,drawbox=x=100:y=200:w=200:h=60:color=$1@1:t=fill:replace=1" -frames:v 1 "$2"; }
box white normal.png
box yellow highlight.png
box red select.png
cat > menu.xml <<'XML'
<subpictures format="NTSC">
  <stream>
    <spu start="00:00:00.00" image="normal.png" highlight="highlight.png" select="select.png" force="yes">
      <button name="b1" x0="100" y0="100" x1="300" y1="160" down="b2"/>
      <button name="b2" x0="100" y0="200" x1="300" y1="260" up="b1"/>
    </spu>
  </stream>
</subpictures>
XML
spumux -s 0 menu.xml < menu.mpg > menu_spu.mpg 2> spumux.log

# 标题
if [ "$VARIANT" = lpcm ]; then
  ffmpeg $q -f lavfi -i testsrc=size=720x480:rate=30000/1001:duration=6 -f lavfi -i sine=frequency=440:sample_rate=48000:duration=6 \
    $NTSC -c:a pcm_s16be -ac 2 -f dvd main.mpg
  ffmpeg $q -f lavfi -i testsrc2=size=720x480:rate=30000/1001:duration=3 -f lavfi -i sine=frequency=330:sample_rate=48000:duration=3 \
    $NTSC -c:a pcm_s16be -ac 2 -f dvd bonus.mpg
  AUDIO_DECL=""
else
  ffmpeg $q -f lavfi -i testsrc=size=720x480:rate=30000/1001:duration=6 -f lavfi -i sine=frequency=440:sample_rate=48000:duration=6 \
    -f lavfi -i sine=frequency=660:sample_rate=48000:duration=6 -map 0:v -map 1:a -map 2:a $NTSC \
    -c:a:0 ac3 -b:a:0 192k -ac:a:0 2 -c:a:1 pcm_s16be -ac:a:1 2 -f dvd main.mpg
  ffmpeg $q -f lavfi -i testsrc2=size=720x480:rate=30000/1001:duration=3 -f lavfi -i sine=frequency=330:sample_rate=48000:duration=3 \
    $NTSC -c:a ac3 -b:a 128k -ac 2 -f dvd bonus.mpg
  AUDIO_DECL='<audio lang="ja"/><audio lang="en"/>'
fi

cat > dvd.xml <<XML
<dvdauthor dest="DVD" jumppad="0">
  <vmgm>
    <fpc>jump vmgm menu 1;</fpc>
    <menus>
      <video format="ntsc" aspect="16:9"/>
      <subpicture lang="ja"/>
      <pgc entry="title">
        <vob file="menu_spu.mpg" pause="inf"/>
        <button name="b1">jump title 1;</button>
        <button name="b2">jump title 2;</button>
      </pgc>
    </menus>
  </vmgm>
  <titleset>
    <titles>
      <video format="ntsc" aspect="16:9"/>
      $AUDIO_DECL
      <pgc>
        <vob file="main.mpg" chapters="0,0:02,0:04"/>
        <post>call vmgm menu 1;</post>
      </pgc>
      <pgc>
        <vob file="bonus.mpg"/>
        <post>call vmgm menu 1;</post>
      </pgc>
    </titles>
  </titleset>
</dvdauthor>
XML
VIDEO_FORMAT=NTSC dvdauthor -x dvd.xml > dvdauthor.log 2>&1 || { tail -20 dvdauthor.log; exit 1; }
find DVD -exec touch -h -d @"$SOURCE_DATE_EPOCH" {} +
genisoimage -quiet -dvd-video -udf -V "MENU_TEST" -o "$OUT" DVD
ls -la "$OUT"
