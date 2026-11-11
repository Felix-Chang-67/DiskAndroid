"""
生成一张能播放的测试蓝光（UDF 镜像），给原生蓝光播放器的电脑测试用：
  - 00001.m2ts：6 秒 H.264 720p25 + 日语 AC-3 立体声（440 Hz）+ 英语 LPCM 立体声（660 Hz）
  - 00002.m2ts：3 秒 H.264 + 日语 AC-3
  - 00000.mpls → 00001（章节 0、2、4 秒），00001.mpls → 00002
  - index.bdmv：First Play 和标题 1 → 电影对象 0（PlayPL 0），标题 2 → 电影对象 1（PlayPL 1）
数据结构按 BD-ROM 规范（参照 libbluray 1.5.0 的 index_parse.c / mpls_parse.c / clpi_parse.c / mobj_parse.c），
沿用 core/video 的 make_bd_fixture.py；剪辑没有 EP map（libbluray 会从头播到尾，不影响这个测试）。
用法：python3 make_bd.py <输出 ISO>
"""
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile

out_iso = sys.argv[1]
work = tempfile.mkdtemp(prefix="bdtest-")
root = os.path.join(work, "disc")
for d in ("BDMV/PLAYLIST", "BDMV/CLIPINF", "BDMV/STREAM", "BDMV/BACKUP"):
    os.makedirs(os.path.join(root, d), exist_ok=True)

def u8(v): return struct.pack(">B", v)
def u16(v): return struct.pack(">H", v)
def u32(v): return struct.pack(">I", v)

