#include "dp_bd.h"

#include <libavcodec/avcodec.h>
#include <libbluray/bluray.h>
#include <libbluray/keys.h>
#include <libbluray/log_control.h>
#include <libbluray/overlay.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dp_av.h"

#define TAG "BD"
#define VQ_BYTES (24 << 20)   /* 蓝光码率高（视频最高 40 Mbps）：队列放大到约 5 秒 */
#define AQ_BYTES (6 << 20)    /* LPCM 96 kHz / 24 位多声道码率也高 */
#define READ_UNITS 10         /* 每次读 10 个对齐单元（6144 字节一个，32 个源包） */
#define MAX_SNAPS 8

typedef struct {
    int cmd, a1, a2;
} bd_cmd;

/* 一个 PID 的 PES 组包缓冲 */
typedef struct {
    uint8_t *buf;
    int len, cap;
    bool started;
} pes_buf;

/* 图层快照（libbluray 的 FLUSH 时提交，到 pts 时显示）：只存有内容的那块矩形 */
typedef struct {
    int64_t pts;
    uint64_t serial;
    int pw, ph;              /* 图层尺寸 */
    int x, y, w, h;          /* w = 0 表示这一层是空的 */
    uint32_t *rgba;
} bd_snap;

typedef struct {
    int w, h;
    uint32_t *buf;           /* 正在画的整层（RGBA，内存顺序 R、G、B、A） */
    int x0, y0, x1, y1;      /* 有内容的范围（x1、y1 不含）；x1 <= x0 表示空 */
    bd_snap snaps[MAX_SNAPS];
    int nsnaps;
} bd_plane;

struct dp_bd {
    dp_source *src;
    dp_platform pf;
    dp_bd_options opt;
    BLURAY *bd;
    dp_av *av;

    pthread_t nav_th;
    volatile bool quit;
    bool started;

    pthread_mutex_t cmd_lock;
    bd_cmd cmds[64];
    int cmd_head, cmd_count;

    /* ---- 只在导航线程里用 ---- */
    bool nav_mode;            /* 导航模式（光盘程序在控制） */
    bool still;
    int64_t still_until_us;   /* 定时静止到什么时候，0 = 一直等按键 */
    bool ended, paused;
    bool end_pending;         /* 读到结尾了，等缓冲里的画面和声音放完再报告结束 */
    int64_t end_since_us;
    BLURAY_TITLE_INFO *ti;    /* 当前播放列表 */
    int ti_playlist;
    int playitem;
    int video_pid, video_codec;
    int audio_pid, audio_codec, audio_num;   /* 当前音轨：流号 1..N */
    int pg_num;
    bool pg_on;
    pes_buf vpes, apes;
    int64_t ts_offset;        /* 非无缝连接处把时间戳接成连续的 */
    int64_t last_video_pts;
    int64_t last_state_us;

    /* ---- 叠加层：导航线程写（libbluray 回调），视频线程读 ---- */
    pthread_mutex_t ov_lock;
    bd_plane planes[2];       /* 0 = PG（字幕），1 = IG（菜单） */
    uint64_t next_serial;
    uint32_t *comp;           /* 视频线程：合成好的整层 */
    int comp_w, comp_h;
    uint64_t comp_key0, comp_key1;
    bool comp_has;

    /* ---- 给界面的状态 ---- */
    pthread_mutex_t st_lock;
    dp_bd_state st;
};

static void emit(dp_bd *d, int ev, int a1, int a2, const char *text) {
    if (d->pf.on_event) d->pf.on_event(d->pf.opaque, ev, a1, a2, text);
}

static void set_error(dp_bd *d, const char *msg) {
    pthread_mutex_lock(&d->st_lock);
    snprintf(d->st.error, sizeof(d->st.error), "%s", msg);
    pthread_mutex_unlock(&d->st_lock);
}

/* ---------------- 编码对应 ---------------- */

static int video_codec_for(int coding) {
    switch (coding) {
        case BLURAY_STREAM_TYPE_VIDEO_MPEG1:
        case BLURAY_STREAM_TYPE_VIDEO_MPEG2: return AV_CODEC_ID_MPEG2VIDEO;
        case BLURAY_STREAM_TYPE_VIDEO_H264: return AV_CODEC_ID_H264;
        case BLURAY_STREAM_TYPE_VIDEO_HEVC: return AV_CODEC_ID_HEVC;
        case BLURAY_STREAM_TYPE_VIDEO_VC1: return AV_CODEC_ID_VC1;
        default: return AV_CODEC_ID_NONE;
    }
}

static int audio_codec_for(int coding) {
    switch (coding) {
        case BLURAY_STREAM_TYPE_AUDIO_MPEG1:
        case BLURAY_STREAM_TYPE_AUDIO_MPEG2: return AV_CODEC_ID_MP2;
        case BLURAY_STREAM_TYPE_AUDIO_LPCM: return AV_CODEC_ID_PCM_BLURAY;
        case BLURAY_STREAM_TYPE_AUDIO_AC3: return AV_CODEC_ID_AC3;
        case BLURAY_STREAM_TYPE_AUDIO_DTS:
        case BLURAY_STREAM_TYPE_AUDIO_DTSHD:
        case BLURAY_STREAM_TYPE_AUDIO_DTSHD_MASTER:
        case BLURAY_STREAM_TYPE_AUDIO_DTSHD_SECONDARY: return AV_CODEC_ID_DTS;
        case BLURAY_STREAM_TYPE_AUDIO_TRUHD: return AV_CODEC_ID_TRUEHD;
        case BLURAY_STREAM_TYPE_AUDIO_AC3PLUS:
        case BLURAY_STREAM_TYPE_AUDIO_AC3PLUS_SECONDARY: return AV_CODEC_ID_EAC3;
        default: return AV_CODEC_ID_NONE;
    }
}

static const char *audio_name(int coding) {
    switch (coding) {
        case BLURAY_STREAM_TYPE_AUDIO_MPEG1:
        case BLURAY_STREAM_TYPE_AUDIO_MPEG2: return "MPEG";
        case BLURAY_STREAM_TYPE_AUDIO_LPCM: return "LPCM";
        case BLURAY_STREAM_TYPE_AUDIO_AC3: return "AC-3";
        case BLURAY_STREAM_TYPE_AUDIO_DTS: return "DTS";
        case BLURAY_STREAM_TYPE_AUDIO_DTSHD: return "DTS-HD";
        case BLURAY_STREAM_TYPE_AUDIO_DTSHD_MASTER: return "DTS-HD MA";
        case BLURAY_STREAM_TYPE_AUDIO_TRUHD: return "TrueHD";
        case BLURAY_STREAM_TYPE_AUDIO_AC3PLUS: return "E-AC-3";
        default: return "?";
    }
}

static const char *channels_name(int format) {
    switch (format) {
        case BLURAY_AUDIO_FORMAT_MONO: return "1ch";
        case BLURAY_AUDIO_FORMAT_STEREO: return "2ch";
        case BLURAY_AUDIO_FORMAT_MULTI_CHAN: return "多声道";
        case BLURAY_AUDIO_FORMAT_COMBO: return "2ch+多声道";
        default: return "";
    }
}

/* ---------------- 叠加层（libbluray 回调，导航线程） ---------------- */

