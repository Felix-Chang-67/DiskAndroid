"""
按 BD-ROM 规范（参照 libbluray 1.5.0 的 index_parse.c / mpls_parse.c / clpi_parse.c）生成一个最小的 BDMV 目录，
给 core:video 的单元测试用；生成后用 libbluray 的 bd_info / bd_list_titles 核对能否解析。
用法：python3 make_bd_fixture.py <输出目录>
  00000.mpls 主片：2 段（00001 + 00002），共 95 分钟，12 个章节；AVC 1080i、LPCM 日语、AC-3 5.1 日语、PG 日语
  00001.mpls 特典：00003，10 分钟，2 个章节；AVC 1080i、AC-3 日语
  00002.mpls 混淆用：00001 的同一段重复 5 次（libbluray 会过滤掉）
  00003.mpls 与 00000 完全相同（重复的播放列表，会被过滤）
  00099.mpls 菜单背景：00004，30 秒，MPEG-2
"""
import os
import struct
import sys

out = sys.argv[1]
for d in ("BDMV/PLAYLIST", "BDMV/CLIPINF", "BDMV/STREAM", "BDMV/BACKUP"):
    os.makedirs(os.path.join(out, d), exist_ok=True)

TICK = 45000
IN0 = 0x0001_0000  # 播放项的起始时间（45 kHz）

def u8(v): return struct.pack(">B", v)
def u16(v): return struct.pack(">H", v)
def u32(v): return struct.pack(">I", v)

# ---------------- index.bdmv ----------------
def hdmv_obj(id_ref, playback_type=0):
    return u16(playback_type << 14) + u16(id_ref) + u32(0)

def index_bdmv(titles):
    app_info = u32(34) + bytes([0x00, 0x00]) + bytes(32)
    body = u32(0x4000_0000) + hdmv_obj(0)          # FirstPlayback：HDMV，电影对象 0
    body += u32(0x4000_0000) + hdmv_obj(0xFFFF)     # TopMenu：没有
    body += u16(len(titles))
    for mobj in titles:
        body += u32(0x4000_0000) + hdmv_obj(mobj)   # 对象类型 1（HDMV），访问类型 0
    indexes = u32(len(body)) + body
    start = 40 + len(app_info)
    return b"INDX0200" + u32(start) + u32(0) + bytes(24) + app_info + indexes

# ---------------- MovieObject.bdmv ----------------
def movie_objects(playlists):
    objs = b""
    for pl in playlists:
        cmd = bytes([0x22, 0x80, 0x00, 0x00]) + u32(pl) + u32(0)   # PlayPL pl
        objs += u16(0) + u16(1) + cmd
    body = u32(0) + u16(len(playlists)) + objs
    return b"MOBJ0200" + u32(0) + bytes(28) + u32(len(body)) + body

# ---------------- mpls ----------------
def stream_entry(pid):
    return u8(9) + u8(1) + u16(pid) + bytes(6)

def video_attr(coding, fmt, rate):
    return u8(5) + u8(coding) + u8((fmt << 4) | rate) + bytes(3)

def audio_attr(coding, ptype, freq, lang):
    return u8(5) + u8(coding) + u8((ptype << 4) | freq) + lang.encode()

def pg_attr(lang):
    return u8(5) + u8(0x90) + lang.encode() + bytes(1)

def stn(video, audio, pg):
    body = u16(0) + bytes([len(video), len(audio), len(pg), 0, 0, 0, 0, 0]) + u32(0)
    for pid, attr in video + audio + pg:
        body += stream_entry(pid) + attr
    return u16(len(body)) + body

def play_item(clip, seconds, stn_bytes, cc=1):
    body = clip.encode() + b"M2TS" + u16(cc) + u8(0) + u32(IN0) + u32(IN0 + seconds * TICK)
    body += bytes(8) + u8(0) + u8(0) + u16(0) + stn_bytes
    return u16(len(body)) + body

def mpls(items, marks):
    app = u32(14) + u8(0) + u8(1) + u16(0) + bytes(8) + u16(0)
    pl_body = u16(0) + u16(len(items)) + u16(0) + b"".join(items)
    playlist = u32(len(pl_body)) + pl_body
    mk_body = u16(len(marks))
    for item, t in marks:
        mk_body += u8(0) + u8(1) + u16(item) + u32(IN0 + int(t * TICK)) + u16(0xFFFF) + u32(0)
    marks_b = u32(len(mk_body)) + mk_body
    list_pos = 40 + len(app)
    mark_pos = list_pos + len(playlist)
    return b"MPLS0200" + u32(list_pos) + u32(mark_pos) + u32(0) + bytes(20) + app + playlist + marks_b