# ---------------- 流 ----------------
def make_clip(name, seconds, lpcm):
    path = os.path.join(root, "BDMV/STREAM", name + ".m2ts")
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
           "-f", "lavfi", "-i", f"testsrc2=size=1280x720:rate=25:duration={seconds}",
           "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=48000:duration={seconds}"]
    if lpcm:
        cmd += ["-f", "lavfi", "-i", f"sine=frequency=660:sample_rate=48000:duration={seconds}"]
    cmd += ["-map", "0:v", "-map", "1:a"] + (["-map", "2:a"] if lpcm else [])
    cmd += ["-c:v", "libx264", "-preset", "veryfast", "-profile:v", "high", "-level", "4.1", "-pix_fmt", "yuv420p", "-g", "25", "-bf", "2",
            "-c:a:0", "ac3", "-b:a:0", "192k", "-ac:a:0", "2", "-metadata:s:a:0", "language=jpn"]
    if lpcm:
        cmd += ["-c:a:1", "pcm_bluray", "-ac:a:1", "2", "-metadata:s:a:1", "language=eng"]
    cmd += ["-f", "mpegts", "-mpegts_m2ts_mode", "1", path]
    subprocess.run(cmd, check=True)
    probe = json.loads(subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-of", "json", path],
                                      check=True, capture_output=True, text=True).stdout)
    streams = [(int(s["id"], 16), s["codec_type"], s["codec_name"], float(s.get("start_time", "0"))) for s in probe["streams"]]
    start = min(t for _, k, _, t in streams if k == "video")
    packets = os.path.getsize(path) // 192
    # 关键帧的时间戳和源包号（EP map 用）
    csv = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "packet=pts,pos,flags", "-of", "csv=p=0", path],
                         check=True, capture_output=True, text=True).stdout
    keys = []
    for line in csv.splitlines():
        parts = line.split(",")
        if len(parts) >= 3 and "K" in parts[2]:
            keys.append((int(parts[0]) // 2, int(parts[1]) // 192))
    return path, streams, int(start * 45000), packets, keys

# ---------------- EP map（每个关键帧一项；libbluray 定位、换音轨时要用）----------------
def ep_map(pid, entries):
    coarse = b""
    fine = b""
    for i, (t, spn) in enumerate(entries):
        coarse += u32((i << 14) | ((t >> 18) & 0x3FFF)) + u32(spn)
        fine += u32((1 << 28) | (((t >> 8) & 0x7FF) << 17) | (spn & 0x1FFFF))
    stream = u32(4 + len(coarse)) + coarse + fine
    n = len(entries)
    head = ((pid << 80) | (1 << 66) | (n << 50) | (n << 32) | 14).to_bytes(12, "big")
    body = u8(0) + u8(1) + head + stream
    return u32(2 + len(body)) + u16(1) + body

clip1 = make_clip("00001", 6, True)
clip2 = make_clip("00002", 3, False)

# ---------------- index.bdmv / MovieObject.bdmv ----------------
def hdmv_obj(id_ref, playback_type=0):
    return u16(playback_type << 14) + u16(id_ref) + u32(0)

def index_bdmv(titles):
    app_info = u32(34) + bytes([0x00, 0x00]) + bytes(32)
    body = u32(0x4000_0000) + hdmv_obj(0)          # First Play：HDMV，电影对象 0
    body += u32(0x4000_0000) + hdmv_obj(0xFFFF)     # Top Menu：没有
    body += u16(len(titles))
    for mobj in titles:
        body += u32(0x4000_0000) + hdmv_obj(mobj)
    indexes = u32(len(body)) + body
    start = 40 + len(app_info)
    return b"INDX0200" + u32(start) + u32(0) + bytes(24) + app_info + indexes

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

def stn(video, audio):
    body = u16(0) + bytes([len(video), len(audio), 0, 0, 0, 0, 0, 0]) + u32(0)
    for pid, attr in video + audio:
        body += stream_entry(pid) + attr
    return u16(len(body)) + body

def play_item(clip, in_time, seconds, stn_bytes):
    body = clip.encode() + b"M2TS" + u16(1) + u8(0) + u32(in_time) + u32(in_time + seconds * 45000)
    body += bytes(8) + u8(0) + u8(0) + u16(0) + stn_bytes
    return u16(len(body)) + body

def mpls(items, marks):
    app = u32(14) + u8(0) + u8(1) + u16(0) + bytes(8) + u16(0)
    pl_body = u16(0) + u16(len(items)) + u16(0) + b"".join(items)
    playlist = u32(len(pl_body)) + pl_body
    mk_body = u16(len(marks))
    for item, t in marks:
        mk_body += u8(0) + u8(1) + u16(item) + u32(t) + u16(0xFFFF) + u32(0)
    marks_b = u32(len(mk_body)) + mk_body
    list_pos = 40 + len(app)
    mark_pos = list_pos + len(playlist)
    return b"MPLS0200" + u32(list_pos) + u32(mark_pos) + u32(0) + bytes(20) + app + playlist + marks_b

# ---------------- clpi ----------------
def stream_coding(coding, attrs):
    body = u8(coding) + attrs
    body += bytes(21 - len(body))
    return u8(21) + body

def clpi(in_time, seconds, packets, streams, cpi):
    ts_type = u8(0x80) + b"HDMV" + bytes(25)
    clip_info_body = u16(0) + u8(1) + u8(1) + u32(0) + u32(48_000_000 // 8) + u32(packets) + bytes(128) + u16(len(ts_type)) + ts_type
    clip_info = u32(len(clip_info_body)) + clip_info_body
    seq_body = u8(0) + u8(1) + u32(0) + u8(1) + u8(0) + u16(0x1001) + u32(0) + u32(in_time) + u32(in_time + seconds * 45000)
    seq = u32(len(seq_body)) + seq_body
    prog_body = u8(0) + u8(1) + u32(0) + u16(0x0100) + u8(len(streams)) + u8(0)
    for pid, coding, attrs in streams:
        prog_body += u16(pid) + stream_coding(coding, attrs)
    prog = u32(len(prog_body)) + prog_body
    mark = u32(0)
    seq_start = 40 + len(clip_info)
    prog_start = seq_start + len(seq)
    cpi_start = prog_start + len(prog)
    mark_start = cpi_start + len(cpi)
    head = b"HDMV0200" + u32(seq_start) + u32(prog_start) + u32(cpi_start) + u32(mark_start) + u32(0) + bytes(12)
    return head + clip_info + seq + prog + cpi + mark

def write(rel, data):
    with open(os.path.join(root, rel), "wb") as f:
        f.write(data)

def describe(clip, seconds):
    path, streams, in_time, packets, keys = clip
    vids = [(pid, video_attr(0x1B, 5, 3)) for pid, k, _, _ in streams if k == "video"]
    auds = []
    cl = []
    for pid, k, name, _ in streams:
        if k == "video":
            cl.append((pid, 0x1B, bytes([0x53, 0x30, 0, 0])))
        elif name == "ac3":
            auds.append((pid, audio_attr(0x81, 3, 1, "jpn")))
            cl.append((pid, 0x81, bytes([0x31]) + b"jpn"))
        else:
            auds.append((pid, audio_attr(0x80, 3, 1, "eng")))
            cl.append((pid, 0x80, bytes([0x31]) + b"eng"))
    vpid = vids[0][0]
    return stn(vids, auds), cl, in_time, packets, ep_map(vpid, keys)

stn1, cl1, in1, pk1, ep1 = describe(clip1, 6)
stn2, cl2, in2, pk2, ep2 = describe(clip2, 3)
write("BDMV/PLAYLIST/00000.mpls", mpls([play_item("00001", in1, 6, stn1)], [(0, in1), (0, in1 + 2 * 45000), (0, in1 + 4 * 45000)]))
write("BDMV/PLAYLIST/00001.mpls", mpls([play_item("00002", in2, 3, stn2)], [(0, in2)]))
write("BDMV/CLIPINF/00001.clpi", clpi(in1, 6, pk1, cl1, ep1))
write("BDMV/CLIPINF/00002.clpi", clpi(in2, 3, pk2, cl2, ep2))
write("BDMV/index.bdmv", index_bdmv([0, 1]))
write("BDMV/MovieObject.bdmv", movie_objects([0, 1]))
for f in ("index.bdmv", "MovieObject.bdmv"):
    shutil.copy(os.path.join(root, "BDMV", f), os.path.join(root, "BDMV/BACKUP", f))

subprocess.run(["genisoimage", "-quiet", "-udf", "-iso-level", "3", "-V", "BD_TEST", "-o", out_iso, root], check=True)
shutil.rmtree(work)
print(json.dumps({"clip1": [[hex(p), k, n] for p, k, n, _ in clip1[1]], "in1": in1, "in2": in2, "keys1": clip1[4]}))