/* BT.709（蓝光图层用高清的色彩）有限范围 YCrCb + 透明度 → RGBA（内存顺序 R、G、B、A）。 */
static uint32_t pal_rgba(const BD_PG_PALETTE_ENTRY *e) {
    double y = 1.164 * (e->Y - 16), cb = e->Cb - 128, cr = e->Cr - 128;
    int r = (int)(y + 1.793 * cr + 0.5), g = (int)(y - 0.213 * cb - 0.533 * cr + 0.5), b = (int)(y + 2.112 * cb + 0.5);
    r = r < 0 ? 0 : r > 255 ? 255 : r;
    g = g < 0 ? 0 : g > 255 ? 255 : g;
    b = b < 0 ? 0 : b > 255 ? 255 : b;
    return (uint32_t)r | ((uint32_t)g << 8) | ((uint32_t)b << 16) | ((uint32_t)e->T << 24);
}

static void plane_free_snaps(bd_plane *pl, int upto) {
    for (int i = 0; i < upto && i < pl->nsnaps; i++) free(pl->snaps[i].rgba);
    if (upto >= pl->nsnaps) {
        pl->nsnaps = 0;
        return;
    }
    memmove(pl->snaps, pl->snaps + upto, sizeof(pl->snaps[0]) * (size_t)(pl->nsnaps - upto));
    pl->nsnaps -= upto;
}

static void plane_clear(bd_plane *pl) {
    if (pl->buf) memset(pl->buf, 0, (size_t)pl->w * (size_t)pl->h * 4);
    pl->x0 = pl->y0 = 0;
    pl->x1 = pl->y1 = 0;
}

static void plane_close(bd_plane *pl) {
    free(pl->buf);
    pl->buf = NULL;
    pl->w = pl->h = 0;
    pl->x0 = pl->y0 = pl->x1 = pl->y1 = 0;
    plane_free_snaps(pl, pl->nsnaps);
}

/* 提交当前画面：只复制有内容的矩形。 */
static void plane_flush(dp_bd *d, bd_plane *pl, int64_t pts) {
    bd_snap s;
    memset(&s, 0, sizeof(s));
    s.pts = pts;
    s.serial = ++d->next_serial;
    s.pw = pl->w;
    s.ph = pl->h;
    if (pl->buf && pl->x1 > pl->x0 && pl->y1 > pl->y0) {
        s.x = pl->x0;
        s.y = pl->y0;
        s.w = pl->x1 - pl->x0;
        s.h = pl->y1 - pl->y0;
        s.rgba = malloc((size_t)s.w * (size_t)s.h * 4);
        if (!s.rgba) {
            s.w = s.h = 0;
        } else {
            for (int y = 0; y < s.h; y++) memcpy(s.rgba + (size_t)y * s.w, pl->buf + (size_t)(s.y + y) * pl->w + s.x, (size_t)s.w * 4);
        }
    }
    if (pl->nsnaps == MAX_SNAPS) plane_free_snaps(pl, 1);
    pl->snaps[pl->nsnaps++] = s;
}

static void bd_overlay_cb(void *handle, const BD_OVERLAY *const ov) {
    dp_bd *d = handle;
    pthread_mutex_lock(&d->ov_lock);
    if (!ov) {
        /* 叠加层整个关闭 */
        plane_close(&d->planes[0]);
        plane_close(&d->planes[1]);
        pthread_mutex_unlock(&d->ov_lock);
        if (d->av) dp_av_refresh(d->av);
        return;
    }
    if (ov->plane > 1) {
        pthread_mutex_unlock(&d->ov_lock);
        return;
    }
    bd_plane *pl = &d->planes[ov->plane];
    int64_t pts = ov->pts > 0 ? ov->pts + d->ts_offset : DP_NOPTS;
    switch (ov->cmd) {
        case BD_OVERLAY_INIT:
            plane_close(pl);
            pl->w = ov->w;
            pl->h = ov->h;
            pl->buf = calloc((size_t)ov->w * (size_t)ov->h, 4);
            if (!pl->buf) pl->w = pl->h = 0;
            pthread_mutex_lock(&d->st_lock);
            if (ov->w > d->st.plane_w) d->st.plane_w = ov->w;
            if (ov->h > d->st.plane_h) d->st.plane_h = ov->h;
            pthread_mutex_unlock(&d->st_lock);
            break;
        case BD_OVERLAY_CLOSE:
            plane_close(pl);
            break;
        case BD_OVERLAY_CLEAR:
            plane_clear(pl);
            break;
        case BD_OVERLAY_HIDE:
            plane_clear(pl);
            plane_flush(d, pl, pts);
            break;
        case BD_OVERLAY_WIPE:
            if (pl->buf) {
                for (int y = ov->y; y < ov->y + ov->h && y < pl->h; y++) {
                    int x0 = ov->x, x1 = ov->x + ov->w > pl->w ? pl->w : ov->x + ov->w;
                    if (x1 > x0) memset(pl->buf + (size_t)y * pl->w + x0, 0, (size_t)(x1 - x0) * 4);
                }
            }
            break;
        case BD_OVERLAY_DRAW: {
            if (!pl->buf || !ov->img || !ov->palette) break;
            uint32_t pal[256];
            for (int i = 0; i < 256; i++) pal[i] = pal_rgba(&ov->palette[i]);
            const BD_PG_RLE_ELEM *e = ov->img;
            /* 每行由若干 (长度, 颜色) 组成，凑满宽度；行尾可能有长度为 0 的结束标记 */
            for (int y = 0; y < ov->h; y++) {
                int x = 0;
                while (x < ov->w) {
                    int len = e->len;
                    uint32_t c = pal[e->color & 0xFF];
                    e++;
                    if (len <= 0) continue;
                    int py = ov->y + y;
                    for (int i = 0; i < len && x < ov->w; i++, x++) {
                        int px = ov->x + x;
                        if (px < pl->w && py < pl->h) pl->buf[(size_t)py * pl->w + px] = c;
                    }
                }
            }
            /* 有内容的范围 */
            int x1 = ov->x + ov->w > pl->w ? pl->w : ov->x + ov->w;
            int y1 = ov->y + ov->h > pl->h ? pl->h : ov->y + ov->h;
            if (pl->x1 <= pl->x0) {
                pl->x0 = ov->x;
                pl->y0 = ov->y;
                pl->x1 = x1;
                pl->y1 = y1;
            } else {
                if (ov->x < pl->x0) pl->x0 = ov->x;
                if (ov->y < pl->y0) pl->y0 = ov->y;
                if (x1 > pl->x1) pl->x1 = x1;
                if (y1 > pl->y1) pl->y1 = y1;
            }
            break;
        }
        case BD_OVERLAY_FLUSH:
            plane_flush(d, pl, pts);
            break;
        default:
            break;
    }
    pthread_mutex_unlock(&d->ov_lock);
    if (ov->cmd == BD_OVERLAY_FLUSH || ov->cmd == BD_OVERLAY_HIDE || ov->cmd == BD_OVERLAY_CLOSE) {
        if (d->av) dp_av_refresh(d->av);
    }
}