# ---------------- clpi ----------------
def stream_coding(coding, attrs):
    body = u8(coding) + attrs
    body += bytes(21 - len(body))
    return u8(21) + body

def clpi(seconds, streams):
    ts_type = u8(0x80) + b"HDMV" + bytes(25)
    clip_info_body = u16(0) + u8(1) + u8(1) + u32(0) + u32(48_000_000 // 8) + u32(seconds * 1000) + bytes(128) + u16(len(ts_type)) + ts_type
    clip_info = u32(len(clip_info_body)) + clip_info_body
    seq_body = u8(0) + u8(1) + u32(0) + u8(1) + u8(0) + u16(0x1001) + u32(0) + u32(IN0) + u32(IN0 + seconds * TICK)
    seq = u32(len(seq_body)) + seq_body
    prog_body = u8(0) + u8(1) + u32(0) + u16(0x0100) + u8(len(streams)) + u8(0)
    for pid, coding, attrs in streams:
        prog_body += u16(pid) + stream_coding(coding, attrs)
    prog = u32(len(prog_body)) + prog_body
    cpi = u32(0)
    mark = u32(0)
    seq_start = 40 + len(clip_info)
    prog_start = seq_start + len(seq)
    cpi_start = prog_start + len(prog)
    mark_start = cpi_start + len(cpi)
    head = b"HDMV0200" + u32(seq_start) + u32(prog_start) + u32(cpi_start) + u32(mark_start) + u32(0) + bytes(12)
    return head + clip_info + seq + prog + cpi + mark

AVC_1080I = [(0x1011, video_attr(0x1B, 4, 4))]
MAIN_STN = stn(AVC_1080I, [(0x1100, audio_attr(0x80, 3, 1, "jpn")), (0x1101, audio_attr(0x81, 6, 1, "jpn"))], [(0x1200, pg_attr("jpn"))])
BONUS_STN = stn(AVC_1080I, [(0x1100, audio_attr(0x81, 3, 1, "jpn"))], [])
MENU_STN = stn([(0x1011, video_attr(0x02, 4, 4))], [], [])

def write(rel, data):
    with open(os.path.join(out, rel), "wb") as f:
        f.write(data)

main = mpls(
    [play_item("00001", 50 * 60, MAIN_STN), play_item("00002", 45 * 60, MAIN_STN)],
    [(0, i * 5 * 60) for i in range(10)] + [(1, 0), (1, 20 * 60)],
)
write("BDMV/PLAYLIST/00000.mpls", main)
write("BDMV/PLAYLIST/00003.mpls", main)
write("BDMV/PLAYLIST/00001.mpls", mpls([play_item("00003", 10 * 60, BONUS_STN)], [(0, 0), (0, 5 * 60)]))
write("BDMV/PLAYLIST/00002.mpls", mpls([play_item("00001", 50 * 60, MAIN_STN) for _ in range(5)], [(0, 0)]))
write("BDMV/PLAYLIST/00099.mpls", mpls([play_item("00004", 30, MENU_STN)], [(0, 0)]))

main_streams = [(0x1011, 0x1B, bytes([0x44, 0x30, 0, 0])), (0x1100, 0x80, bytes([0x31]) + b"jpn"),
                (0x1101, 0x81, bytes([0x61]) + b"jpn"), (0x1200, 0x90, b"jpn" + bytes(1))]
write("BDMV/CLIPINF/00001.clpi", clpi(50 * 60, main_streams))
write("BDMV/CLIPINF/00002.clpi", clpi(45 * 60, main_streams))
write("BDMV/CLIPINF/00003.clpi", clpi(10 * 60, [(0x1011, 0x1B, bytes([0x44, 0x30, 0, 0])), (0x1100, 0x81, bytes([0x31]) + b"jpn")]))
write("BDMV/CLIPINF/00004.clpi", clpi(30, [(0x1011, 0x02, bytes([0x44, 0x30, 0, 0]))]))
for clip in ("00001", "00002", "00003", "00004"):
    write(f"BDMV/STREAM/{clip}.m2ts", bytes(192 * 32))
write("BDMV/index.bdmv", index_bdmv([0, 1]))
write("BDMV/MovieObject.bdmv", movie_objects([0, 1]))