/* src 叠在 dst 上（普通 RGBA）。 */
static void blit_over(uint32_t *dst, int dw, int dh, const bd_snap *s) {
    for (int y = 0; y < s->h; y++) {
        int dy = s->y + y;
        if (dy >= dh) break;
        const uint32_t *sp = s->rgba + (size_t)y * s->w;
        uint32_t *dp = dst + (size_t)dy * dw + s->x;
        for (int x = 0; x < s->w && s->x + x < dw; x++) {
            uint32_t c = sp[x];
            uint32_t a = c >> 24;
            if (a == 0) continue;
            if (a == 255 || (dp[x] >> 24) == 0) {
                dp[x] = c;
                continue;
            }
            uint32_t o = dp[x];
            uint32_t ia = 255 - a;
            uint32_t r = ((c & 0xFF) * a + (o & 0xFF) * ia) / 255;
            uint32_t g = (((c >> 8) & 0xFF) * a + ((o >> 8) & 0xFF) * ia) / 255;
            uint32_t b = (((c >> 16) & 0xFF) * a + ((o >> 16) & 0xFF) * ia) / 255;
            uint32_t oa = a + ((o >> 24) * ia) / 255;
            dp[x] = r | (g << 8) | (b << 16) | (oa << 24);
        }
    }
}

/* 选出 pts 时刻该显示的快照，更早的扔掉。返回下标，-1 = 没有。 */
static int plane_pick(bd_plane *pl, int64_t pts) {
    int pick = -1;
    for (int i = 0; i < pl->nsnaps; i++) {
        if (pl->snaps[i].pts == DP_NOPTS || pts == DP_NOPTS || pl->snaps[i].pts <= pts) pick = i;
    }
    if (pick > 0) {
        plane_free_snaps(pl, pick);
        pick = 0;
    }
    return pick;
}

/* 视频线程每帧调用（dp_av 的叠加层回调）：PG（字幕）和 IG（菜单）合成一层。 */
static bool bd_overlay(void *owner, int64_t pts, int fw, int fh, const uint32_t **rgba, int *w, int *h, bool *changed) {
    dp_bd *d = owner;
    (void)fw;
    (void)fh;
    pthread_mutex_lock(&d->ov_lock);
    int p0 = plane_pick(&d->planes[0], pts);
    int p1 = plane_pick(&d->planes[1], pts);
    const bd_snap *s0 = p0 >= 0 ? &d->planes[0].snaps[p0] : NULL;
    const bd_snap *s1 = p1 >= 0 ? &d->planes[1].snaps[p1] : NULL;
    uint64_t k0 = s0 ? s0->serial : 0, k1 = s1 ? s1->serial : 0;
    bool has = (s0 && s0->w > 0) || (s1 && s1->w > 0);
    int cw = 0, ch = 0;
    if (s0 && s0->pw > cw) cw = s0->pw;
    if (s1 && s1->pw > cw) cw = s1->pw;
    if (s0 && s0->ph > ch) ch = s0->ph;
    if (s1 && s1->ph > ch) ch = s1->ph;
    *changed = k0 != d->comp_key0 || k1 != d->comp_key1;
    if (*changed && has && cw > 0 && ch > 0) {
        if (cw != d->comp_w || ch != d->comp_h) {
            free(d->comp);
            d->comp = malloc((size_t)cw * (size_t)ch * 4);
            d->comp_w = d->comp ? cw : 0;
            d->comp_h = d->comp ? ch : 0;
        }
        if (d->comp) {
            memset(d->comp, 0, (size_t)d->comp_w * (size_t)d->comp_h * 4);
            if (s0 && s0->w > 0) blit_over(d->comp, d->comp_w, d->comp_h, s0);
            if (s1 && s1->w > 0) blit_over(d->comp, d->comp_w, d->comp_h, s1);
        }
    }
    d->comp_key0 = k0;
    d->comp_key1 = k1;
    d->comp_has = has && d->comp;
    *rgba = d->comp;
    *w = d->comp_w;
    *h = d->comp_h;
    bool r = d->comp_has;
    pthread_mutex_unlock(&d->ov_lock);
    return r;
}

/* ---------------- M2TS 解复用（导航线程） ---------------- */

static int64_t parse_pts(const uint8_t *p) {
    return ((int64_t)((p[0] >> 1) & 7) << 30) | ((int64_t)p[1] << 22) | ((int64_t)(p[2] >> 1) << 15) | ((int64_t)p[3] << 7) | (p[4] >> 1);
}

static void pes_reset(pes_buf *pb) {
    pb->len = 0;
    pb->started = false;
}

static bool pes_append(pes_buf *pb, const uint8_t *data, int n) {
    if (pb->len + n > pb->cap) {
        int cap = pb->cap ? pb->cap * 2 : 256 * 1024;
        while (cap < pb->len + n) cap *= 2;
        uint8_t *b = realloc(pb->buf, (size_t)cap);
        if (!b) return false;
        pb->buf = b;
        pb->cap = cap;
    }
    memcpy(pb->buf + pb->len, data, (size_t)n);
    pb->len += n;
    return true;
}

/* 一个完整的 PES：取出时间戳和数据，送去解码。 */
static void pes_emit(dp_bd *d, pes_buf *pb, bool video) {
    const uint8_t *b = pb->buf;
    int len = pb->len;
    pes_reset(pb);
    if (len < 9 || b[0] || b[1] || b[2] != 1) return;
    int sid = b[3];
    int flags = b[7], hlen = b[8];
    if (9 + hlen > len) return;
    int64_t pts = DP_NOPTS, dts = DP_NOPTS;
    int p = 9;
    if ((flags & 0x80) && p + 5 <= len) {
        pts = parse_pts(b + p);
        p += 5;
    }
    if ((flags & 0xC0) == 0xC0 && p + 5 <= len) {
        dts = parse_pts(b + p);
        p += 5;
    }
    /* stream_id_extension（TrueHD 里夹带的 AC-3 核心是 0x76，TrueHD 本身是 0x72） */
    int ext_id = -1;
    if (sid == 0xFD && (flags & 0x01)) {
        if (flags & 0x20) p += 6;  /* ESCR */
        if (flags & 0x10) p += 3;  /* ES_rate */
        if (flags & 0x08) p += 1;  /* DSM trick mode */
        if (flags & 0x04) p += 1;  /* additional copy info */
        if (flags & 0x02) p += 2;  /* previous PES CRC */
        if (p < 9 + hlen) {
            int ef = b[p++];
            if (ef & 0x80) p += 16;                          /* PES private data */
            if ((ef & 0x40) && p < len) p += 1 + b[p];       /* pack header field */
            if (ef & 0x20) p += 2;                           /* program packet sequence counter */
            if (ef & 0x10) p += 2;                           /* P-STD buffer */
            if ((ef & 0x01) && p + 1 < 9 + hlen + 1 && p + 1 < len) {
                int el = b[p] & 0x7F;
                if (el >= 1 && !(b[p + 1] & 0x80)) ext_id = b[p + 1] & 0x7F;
            }
        }
    }
    const uint8_t *payload = b + 9 + hlen;
    int size = len - 9 - hlen;
    if (size <= 0) return;
    if (pts != DP_NOPTS) pts += d->ts_offset;
    if (dts != DP_NOPTS) dts += d->ts_offset;
    if (video) {
        if (pts != DP_NOPTS) d->last_video_pts = pts;
        dp_av_video(d->av, d->video_codec, pts, dts, payload, size);
    } else {
        if (d->audio_codec == AV_CODEC_ID_TRUEHD && ext_id == 0x76) return; /* 跳过 AC-3 核心，只解 TrueHD */
        dp_av_audio(d->av, d->audio_pid, d->audio_codec, pts, payload, size);
    }
}

static void demux(dp_bd *d, const uint8_t *buf, int len) {
    for (int off = 0; off + 192 <= len; off += 192) {
        const uint8_t *t = buf + off + 4; /* 跳过 4 字节 TP_extra_header */
        if (t[0] != 0x47) continue;
        int pid = ((t[1] & 0x1F) << 8) | t[2];
        bool pusi = (t[1] & 0x40) != 0;
        int afc = (t[3] >> 4) & 3;
        if (afc == 0 || afc == 2) continue;
        int p = 4;
        if (afc == 3) {
            p += 1 + t[4];
            if (p >= 188) continue;
        }
        pes_buf *pb = NULL;
        bool video = false;
        if (pid == d->video_pid && d->video_codec != AV_CODEC_ID_NONE) {
            pb = &d->vpes;
            video = true;
        } else if (pid == d->audio_pid && d->audio_codec != AV_CODEC_ID_NONE) {
            pb = &d->apes;
        }
        if (!pb) continue;
        if (pusi) {
            if (pb->started) pes_emit(d, pb, video);
            pb->started = true;
            pb->len = 0;
        }
        if (!pb->started) continue;
        pes_append(pb, t + p, 188 - p);
        /* 有长度的 PES（音频）收齐了就马上送，别等下一个包 */
        if (!video && pb->len >= 6) {
            int plen = (pb->buf[4] << 8) | pb->buf[5];
            if (plen > 0 && pb->len >= 6 + plen) pes_emit(d, pb, false);
        }
    }
}

/* 静止画面 / 标题结束：最后一个视频 PES 后面不会再有包了，直接送出去（否则菜单的静止画面出不来）。 */
static void emit_pending(dp_bd *d) {
    if (d->vpes.started && d->vpes.len > 0) pes_emit(d, &d->vpes, true);
    if (d->apes.started && d->apes.len > 0) pes_emit(d, &d->apes, false);
}

/* ---------------- 导航 ---------------- */

/* 读到播放列表结尾：剩下的数据送出去、解码器排空，等放完再报告结束。 */
static void begin_end(dp_bd *d) {
    if (d->end_pending || d->ended) return;
    emit_pending(d);
    dp_av_drain(d->av);
    d->end_pending = true;
    d->end_since_us = dp_now_us();
}

static void flush_all(dp_bd *d, const char *why) {
    d->end_pending = false;
    dp_av_flush(d->av, why);
    pes_reset(&d->vpes);
    pes_reset(&d->apes);
    d->still = false;
    d->still_until_us = 0;
}

/* 当前播放项（剪辑）：视频 / 音频 PID、给界面的音轨和字幕列表。 */
static void select_clip(dp_bd *d) {
    if (!d->ti || d->playitem < 0 || (uint32_t)d->playitem >= d->ti->clip_count) return;
    const BLURAY_CLIP_INFO *c = &d->ti->clips[d->playitem];
    int vpid = 0, vcodec = AV_CODEC_ID_NONE;
    if (c->video_stream_count > 0) {
        vpid = c->video_streams[0].pid;
        vcodec = video_codec_for(c->video_streams[0].coding_type);
    }
    if (vpid != d->video_pid || vcodec != d->video_codec) {
        d->video_pid = vpid;
        d->video_codec = vcodec;
        pes_reset(&d->vpes);
        const char *vn = vcodec == AV_CODEC_ID_H264 ? "H.264" : vcodec == AV_CODEC_ID_HEVC ? "HEVC" : vcodec == AV_CODEC_ID_VC1 ? "VC-1" :
                         vcodec == AV_CODEC_ID_MPEG2VIDEO ? "MPEG-2" : "不支持的格式";
        dp_log(DP_LOG_INFO, TAG, "剪辑 %.5s：视频 PID %04X（%s）", c->clip_id, vpid, vn);
    }
    pthread_mutex_lock(&d->st_lock);
    dp_bd_state *s = &d->st;
    s->audio_count = c->audio_stream_count > 32 ? 32 : c->audio_stream_count;
    for (int i = 0; i < s->audio_count; i++) {
        const BLURAY_STREAM_INFO *a = &c->audio_streams[i];
        char lang[4] = {0};
        memcpy(lang, a->lang, 3);
        snprintf(s->audio_desc[i], sizeof(s->audio_desc[i]), "%s %s %s", lang[0] ? lang : "---", audio_name(a->coding_type),
                 channels_name(a->format));
    }
    s->pg_count = c->pg_stream_count > 32 ? 32 : c->pg_stream_count;
    for (int i = 0; i < s->pg_count; i++) {
        char lang[4] = {0};
        memcpy(lang, c->pg_streams[i].lang, 3);
        snprintf(s->pg_desc[i], sizeof(s->pg_desc[i]), "%s", lang[0] ? lang : "---");
    }
    pthread_mutex_unlock(&d->st_lock);
}

/* 当前音轨（流号 1..N）→ PID 和解码器。 */
static void select_audio(dp_bd *d) {
    int pid = 0, codec = AV_CODEC_ID_NONE;
    if (d->ti && d->playitem >= 0 && (uint32_t)d->playitem < d->ti->clip_count) {
        const BLURAY_CLIP_INFO *c = &d->ti->clips[d->playitem];
        if (d->audio_num >= 1 && d->audio_num <= c->audio_stream_count) {
            const BLURAY_STREAM_INFO *a = &c->audio_streams[d->audio_num - 1];
            pid = a->pid;
            codec = audio_codec_for(a->coding_type);
        }
    }
    if (pid != d->audio_pid || codec != d->audio_codec) {
        d->audio_pid = pid;
        d->audio_codec = codec;
        pes_reset(&d->apes);
        dp_av_flush_audio(d->av);
        dp_log(DP_LOG_INFO, TAG, "音轨：第 %d 条，PID %04X", d->audio_num, pid);
    }
    pthread_mutex_lock(&d->st_lock);
    d->st.audio_current = d->audio_num >= 1 ? d->audio_num - 1 : -1;
    d->st.audio_codec = codec;
    pthread_mutex_unlock(&d->st_lock);
}

static void load_playlist(dp_bd *d, int playlist) {
    if (d->ti && d->ti_playlist == playlist) return;
    if (d->ti) bd_free_title_info(d->ti);
    d->ti = bd_get_playlist_info(d->bd, (uint32_t)playlist, 0);
    d->ti_playlist = playlist;
    d->playitem = 0;
    if (!d->ti) return;
    pthread_mutex_lock(&d->st_lock);
    d->st.playlist = playlist;
    d->st.chapters = (int)d->ti->chapter_count;
    d->st.duration_ms = (int64_t)(d->ti->duration / 90);
    d->st.angles = d->ti->angle_count ? d->ti->angle_count : 1;
    pthread_mutex_unlock(&d->st_lock);
    dp_log(DP_LOG_INFO, TAG, "播放列表 %05d.mpls：%d 段，%u 章，%lld 秒", playlist, d->ti->clip_count, d->ti->chapter_count,
           (long long)(d->ti->duration / 90000));
    select_clip(d);
    select_audio(d);
}

/* 直接播放某个播放列表（BD-J 盘、或者用户选了"直接播放"）。 */
static bool play_playlist(dp_bd *d, int playlist) {
    if (!bd_select_playlist(d->bd, (uint32_t)playlist)) {
        dp_log(DP_LOG_WARN, TAG, "播放列表 %05d.mpls 打不开", playlist);
        return false;
    }
    d->nav_mode = false;
    bd_get_event(d->bd, NULL); /* 非导航模式要先初始化事件队列 */
    flush_all(d, "直接播放播放列表");
    load_playlist(d, playlist);
    pthread_mutex_lock(&d->st_lock);
    d->st.mode = DP_BD_MODE_PLAYLIST;
    pthread_mutex_unlock(&d->st_lock);
    dp_log(DP_LOG_INFO, TAG, "直接播放 %05d.mpls（没有菜单）", playlist);
    return true;
}

/* 主片：最长的有意义的播放列表（libbluray 的规则）。 */
static int main_playlist(dp_bd *d) {
    uint32_t n = bd_get_titles(d->bd, TITLES_RELEVANT, 0);
    if (n == 0) return -1;
    int idx = bd_get_main_title(d->bd);
    if (idx < 0) idx = 0;
    BLURAY_TITLE_INFO *t = bd_get_title_info(d->bd, (uint32_t)idx, 0);
    if (!t) return -1;
    int pl = (int)t->playlist;
    bd_free_title_info(t);
    return pl;
}

static void handle_event(dp_bd *d, const BD_EVENT *ev) {
    switch (ev->event) {
        case BD_EVENT_ERROR:
            if (ev->param == BD_ERROR_BDJ) {
                dp_log(DP_LOG_WARN, TAG, "光盘菜单是 BD-J（Java），Android 上没法运行：直接播放主片");
                pthread_mutex_lock(&d->st_lock);
                d->st.bdj = true;
                pthread_mutex_unlock(&d->st_lock);
                int pl = main_playlist(d);
                if (pl < 0 || !play_playlist(d, pl)) {
                    set_error(d, "光盘菜单是 BD-J（Android 上不支持），也没找到能直接播放的主片");
                    d->ended = true;
                    emit(d, DP_EV_ERROR, 0, 0, "光盘菜单是 BD-J（Android 上不支持），也没找到能直接播放的主片");
                }
                emit(d, DP_EV_STATE, 0, 0, NULL);
            } else {
                dp_log(DP_LOG_ERROR, TAG, "光盘程序出错（%u）", ev->param);
                set_error(d, "光盘程序（HDMV）执行出错");
                d->ended = true;
                emit(d, DP_EV_ERROR, 0, 0, "光盘程序（HDMV）执行出错");
            }
            break;
        case BD_EVENT_ENCRYPTED: {
            /* 打开时 AACS 已经"成功"，播放时读到的还是密文：密钥不对，或者光驱开了总线加密 */
            const char *m = ev->param == BD_ERROR_BDPLUS ? "这张盘有 BD+ 保护（本 App 不支持 BD+）"
                            : d->st.aacs ? "AACS 解密不对：读到的视频还是加密的（KEYDB.cfg 里这张盘的密钥可能不对）"
                                         : "该盘有版权保护，需要启用解密组件（AACS）";
            dp_log(DP_LOG_ERROR, TAG, "%s", m);
            set_error(d, m);
            d->ended = true;
            emit(d, DP_EV_ERROR, 0, 0, m);
            break;
        }
        case BD_EVENT_READ_ERROR:
            pthread_mutex_lock(&d->st_lock);
            d->st.read_errors++;
            pthread_mutex_unlock(&d->st_lock);
            break;
        case BD_EVENT_ANGLE:
            pthread_mutex_lock(&d->st_lock);
            d->st.angle = (int)ev->param;
            pthread_mutex_unlock(&d->st_lock);
            break;
        case BD_EVENT_TITLE:
            pthread_mutex_lock(&d->st_lock);
            d->st.title = (int)ev->param;
            pthread_mutex_unlock(&d->st_lock);
            dp_log(DP_LOG_INFO, TAG, "标题：%s", ev->param == BLURAY_TITLE_FIRST_PLAY ? "First Play" : ev->param == BLURAY_TITLE_TOP_MENU ? "顶层菜单" : "");
            emit(d, DP_EV_STATE, 0, 0, NULL);
            break;
        case BD_EVENT_PLAYLIST:
            load_playlist(d, (int)ev->param);
            emit(d, DP_EV_STATE, 0, 0, NULL);
            break;
        case BD_EVENT_PLAYITEM:
            d->playitem = (int)ev->param;
            pthread_mutex_lock(&d->st_lock);
            d->st.playitem = (int)ev->param;
            pthread_mutex_unlock(&d->st_lock);
            select_clip(d);
            select_audio(d);
            break;
        case BD_EVENT_CHAPTER:
            pthread_mutex_lock(&d->st_lock);
            d->st.chapter = (int)ev->param;
            pthread_mutex_unlock(&d->st_lock);
            emit(d, DP_EV_STATE, 0, 0, NULL);
            break;
        case BD_EVENT_END_OF_TITLE:
            if (!d->nav_mode) begin_end(d);
            else emit_pending(d);
            break;
        case BD_EVENT_AUDIO_STREAM:
            d->audio_num = ev->param == 0xFF ? 0 : (int)ev->param;
            select_audio(d);
            emit(d, DP_EV_STATE, 0, 0, NULL);
            break;
        case BD_EVENT_PG_TEXTST_STREAM:
            d->pg_num = ev->param == 0xFFF ? 0 : (int)ev->param;
            pthread_mutex_lock(&d->st_lock);
            d->st.pg_current = d->pg_num >= 1 ? d->pg_num - 1 : -1;
            pthread_mutex_unlock(&d->st_lock);
            break;
        case BD_EVENT_PG_TEXTST:
            d->pg_on = ev->param != 0;
            pthread_mutex_lock(&d->st_lock);
            d->st.pg_shown = d->pg_on;
            pthread_mutex_unlock(&d->st_lock);
            break;
        case BD_EVENT_PLAYLIST_STOP:
            flush_all(d, "播放列表停止");
            break;
        case BD_EVENT_SEEK:
            flush_all(d, "跳转");
            break;
        case BD_EVENT_DISCONTINUITY:
            /* 非无缝连接：新剪辑的时间戳另起，接在已送出的视频后面，保持时间轴连续 */
            if (d->last_video_pts != DP_NOPTS) {
                int64_t newpts = (int64_t)ev->param * 2; /* 45 kHz → 90 kHz */
                d->ts_offset = d->last_video_pts + 3003 - newpts;
                dp_log(DP_LOG_DEBUG, TAG, "时间轴不连续：偏移 %lld", (long long)d->ts_offset);
            }
            break;
        case BD_EVENT_STILL:
            if (ev->param) {
                d->still = true;
                emit_pending(d);
                dp_av_set_still(d->av, true);
                dp_av_drain(d->av);
            } else {
                d->still = false;
                d->still_until_us = 0;
                dp_av_set_still(d->av, false);
            }
            break;
        case BD_EVENT_STILL_TIME:
            if (!d->still) {
                d->still = true;
                emit_pending(d);
                dp_av_set_still(d->av, true);
                dp_av_drain(d->av);
                d->still_until_us = ev->param ? dp_now_us() + (int64_t)ev->param * 1000000 : 0;
                dp_log(DP_LOG_INFO, TAG, "静止画面：%s", ev->param ? "定时" : "一直停住，等按键");
                emit(d, DP_EV_STILL, ev->param ? (int)ev->param : 255, 0, NULL);
            }
            break;
        case BD_EVENT_MENU:
            pthread_mutex_lock(&d->st_lock);
            d->st.menu = ev->param != 0;
            pthread_mutex_unlock(&d->st_lock);
            dp_log(DP_LOG_INFO, TAG, ev->param ? "显示菜单" : "关闭菜单");
            emit(d, DP_EV_MENU, ev->param ? 1 : 0, 0, NULL);
            break;
        case BD_EVENT_POPUP:
            pthread_mutex_lock(&d->st_lock);
            d->st.popup = ev->param != 0;
            pthread_mutex_unlock(&d->st_lock);
            break;
        default:
            break;
    }
}

static int64_t stream_clock(dp_bd *d) {
    int64_t c = dp_av_clock(d->av);
    return c == DP_NOPTS ? -1 : c - d->ts_offset;
}

static void update_position(dp_bd *d) {
    uint64_t t = bd_tell_time(d->bd);
    uint32_t ch = bd_get_current_chapter(d->bd);
    pthread_mutex_lock(&d->st_lock);
    d->st.position_ms = (int64_t)(t / 90);
    if (!d->nav_mode) d->st.chapter = (int)ch + 1;
    d->st.still = d->still;
    d->st.paused = d->paused;
    d->st.ended = d->ended;
    d->st.bytes_read = d->src->bytes_read;
    pthread_mutex_unlock(&d->st_lock);
}

static void exec_command(dp_bd *d, bd_cmd c) {
    int64_t pts = stream_clock(d);
    int r = 1;
    switch (c.cmd) {
        case DP_CMD_UP: r = bd_user_input(d->bd, pts, BD_VK_UP); break;
        case DP_CMD_DOWN: r = bd_user_input(d->bd, pts, BD_VK_DOWN); break;
        case DP_CMD_LEFT: r = bd_user_input(d->bd, pts, BD_VK_LEFT); break;
        case DP_CMD_RIGHT: r = bd_user_input(d->bd, pts, BD_VK_RIGHT); break;
        case DP_CMD_ACTIVATE: r = bd_user_input(d->bd, pts, BD_VK_ENTER); break;
        case DP_CMD_MOUSE_SELECT:
        case DP_CMD_MOUSE_ACTIVATE: {
            /* 界面给的是视频帧坐标，换算成图层坐标（图层一般是 1920×1080） */
            dp_av_stats as;
            dp_av_get_stats(d->av, &as);
            int pw = d->st.plane_w ? d->st.plane_w : 1920, ph = d->st.plane_h ? d->st.plane_h : 1080;
            int x = as.video_w > 0 ? c.a1 * pw / as.video_w : c.a1;
            int y = as.video_h > 0 ? c.a2 * ph / as.video_h : c.a2;
            r = bd_mouse_select(d->bd, pts, (uint16_t)x, (uint16_t)y);
            if (c.cmd == DP_CMD_MOUSE_ACTIVATE && r > 0) r = bd_user_input(d->bd, pts, BD_VK_MOUSE_ACTIVATE);
            break;
        }
        case DP_CMD_MENU:
            if (!d->nav_mode) {
                r = -1;
                break;
            }
            if (c.a1 == 1) r = bd_user_input(d->bd, pts, BD_VK_POPUP);
            else r = bd_menu_call(d->bd, pts);
            break;
        case DP_CMD_TITLE_PLAY:
            if (d->nav_mode) {
                r = bd_play_title(d->bd, (unsigned)c.a1);
            } else {
                r = play_playlist(d, c.a1) ? 1 : 0;
            }
            if (r > 0) {
                d->ended = false;
                d->end_pending = false;
            }
            break;
        case DP_CMD_PART_PLAY:
            if (c.a2 >= 1) r = bd_seek_chapter(d->bd, (unsigned)(c.a2 - 1)) >= 0 ? 1 : 0;
            break;
        case DP_CMD_NEXT_CHAPTER: {
            uint32_t ch = bd_get_current_chapter(d->bd);
            r = bd_seek_chapter(d->bd, ch + 1) >= 0 ? 1 : 0;
            break;
        }
        case DP_CMD_PREV_CHAPTER: {
            uint32_t ch = bd_get_current_chapter(d->bd);
            r = bd_seek_chapter(d->bd, ch > 0 ? ch - 1 : 0) >= 0 ? 1 : 0;
            break;
        }
        case DP_CMD_SEEK_MS:
            r = bd_seek_time(d->bd, (uint64_t)c.a1 * 90) >= 0 ? 1 : 0;
            break;
        case DP_CMD_AUDIO:
            r = bd_select_stream(d->bd, BLURAY_AUDIO_STREAM, (uint32_t)(c.a1 + 1), 1);
            break;
        case DP_CMD_SPU:
            if (c.a1 < 0) r = bd_select_stream(d->bd, BLURAY_PG_TEXTST_STREAM, d->pg_num > 0 ? (uint32_t)d->pg_num : 1, 0);
            else r = bd_select_stream(d->bd, BLURAY_PG_TEXTST_STREAM, (uint32_t)(c.a1 + 1), 1);
            break;
        case DP_CMD_ANGLE:
            r = bd_select_angle(d->bd, (unsigned)(c.a1 > 0 ? c.a1 - 1 : 0));
            break;
        case DP_CMD_PAUSE:
            d->paused = true;
            dp_av_pause(d->av, true);
            break;
        case DP_CMD_RESUME:
            d->paused = false;
            dp_av_pause(d->av, false);
            break;
        case DP_CMD_STILL_SKIP:
            r = bd_read_skip_still(d->bd);
            break;
        default:
            break;
    }
    if (r < 0 || (r == 0 && c.cmd != DP_CMD_PAUSE && c.cmd != DP_CMD_RESUME && c.cmd != DP_CMD_MOUSE_SELECT)) {
        dp_log(DP_LOG_INFO, TAG, "命令 %d 没有执行（光盘不允许或无效）", c.cmd);
    }
    update_position(d);
    emit(d, DP_EV_STATE, 0, 0, NULL);
}

static void process_commands(dp_bd *d) {
    for (;;) {
        pthread_mutex_lock(&d->cmd_lock);
        if (d->cmd_count == 0) {
            pthread_mutex_unlock(&d->cmd_lock);
            return;
        }
        bd_cmd c = d->cmds[d->cmd_head];
        d->cmd_head = (d->cmd_head + 1) % 64;
        d->cmd_count--;
        pthread_mutex_unlock(&d->cmd_lock);
        exec_command(d, c);
    }
}

static void *nav_thread(void *arg) {
    dp_bd *d = arg;
    dp_thread_priority(-2, "导航");
    uint8_t *buf = malloc(6144 * READ_UNITS);
    if (!buf) return NULL;
    if (d->nav_mode) {
        if (!bd_play(d->bd)) {
            dp_log(DP_LOG_WARN, TAG, "光盘菜单启动不了，直接播放主片");
            int pl = main_playlist(d);
            if (pl < 0 || !play_playlist(d, pl)) {
                set_error(d, "这张蓝光既不能从菜单播放，也没找到主片");
                d->ended = true;
                emit(d, DP_EV_ERROR, 0, 0, "这张蓝光既不能从菜单播放，也没找到主片");
            }
        } else {
            dp_log(DP_LOG_INFO, TAG, "从光盘的 First Play 开始（菜单导航）");
        }
    }
    while (!d->quit) {
        process_commands(d);
        if (d->quit) break;
        if (d->paused && !d->still) {
            dp_sleep_us(10000);
            continue;
        }
        if (d->ended) {
            dp_sleep_us(20000);
            continue;
        }
        if (d->end_pending) {
            /* 等队列放空、时钟走到最后一帧（最多再等 30 秒） */
            int64_t clk = dp_av_clock(d->av);
            bool shown = clk == DP_NOPTS || d->last_video_pts == DP_NOPTS || clk >= d->last_video_pts;
            if ((dp_av_queues_empty(d->av) && shown) || dp_now_us() - d->end_since_us > 30000000) {
                d->end_pending = false;
                d->ended = true;
                dp_log(DP_LOG_INFO, TAG, "播放结束");
                update_position(d);
                emit(d, DP_EV_END, 0, 0, NULL);
            } else {
                dp_sleep_us(20000);
            }
            continue;
        }
        if (d->still && d->still_until_us && dp_now_us() >= d->still_until_us) {
            d->still_until_us = 0;
            bd_read_skip_still(d->bd);
        }
        int64_t scr = stream_clock(d);
        if (scr >= 0) bd_set_scr(d->bd, scr);
        BD_EVENT ev;
        int n;
        if (d->nav_mode) {
            n = bd_read_ext(d->bd, buf, 6144 * READ_UNITS, &ev);
            while (ev.event != BD_EVENT_NONE) {
                handle_event(d, &ev);
                if (!bd_get_event(d->bd, &ev)) break;
            }
        } else {
            n = bd_read(d->bd, buf, 6144 * READ_UNITS);
            while (bd_get_event(d->bd, &ev)) handle_event(d, &ev);
        }
        if (n < 0) {
            if (!d->ended) {
                dp_log(DP_LOG_ERROR, TAG, "读取出错");
                set_error(d, "读取出错（盘片读不出，或者需要解密）");
                d->ended = true;
                emit(d, DP_EV_ERROR, 0, 0, "读取出错（盘片读不出，或者需要解密）");
            }
        } else if (n == 0) {
            if (!d->nav_mode && !d->ended) {
                begin_end(d);
            } else {
                dp_sleep_us(20000); /* 菜单 / 静止画面里没数据：别空转 */
            }
        } else {
            demux(d, buf, n);
        }
        int64_t now = dp_now_us();
        if (now - d->last_state_us > 250000) {
            d->last_state_us = now;
            update_position(d);
        }
    }
    free(buf);
    return NULL;
}

/* ---------------- 打开 / 关闭 ---------------- */

static void bd_log(const char *msg) {
    char buf[512];
    snprintf(buf, sizeof(buf), "%s", msg);
    size_t n = strlen(buf);
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) buf[--n] = 0;
    dp_log(DP_LOG_DEBUG, "BLURAY", "%s", buf);
}

/*
 * libaacs 打开失败的原因 → 给用户看的说明。libbluray 把 libaacs 的错误码原样放在 aacs_error_code 里
 * （libaacs 0.12.0 aacs.h 的 AACS_ERROR_*：-1 盘上文件坏、-2 没有 KEYDB.cfg、-3 没有处理密钥、-4 没有主机证书、
 * -5 证书被吊销、-6 打不开光驱（光驱不支持 AACS）、-7 光驱认证出错、-8 没有设备密钥、-10 不支持的 AACS 版本）。
 * 光驱那边的失败（-5 / -6 / -7）都说"该光驱不支持此盘的认证"，sense 原文由 App 的命令日志记录。
 */
static void aacs_error_text(const BLURAY_DISC_INFO *di, char *out, int len) {
    int mkbv = di->aacs_mkbv;
    switch (di->aacs_error_code) {
        case -1: snprintf(out, (size_t)len, "读不出盘上的 AACS 文件（AACS 目录损坏或读盘出错）"); break;
        case -2: snprintf(out, (size_t)len, "找不到 KEYDB.cfg 密钥库（请在\"解密组件\"里导入）"); break;
        case -3:
            snprintf(out, (size_t)len, "KEYDB.cfg 里没有这张盘的密钥（VUK），也没有能处理这张盘（MKB 版本 %d）的处理密钥", mkbv);
            break;
        case -4:
            snprintf(out, (size_t)len, "KEYDB.cfg 里没有这张盘的密钥（VUK）；要向光驱读卷标识，但 KEYDB.cfg 里没有可用的主机证书");
            break;
        case -5: snprintf(out, (size_t)len, "该光驱不支持此盘的认证：光驱拒绝了 KEYDB.cfg 里的主机证书（可能已被吊销）"); break;
        case -6: snprintf(out, (size_t)len, "该光驱不支持此盘的认证：光驱没有报告 AACS 功能"); break;
        case -7: snprintf(out, (size_t)len, "该光驱不支持此盘的认证（AACS 认证过程出错）"); break;
        case -8:
            snprintf(out, (size_t)len, "KEYDB.cfg 里没有这张盘的密钥（VUK），也没有能处理这张盘（MKB 版本 %d）的设备密钥", mkbv);
            break;
        case -10: snprintf(out, (size_t)len, "不支持 AACS 2.0（4K UHD 蓝光）"); break;
        default: snprintf(out, (size_t)len, "AACS 解密失败（错误码 %d）", di->aacs_error_code); break;
    }
}

/* index.bdmv 的版本：0300 = Ultra HD Blu-ray（用 AACS 2.0）。读不出返回 0。 */
static int index_version(BLURAY *bd) {
    void *data = NULL;
    int64_t size = 0;
    int v = 0;
    if (bd_read_file(bd, "BDMV/index.bdmv", &data, &size) && data && size >= 8 && memcmp(data, "INDX", 4) == 0) {
        const char *p = (const char *)data + 4;
        if (p[0] >= '0' && p[0] <= '9' && p[1] >= '0' && p[1] <= '9' && p[2] >= '0' && p[2] <= '9' && p[3] >= '0' && p[3] <= '9') {
            v = (p[0] - '0') * 1000 + (p[1] - '0') * 100 + (p[2] - '0') * 10 + (p[3] - '0');
        }
    }
    free(data);
    return v;
}

static int read_blocks(void *handle, void *buf, int lba, int num_blocks) {
    return dp_source_read_blocks(handle, buf, lba, num_blocks);
}

dp_bd *dp_bd_open(dp_source *src, const dp_platform *pf, const dp_bd_options *opt, char *err, int errlen) {
    dp_bd *d = calloc(1, sizeof(*d));
    if (!d) return NULL;
    d->src = src;
    d->pf = *pf;
    d->opt = *opt;
    d->last_video_pts = DP_NOPTS;
    d->ti_playlist = -1;
    pthread_mutex_init(&d->cmd_lock, NULL);
    pthread_mutex_init(&d->ov_lock, NULL);
    pthread_mutex_init(&d->st_lock, NULL);
    d->st.audio_current = -1;
    d->st.pg_current = -1;
    d->st.angles = 1;
    d->st.angle = 1;

    bd_set_debug_handler(bd_log);
    bd_set_debug_mask(DBG_CRIT | DBG_BLURAY);
    d->bd = bd_init();
    if (!d->bd || !bd_open_stream(d->bd, src, read_blocks)) {
        snprintf(err, (size_t)errlen, "libbluray 打不开这张盘（BDMV 结构读不出）");
        dp_bd_close(d);
        return NULL;
    }
    const BLURAY_DISC_INFO *di = bd_get_disc_info(d->bd);
    if (!di || !di->bluray_detected) {
        snprintf(err, (size_t)errlen, "不是蓝光视频盘（没有 BDMV）");
        dp_bd_close(d);
        return NULL;
    }
    d->st.aacs = di->aacs_detected;
    int indx = index_version(d->bd);
    if (di->aacs_detected) {
        dp_log(DP_LOG_INFO, TAG, "AACS：解密组件%s，MKB 版本 %d，index.bdmv 版本 %04d，结果 %d", di->libaacs_detected ? "已加载" : "没有加载",
               di->aacs_mkbv, indx, di->aacs_error_code);
    }
    if (di->aacs_detected && indx >= 300) {
        /* Ultra HD Blu-ray：AACS 2.0，不支持（即使 KEYDB 里有密钥也不试） */
        snprintf(err, (size_t)errlen, "不支持 AACS 2.0（4K UHD 蓝光）");
        dp_log(DP_LOG_WARN, TAG, "%s", err);
        dp_bd_close(d);
        return NULL;
    }
    if (di->aacs_detected && !di->aacs_handled) {
        if (!di->libaacs_detected) snprintf(err, (size_t)errlen, "该盘有版权保护，需要启用解密组件（AACS）");
        else aacs_error_text(di, err, errlen);
        dp_log(DP_LOG_WARN, TAG, "%s（libaacs 错误码 %d）", err, di->aacs_error_code);
        dp_bd_close(d);
        return NULL;
    }
    if (di->bdplus_detected && !di->bdplus_handled) {
        /* BD+ 是 AACS 之外的另一层保护（盘上带一段程序修补视频）。没有 libbdplus 时视频会大片花屏：不播 */
        snprintf(err, (size_t)errlen, "这张盘除了 AACS 还有 BD+ 保护（本 App 不支持 BD+）");
        dp_log(DP_LOG_WARN, TAG, "%s", err);
        dp_bd_close(d);
        return NULL;
    }
    bd_set_player_setting_str(d->bd, BLURAY_PLAYER_SETTING_AUDIO_LANG, opt->audio_lang[0] ? opt->audio_lang : "jpn");
    bd_set_player_setting_str(d->bd, BLURAY_PLAYER_SETTING_PG_LANG, opt->pg_lang[0] ? opt->pg_lang : "chi");
    bd_set_player_setting_str(d->bd, BLURAY_PLAYER_SETTING_MENU_LANG, opt->menu_lang[0] ? opt->menu_lang : "jpn");
    bd_set_player_setting(d->bd, BLURAY_PLAYER_SETTING_REGION_CODE, (uint32_t)(opt->region ? opt->region : 1));
    bd_set_player_setting(d->bd, BLURAY_PLAYER_SETTING_DECODE_PG, 1);
    bd_set_player_setting(d->bd, BLURAY_PLAYER_SETTING_PERSISTENT_STORAGE, 0);
    bd_register_overlay_proc(d->bd, d, bd_overlay_cb);

    dp_av_options ao = {
        .tag = TAG, .deinterlace = opt->deinterlace, .vq_bytes = VQ_BYTES, .aq_bytes = AQ_BYTES,
        .overlay = bd_overlay, .owner = d, .src = src,
    };
    d->av = dp_av_new(pf, &ao);
    if (!d->av) {
        snprintf(err, (size_t)errlen, "内存不足");
        dp_bd_close(d);
        return NULL;
    }
    d->st.titles = (int)di->num_titles;
    bool menus = opt->start_playlist < 0 && !di->no_menu_support && (di->first_play_supported || di->top_menu_supported);
    if (opt->start_playlist < 0 && di->bdj_detected && di->num_hdmv_titles == 0) {
        menus = false;
        d->st.bdj = true;
        dp_log(DP_LOG_WARN, TAG, "光盘菜单全是 BD-J（Java），Android 上没法运行：直接播放主片");
    }
    dp_log(DP_LOG_INFO, TAG, "打开蓝光：%u 个标题（HDMV %u，BD-J %u），%s%s，%s", di->num_titles, di->num_hdmv_titles, di->num_bdj_titles,
           di->aacs_detected ? "AACS 加密（已解）" : "没有加密", di->bdplus_detected ? "，BD+" : "", menus ? "用光盘菜单" : "直接播放");
    if (menus) {
        d->nav_mode = true;
        d->st.mode = DP_BD_MODE_MENU;
    } else {
        int pl = opt->start_playlist >= 0 ? opt->start_playlist : main_playlist(d);
        if (pl < 0 || !play_playlist(d, pl)) {
            snprintf(err, (size_t)errlen, "没找到能播放的播放列表");
            dp_bd_close(d);
            return NULL;
        }
    }
    return d;
}

int dp_bd_start(dp_bd *d) {
    if (d->started) return 0;
    if (dp_av_start(d->av) != 0) return -1;
    if (pthread_create(&d->nav_th, NULL, nav_thread, d) != 0) return -1;
    d->started = true;
    return 0;
}

void dp_bd_command(dp_bd *d, int cmd, int a1, int a2) {
    pthread_mutex_lock(&d->cmd_lock);
    if (d->cmd_count < 64) {
        d->cmds[(d->cmd_head + d->cmd_count) % 64] = (bd_cmd){cmd, a1, a2};
        d->cmd_count++;
    }
    pthread_mutex_unlock(&d->cmd_lock);
}

void dp_bd_get_state(dp_bd *d, dp_bd_state *out) {
    pthread_mutex_lock(&d->st_lock);
    *out = d->st;
    out->paused = d->paused;
    pthread_mutex_unlock(&d->st_lock);
    dp_av_stats as;
    dp_av_get_stats(d->av, &as);
    out->frames_shown = as.frames_shown;
    out->frames_dropped = as.frames_dropped;
    out->audio_frames = as.audio_frames;
    out->video_w = as.video_w;
    out->video_h = as.video_h;
    out->video_codec_id = as.video_codec;
    out->audio_codec_id = as.audio_codec;
}

void dp_bd_refresh(dp_bd *d) {
    dp_av_refresh(d->av);
}

void dp_bd_close(dp_bd *d) {
    if (!d) return;
    d->quit = true;
    if (d->av) dp_av_abort(d->av);
    if (d->started) pthread_join(d->nav_th, NULL);
    /* 先关 libbluray（关闭时还会回调叠加层，那时解码模块要还在），再停解码 / 显示线程，最后释放图层 */
    if (d->bd) {
        bd_close(d->bd);
        d->bd = NULL;
    }
    if (d->av) {
        dp_av_free(d->av);
        d->av = NULL;
    }
    if (d->ti) bd_free_title_info(d->ti);
    plane_close(&d->planes[0]);
    plane_close(&d->planes[1]);
    free(d->comp);
    free(d->vpes.buf);
    free(d->apes.buf);
    pthread_mutex_destroy(&d->cmd_lock);
    pthread_mutex_destroy(&d->ov_lock);
    pthread_mutex_destroy(&d->st_lock);
    free(d);
}
