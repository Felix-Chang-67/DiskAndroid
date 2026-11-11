#include "dp_dvd.h"

#include <dvdnav/dvdnav.h>
#include <libavcodec/avcodec.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dp_av.h"
#include "dp_spu.h"

#define TAG "DVD"
#define MAX_SPUS 8
#define VQ_BYTES (6 << 20)
#define AQ_BYTES (1 << 20)

typedef struct {
    int cmd, a1, a2;
} dp_cmd;

struct dp_dvd {
    dp_source *src;
    dp_platform pf;
    dp_dvd_options opt;
    dvdnav_t *nav;
    dp_av *av;                /* 解码、显示、音画同步（与蓝光共用） */

    pthread_t nav_th;
    volatile bool quit;
    bool started;

    pthread_mutex_t cmd_lock;
    dp_cmd cmds[64];
    int cmd_head, cmd_count;

    /* ---- 只在导航线程里用 ---- */
    int audio_phys;          /* 当前物理音轨（0..7），-1 = 无 */
    int audio_code;          /* 要解码的流：AC-3 0x80+n、DTS 0x88+n、LPCM 0xA0+n、MPEG 0xC0+n；-1 = 无 */
    bool audio_user;         /* 用户手动选过音轨：不再按语言偏好自动选 */
    int audio_logical;       /* 光盘的"当前音轨"寄存器（SPRM1，逻辑号；15 = 光盘没指定），来自音轨事件 */
    /* 选中的格式一个包都没有、同一音轨号的别的格式却一直有：连续几个 VOBU 这样就改按实际的格式解码（见 demux_audio） */
    bool audio_sel_seen, audio_alt_seen;
    int audio_alt_code, audio_miss;
    int spu_phys;            /* 当前物理字幕（0..31），-1 = 无 */
    int64_t ts_offset;       /* 把 PTS 跳变接成连续时间轴的偏移 */
    int64_t prev_vobu_end;
    bool have_vobu;
    bool in_still;
    int still_len;
    int64_t still_since_us;
    bool menu_active;
    dp_spu_asm spu_asm;
    int spu_asm_stream;
    bool ended;
    bool paused;

    /* ---- 叠加层（字幕 / 菜单）：导航线程写，视频线程读 ---- */
    pthread_mutex_t ov_lock;
    uint32_t clut[16];
    dp_spu_image spus[MAX_SPUS];
    uint64_t spu_serial[MAX_SPUS];
    int spu_count;
    uint64_t next_serial;
    dp_highlight hl;
    bool spu_show_all;
    uint64_t ov_version;
    /* 视频线程用：合成好的叠加层（与视频帧同尺寸）和上次画的内容 */
    uint32_t *ov_buf;
    int ov_w, ov_h;
    uint64_t ov_key;

    /* ---- 给界面的状态 ---- */
    pthread_mutex_t st_lock;
    dp_dvd_state st;
};

static void emit(dp_dvd *d, int ev, int a1, int a2, const char *text) {
    if (d->pf.on_event) d->pf.on_event(d->pf.opaque, ev, a1, a2, text);
}

/* ---------------- 叠加层 ---------------- */

static void ov_bump(dp_dvd *d) {
    d->ov_version++;
    if (d->av) dp_av_refresh(d->av);
}

static void ov_clear_spus(dp_dvd *d) {
    pthread_mutex_lock(&d->ov_lock);
    for (int i = 0; i < d->spu_count; i++) free(d->spus[i].idx);
    d->spu_count = 0;
    ov_bump(d);
    pthread_mutex_unlock(&d->ov_lock);
}

static void ov_add_spu(dp_dvd *d, dp_spu_image *img) {
    int hist[4] = {0, 0, 0, 0};
    for (int i = 0; i < img->w * img->h; i++) hist[img->idx[i] & 3]++;
    dp_log(DP_LOG_DEBUG, TAG, "子图像 %dx%d @%d,%d 颜色 %d/%d/%d/%d 透明度 %d/%d/%d/%d 像素 %d/%d/%d/%d%s", img->w, img->h, img->x, img->y,
           img->color[0], img->color[1], img->color[2], img->color[3], img->alpha[0], img->alpha[1], img->alpha[2], img->alpha[3],
           hist[0], hist[1], hist[2], hist[3], img->forced ? " 强制" : "");
    pthread_mutex_lock(&d->ov_lock);
    if (d->spu_count == MAX_SPUS) {
        free(d->spus[0].idx);
        memmove(d->spus, d->spus + 1, sizeof(d->spus[0]) * (MAX_SPUS - 1));
        memmove(d->spu_serial, d->spu_serial + 1, sizeof(d->spu_serial[0]) * (MAX_SPUS - 1));
        d->spu_count--;
    }
    /* 新的一张从它开始显示的时刻起替换旧的（没有结束时间的旧图到此为止） */
    for (int i = 0; i < d->spu_count; i++) {
        if (d->spus[i].end == DP_NOPTS && img->start != DP_NOPTS) d->spus[i].end = img->start;
    }
    d->spus[d->spu_count] = *img;
    d->spu_serial[d->spu_count] = ++d->next_serial;
    d->spu_count++;
    ov_bump(d);
    pthread_mutex_unlock(&d->ov_lock);
}

/*
 * 视频线程：算出 pts 时刻的叠加层。*key 记着上次画的内容（字幕序号 + 叠加层版本），没变就不重画。
 * 返回 true 表示有叠加内容（buf 有效）。
 */
static bool ov_compose(dp_dvd *d, int64_t pts, uint32_t *buf, int w, int h, uint64_t *key, bool *changed) {
    pthread_mutex_lock(&d->ov_lock);
    int pick = -1;
    for (int i = 0; i < d->spu_count; i++) {
        const dp_spu_image *s = &d->spus[i];
        if (!s->forced && !d->spu_show_all) continue;
        bool started = s->start == DP_NOPTS || pts == DP_NOPTS || pts >= s->start;
        bool ended = s->end != DP_NOPTS && pts != DP_NOPTS && pts >= s->end;
        if (started && !ended) pick = i;
    }
    uint64_t k = ((pick < 0 ? 0 : d->spu_serial[pick]) * 1000003u) ^ (d->ov_version << 1) ^ (pick < 0 ? 1u : 0u);
    *changed = k != *key;
    bool has = pick >= 0;
    if (*changed && has) {
        memset(buf, 0, (size_t)w * (size_t)h * 4);
        dp_spu_render(&d->spus[pick], d->clut, &d->hl, buf, w, h);
    }
    *key = k;
    pthread_mutex_unlock(&d->ov_lock);
    return has;
}

/* 视频线程每帧调用（dp_av 的叠加层回调）：DVD 的字幕 / 菜单高亮合成到和视频帧一样大的 RGBA。 */
static bool dvd_overlay(void *owner, int64_t pts, int fw, int fh, const uint32_t **rgba, int *w, int *h, bool *changed) {
    dp_dvd *d = owner;
    if (fw != d->ov_w || fh != d->ov_h) {
        free(d->ov_buf);
        d->ov_buf = malloc((size_t)fw * (size_t)fh * 4);
        d->ov_w = d->ov_buf ? fw : 0;
        d->ov_h = d->ov_buf ? fh : 0;
        d->ov_key = (uint64_t)-1;
    }
    if (!d->ov_buf) return false;
    bool has = ov_compose(d, pts, d->ov_buf, fw, fh, &d->ov_key, changed);
    *rgba = d->ov_buf;
    *w = fw;
    *h = fh;
    return has;
}

/* ---------------- 导航线程 ---------------- */

static int64_t parse_pts(const uint8_t *p) {
    return ((int64_t)((p[0] >> 1) & 7) << 30) | ((int64_t)p[1] << 22) | ((int64_t)(p[2] >> 1) << 15) | ((int64_t)p[3] << 7) | (p[4] >> 1);
}

/* DVD 音频子流号 → FFmpeg 解码器。 */
static int audio_codec(int code) {
    if (code >= 0x80 && code <= 0x87) return AV_CODEC_ID_AC3;
    if (code >= 0x88 && code <= 0x8F) return AV_CODEC_ID_DTS;
    if (code >= 0xA0 && code <= 0xA7) return AV_CODEC_ID_PCM_DVD;
    if (code >= 0xC0 && code <= 0xDF) return AV_CODEC_ID_MP2;
    return AV_CODEC_ID_NONE;
}

static void handle_spu(dp_dvd *d, int phys, const uint8_t *data, int len, int64_t pts) {
    if (phys != d->spu_phys) return;
    if (d->spu_asm_stream != phys) {
        dp_spu_asm_reset(&d->spu_asm);
        d->spu_asm_stream = phys;
    }
    dp_spu_image img;
    int r = dp_spu_feed(&d->spu_asm, data, len, pts, &img);
    if (r == 1) ov_add_spu(d, &img);
}

/* 不同格式的音轨物理号可以相同（AC-3 的 0 号和 LPCM 的 0 号是两条音轨），所以按"格式基数 + 物理号"精确匹配。 */
static bool audio_selected(dp_dvd *d, int code) {
    return d->audio_code >= 0 && code == d->audio_code;
}

/*
 * 物理音轨号 + 格式 → 子流号。格式要按"逻辑音轨"查 IFO：光盘程序没指定音轨时逻辑号是 15（无效），
 * libdvdnav 自己挑了第一条可用的物理音轨，这时反查是哪条逻辑音轨（以前直接用 15 去查，查不到就当成 AC-3，
 * 只有 LPCM 的盘就没有声音）。
 */
static int audio_code_for(dp_dvd *d, int physical, int logical) {
    if (physical < 0) return -1;
    if (logical < 0 || logical >= 8) {
        logical = -1;
        for (int i = 0; i < 8; i++) {
            if (dvdnav_get_audio_logical_stream(d->nav, (uint8_t)i) == physical) {
                logical = i;
                break;
            }
        }
    }
    uint16_t fmt = logical >= 0 ? dvdnav_audio_stream_format(d->nav, (uint8_t)logical) : DVD_AUDIO_FORMAT_AC3;
    switch (fmt) {
        case DVD_AUDIO_FORMAT_LPCM: return 0xA0 + (physical & 7);
        case DVD_AUDIO_FORMAT_DTS: return 0x88 + (physical & 7);
        case DVD_AUDIO_FORMAT_MPEG:
        case DVD_AUDIO_FORMAT_MPEG2_EXT: return 0xC0 + (physical & 7);
        default: return 0x80 + (physical & 7);
    }
}

static void set_audio_code(dp_dvd *d, int code, int physical, const char *why, bool flush);

/*
 * 一个音频包：是选中的音轨就送去解码。另外记下有没有"同一个音轨号、别的格式"的包：不同格式的音轨号可以相同
 * （AC-3 的 0 号和 LPCM 的 0 号是两条音轨），所以只有选中的格式连续几个 VOBU 一个包都没有、它却一直有时，
 * 才认为是 IFO 里写的格式和实际的流对不上（见 check_audio_format）。
 */
static void demux_audio(dp_dvd *d, int code, int64_t t, const uint8_t *data, int len) {
    if (audio_selected(d, code)) {
        d->audio_sel_seen = true;
        dp_av_audio(d->av, code, audio_codec(code), t, data, len);
        return;
    }
    if (d->audio_code >= 0 && (code & 7) == (d->audio_code & 7) && audio_codec(code) != AV_CODEC_ID_NONE) {
        d->audio_alt_seen = true;
        d->audio_alt_code = code;
    }
}

/* 每个 VOBU 开头：选中的音轨格式连续 3 个 VOBU 都没有数据，同一音轨号的另一种格式却有，就改用它（免得一直没声音）。 */
static void check_audio_format(dp_dvd *d) {
    if (d->audio_sel_seen || !d->audio_alt_seen) {
        d->audio_miss = 0;
    } else if (++d->audio_miss >= 3) {
        dp_log(DP_LOG_WARN, TAG, "音轨 %d 的实际格式和 IFO 里写的不一样（要的是 %02Xh，盘上只有 %02Xh）：按实际的解码",
               (d->audio_alt_code & 7) + 1, d->audio_code, d->audio_alt_code);
        set_audio_code(d, d->audio_alt_code, d->audio_alt_code & 7, "格式纠正", false);
    }
    d->audio_sel_seen = d->audio_alt_seen = false;
}

/* 换要解码的音频流。[flush]：丢掉已经排队的声音——换音轨时要；换标题集（正片放完回菜单之类）时不要，不然结尾的声音会被截掉。 */
static void set_audio_code(dp_dvd *d, int code, int physical, const char *why, bool flush) {
    d->audio_miss = 0;
    d->audio_sel_seen = d->audio_alt_seen = false;
    if (code == d->audio_code) return;
    d->audio_phys = physical;
    d->audio_code = code;
    dp_log(DP_LOG_INFO, TAG, "音轨（%s）：物理 %d，流 %02Xh", why, physical, code < 0 ? 0 : code);
    pthread_mutex_lock(&d->st_lock);
    d->st.audio_code = code;
    pthread_mutex_unlock(&d->st_lock);
    emit(d, DP_EV_STATE, 0, 0, NULL);
    if (flush) dp_av_flush_audio(d->av);
}

/* 解一个 2048 字节的 MPEG-PS 包（DVD 的 pack），把各路数据送进队列。 */
static void demux_pack(dp_dvd *d, const uint8_t *b, int len) {
    if (len < 14 || b[0] || b[1] || b[2] != 1 || b[3] != 0xBA) return;
    int p = (b[4] & 0xC0) == 0x40 ? 14 + (b[13] & 7) : 12;
    while (p + 6 <= len) {
        if (b[p] || b[p + 1] || b[p + 2] != 1) break;
        int id = b[p + 3];
        int plen = (b[p + 4] << 8) | b[p + 5];
        int start = p + 6;
        int end = start + plen;
        if (end > len) end = len;
        p = end;
        if (id == 0xBB || id == 0xBE || id == 0xBF || id < 0xBA) continue;
        int64_t pts = DP_NOPTS;
        int q = start;
        if (q + 3 <= end && (b[q] & 0xC0) == 0x80) {
            int flags = b[q + 1];
            int hlen = b[q + 2];
            if ((flags & 0x80) && q + 8 <= end) pts = parse_pts(b + q + 3);
            q += 3 + hlen;
        } else {
            while (q < end && b[q] == 0xFF) q++;
            if (q < end && (b[q] & 0xC0) == 0x40) q += 2;
            if (q < end && (b[q] & 0xF0) == 0x20) {
                if (q + 5 <= end) pts = parse_pts(b + q);
                q += 5;
            } else if (q < end && (b[q] & 0xF0) == 0x30) {
                if (q + 5 <= end) pts = parse_pts(b + q);
                q += 10;
            } else {
                q += 1;
            }
        }
        if (q >= end) continue;
        int64_t t = pts == DP_NOPTS ? DP_NOPTS : pts + d->ts_offset;
        if (id >= 0xE0 && id <= 0xEF) {
            dp_av_video(d->av, AV_CODEC_ID_MPEG2VIDEO, t, DP_NOPTS, b + q, end - q);
        } else if (id >= 0xC0 && id <= 0xDF) {
            demux_audio(d, id, t, b + q, end - q);
        } else if (id == 0xBD) {
            int sub = b[q];
            if (sub >= 0x20 && sub <= 0x3F) {
                handle_spu(d, sub & 0x1F, b + q + 1, end - q - 1, t);
            } else if (sub >= 0x80 && sub <= 0xAF) {
                /* 子流号 + 帧数 + 首个访问单元指针，之后是数据（LPCM 还带 3 字节头，留给解码器） */
                if (q + 4 < end) demux_audio(d, sub, t, b + q + 4, end - q - 4);
            }
        }
    }
}

static void flush_all(dp_dvd *d, const char *why) {
    dp_av_flush(d->av, why);
    dp_spu_asm_reset(&d->spu_asm);
    ov_clear_spus(d);
    d->have_vobu = false;
    d->in_still = false;
}

static void update_streams_state(dp_dvd *d) {
    dp_dvd_state *s = &d->st;
    int8_t na = dvdnav_get_number_of_streams(d->nav, DVD_AUDIO_STREAM);
    int8_t ns = dvdnav_get_number_of_streams(d->nav, DVD_SUBTITLE_STREAM);
    s->audio_count = na < 0 ? 0 : (na > 8 ? 8 : na);
    for (int i = 0; i < s->audio_count; i++) {
        uint16_t lang = dvdnav_audio_stream_to_lang(d->nav, (uint8_t)i);
        uint16_t fmt = dvdnav_audio_stream_format(d->nav, (uint8_t)i);
        uint16_t ch = dvdnav_audio_stream_channels(d->nav, (uint8_t)i);
        const char *f = fmt == DVD_AUDIO_FORMAT_AC3 ? "AC-3" : fmt == DVD_AUDIO_FORMAT_LPCM ? "LPCM" : fmt == DVD_AUDIO_FORMAT_DTS ? "DTS" :
                        (fmt == DVD_AUDIO_FORMAT_MPEG || fmt == DVD_AUDIO_FORMAT_MPEG2_EXT) ? "MPEG" : "?";
        char l[3] = {(char)(lang >> 8), (char)(lang & 0xFF), 0};
        if (lang == 0xFFFF || !l[0]) strcpy(l, "--");
        snprintf(s->audio_desc[i], sizeof(s->audio_desc[i]), "%s %s %uch", l, f, (unsigned)ch);
    }
    /* 界面要逻辑序号（音轨 1、2…）。光盘的音轨寄存器有效就用它；没指定（15）时 libdvdnav 播的是第一条可用的，
     * 按物理号反查（不同格式的音轨物理号可以相同，所以寄存器有效时不能反查） */
    int8_t aphys = dvdnav_get_active_audio_stream(d->nav);
    s->audio_current = -1;
    if (d->audio_logical >= 0 && d->audio_logical < s->audio_count &&
        dvdnav_get_audio_logical_stream(d->nav, (uint8_t)d->audio_logical) == aphys) {
        s->audio_current = d->audio_logical;
    }
    for (int i = 0; s->audio_current < 0 && aphys >= 0 && i < s->audio_count; i++) {
        if (dvdnav_get_audio_logical_stream(d->nav, (uint8_t)i) == aphys) s->audio_current = i;
    }
    s->spu_count = ns < 0 ? 0 : (ns > 32 ? 32 : ns);
    for (int i = 0; i < s->spu_count; i++) {
        uint16_t lang = dvdnav_spu_stream_to_lang(d->nav, (uint8_t)i);
        char l[3] = {(char)(lang >> 8), (char)(lang & 0xFF), 0};
        if (lang == 0xFFFF || !l[0]) strcpy(l, "--");
        snprintf(s->spu_desc[i], sizeof(s->spu_desc[i]), "%s", l);
    }
    int8_t spu = dvdnav_get_active_spu_stream(d->nav);
    s->spu_shown = spu >= 0 && !(spu & 0x80);
    s->spu_current = -1;
    for (int i = 0; spu >= 0 && i < s->spu_count; i++) {
        if (dvdnav_get_spu_logical_stream(d->nav, (uint8_t)i) == (spu & 0x1F)) {
            s->spu_current = i;
            break;
        }
    }
}

static void update_position_state(dp_dvd *d) {
    int32_t title = 0, part = 0;
    dp_dvd_state *s = &d->st;
    pthread_mutex_lock(&d->st_lock);
    if (dvdnav_is_domain_vts(d->nav)) s->domain = DP_DOMAIN_TITLE;
    else if (dvdnav_is_domain_vmgm(d->nav)) s->domain = DP_DOMAIN_VMG_MENU;
    else if (dvdnav_is_domain_vtsm(d->nav)) s->domain = DP_DOMAIN_VTS_MENU;
    else if (dvdnav_is_domain_fp(d->nav)) s->domain = DP_DOMAIN_FIRST_PLAY;
    if (dvdnav_current_title_info(d->nav, &title, &part) == DVDNAV_STATUS_OK) {
        s->title = title;
        s->chapter = part;
        int32_t parts = 0;
        if (title > 0 && dvdnav_get_number_of_parts(d->nav, title, &parts) == DVDNAV_STATUS_OK) s->chapters = parts;
    }
    int64_t t = dvdnav_get_current_time(d->nav);
    if (t >= 0) s->position_ms = t / 90;
    int32_t angle = 1, angles = 1;
    if (dvdnav_get_angle_info(d->nav, &angle, &angles) == DVDNAV_STATUS_OK) {
        s->angle = angle;
        s->angles = angles;
    }
    s->wide = dvdnav_get_video_aspect(d->nav) == 3;
    update_streams_state(d);
    s->still = d->in_still;
    s->audio_code = d->audio_code;
    s->ended = d->ended;
    s->paused = d->paused;
    s->bytes_read = d->src->bytes_read;
    s->read_errors = d->src->errors;
    pthread_mutex_unlock(&d->st_lock);
}

/* 菜单按钮高亮：每个导航包和每次高亮事件后都重新取（同一个按钮在动态菜单里位置 / 颜色也可能变）。 */
static void update_highlight(dp_dvd *d) {
    pci_t *pci = dvdnav_get_current_nav_pci(d->nav);
    int32_t button = 0;
    dvdnav_get_current_highlight(d->nav, &button);
    int btn_ns = pci ? (pci->hli.hl_gi.btn_ns & 0x3F) : 0;
    bool menu = pci && (pci->hli.hl_gi.hli_ss & 3) != 0 && btn_ns > 0;
    dp_highlight hl;
    memset(&hl, 0, sizeof(hl));
    if (menu && button > 0 && button <= btn_ns) {
        dvdnav_highlight_area_t a;
        if (dvdnav_get_highlight_area(pci, button, 0, &a) == DVDNAV_STATUS_OK) {
            hl.display = true;
            hl.sx = a.sx;
            hl.sy = a.sy;
            hl.ex = a.ex;
            hl.ey = a.ey;
            hl.button = button;
            for (int i = 0; i < 4; i++) {
                hl.color[i] = (uint8_t)((a.palette >> (16 + 4 * i)) & 0x0F);
                hl.alpha[i] = (uint8_t)((a.palette >> (4 * i)) & 0x0F);
            }
        }
    }
    pthread_mutex_lock(&d->ov_lock);
    if (memcmp(&hl, &d->hl, sizeof(hl)) != 0) {
        d->hl = hl;
        ov_bump(d);
    }
    pthread_mutex_unlock(&d->ov_lock);
    pthread_mutex_lock(&d->st_lock);
    d->st.buttons = menu ? btn_ns : 0;
    d->st.button = menu ? button : 0;
    pthread_mutex_unlock(&d->st_lock);
    if (menu != d->menu_active) {
        d->menu_active = menu;
        dp_log(DP_LOG_INFO, TAG, menu ? "菜单：%d 个按钮，当前第 %d 个" : "离开菜单（%d/%d）", btn_ns, button);
        emit(d, DP_EV_MENU, menu ? 1 : 0, btn_ns, NULL);
    }
}

static void exec_command(dp_dvd *d, dp_cmd c) {
    pci_t *pci = dvdnav_get_current_nav_pci(d->nav);
    dvdnav_status_t r = DVDNAV_STATUS_OK;
    /* 在静止画面里直接跳（标题 / 章节 / 菜单 / 时间）：dvdnav 不会自己清掉静止状态（按按钮确定时会），先跳过静止 */
    bool jump = c.cmd == DP_CMD_MENU || c.cmd == DP_CMD_TITLE_PLAY || c.cmd == DP_CMD_PART_PLAY || c.cmd == DP_CMD_NEXT_CHAPTER ||
                c.cmd == DP_CMD_PREV_CHAPTER || c.cmd == DP_CMD_SEEK_MS || c.cmd == DP_CMD_GO_UP;
    if (jump && d->in_still) {
        dvdnav_still_skip(d->nav);
        d->in_still = false;
    }
    switch (c.cmd) {
        case DP_CMD_UP: r = dvdnav_upper_button_select(d->nav, pci); break;
        case DP_CMD_DOWN: r = dvdnav_lower_button_select(d->nav, pci); break;
        case DP_CMD_LEFT: r = dvdnav_left_button_select(d->nav, pci); break;
        case DP_CMD_RIGHT: r = dvdnav_right_button_select(d->nav, pci); break;
        case DP_CMD_ACTIVATE: r = dvdnav_button_activate(d->nav, pci); break;
        case DP_CMD_MOUSE_SELECT: r = dvdnav_mouse_select(d->nav, pci, c.a1, c.a2); break;
        case DP_CMD_MOUSE_ACTIVATE: r = dvdnav_mouse_activate(d->nav, pci, c.a1, c.a2); break;
        case DP_CMD_MENU: r = dvdnav_menu_call(d->nav, (DVDMenuID_t)c.a1); break;
        case DP_CMD_TITLE_PLAY: r = dvdnav_title_play(d->nav, c.a1); break;
        case DP_CMD_PART_PLAY: r = dvdnav_part_play(d->nav, c.a1, c.a2); break;
        case DP_CMD_NEXT_CHAPTER: r = dvdnav_next_pg_search(d->nav); break;
        case DP_CMD_PREV_CHAPTER: r = dvdnav_prev_pg_search(d->nav); break;
        case DP_CMD_SEEK_MS: r = dvdnav_time_search(d->nav, (uint64_t)c.a1 * 90); break;
        case DP_CMD_AUDIO:
            d->audio_user = true;
            r = dvdnav_set_active_stream(d->nav, (uint8_t)c.a1, DVD_AUDIO_STREAM);
            break;
        case DP_CMD_SPU:
            if (c.a1 < 0) {
                r = dvdnav_toggle_spu_stream(d->nav, 0);
            } else {
                r = dvdnav_set_active_stream(d->nav, (uint8_t)c.a1, DVD_SUBTITLE_STREAM);
                if (r == DVDNAV_STATUS_OK) r = dvdnav_toggle_spu_stream(d->nav, 1);
            }
            break;
        case DP_CMD_ANGLE: r = dvdnav_angle_change(d->nav, c.a1); break;
        case DP_CMD_PAUSE:
            d->paused = true;
            dp_av_pause(d->av, true);
            break;
        case DP_CMD_RESUME:
            d->paused = false;
            dp_av_pause(d->av, false);
            break;
        case DP_CMD_GO_UP: r = dvdnav_go_up(d->nav); break;
        case DP_CMD_STILL_SKIP: r = dvdnav_still_skip(d->nav); break;
        default: break;
    }
    if (r != DVDNAV_STATUS_OK) dp_log(DP_LOG_INFO, TAG, "命令 %d 没有执行：%s", c.cmd, dvdnav_err_to_string(d->nav));
    if (c.cmd != DP_CMD_PAUSE && c.cmd != DP_CMD_RESUME) {
        if (d->ended && r == DVDNAV_STATUS_OK) d->ended = false;
        update_highlight(d);
        update_position_state(d);
        emit(d, DP_EV_STATE, 0, 0, NULL);
    }
}

static void process_commands(dp_dvd *d) {
    for (;;) {
        pthread_mutex_lock(&d->cmd_lock);
        if (d->cmd_count == 0) {
            pthread_mutex_unlock(&d->cmd_lock);
            return;
        }
        dp_cmd c = d->cmds[d->cmd_head];
        d->cmd_head = (d->cmd_head + 1) % 64;
        d->cmd_count--;
        pthread_mutex_unlock(&d->cmd_lock);
        exec_command(d, c);
    }
}

/* 等解码器把排队的数据处理完（WAIT 事件、静止画面之前）。 */
static void wait_drained(dp_dvd *d, int max_ms) {
    dp_av_wait_drained(d->av, max_ms);
}

static void *nav_thread(void *arg) {
    dp_dvd *d = arg;
    uint8_t buf[DVD_VIDEO_LB_LEN];
    dp_thread_priority(-2, "导航");
    int64_t last_state_us = 0;
    /* 直接播放某个标题：dvdnav 的虚拟机在第一次取块时才启动（会从首播开始），所以等它启动后再跳 */
    bool start_pending = d->opt.start_title > 0;
    while (!d->quit) {
        process_commands(d);
        if (d->paused && !d->in_still) {
            dp_sleep_us(10000);
            continue;
        }
        if (d->ended) {
            dp_sleep_us(20000);
            continue;
        }
        int event = 0, len = 0;
        if (dvdnav_get_next_block(d->nav, buf, &event, &len) != DVDNAV_STATUS_OK) {
            const char *e = dvdnav_err_to_string(d->nav);
            dp_log(DP_LOG_ERROR, TAG, "读取出错：%s", e);
            emit(d, DP_EV_ERROR, 0, 0, e);
            d->ended = true;
            continue;
        }
        if (start_pending) {
            start_pending = false;
            if (dvdnav_title_play(d->nav, d->opt.start_title) == DVDNAV_STATUS_OK) {
                dp_log(DP_LOG_INFO, TAG, "直接播放标题 %d（跳过片头和菜单）", d->opt.start_title);
                flush_all(d, "直接播放标题");
                continue;
            }
            dp_log(DP_LOG_WARN, TAG, "标题 %d 不能直接播放（%s），从首播开始", d->opt.start_title, dvdnav_err_to_string(d->nav));
        }
        switch (event) {
            case DVDNAV_BLOCK_OK:
                if (d->in_still) {
                    d->in_still = false;
                    dp_av_set_still(d->av, false);
                }
                demux_pack(d, buf, len);
                break;
            case DVDNAV_NOP:
                break;
            case DVDNAV_STILL_FRAME: {
                dvdnav_still_event_t *ev = (dvdnav_still_event_t *)buf;
                if (!d->in_still) {
                    d->in_still = true;
                    d->still_len = ev->length;
                    d->still_since_us = dp_now_us();
                    dp_av_set_still(d->av, true);
                    dp_av_drain(d->av);
                    dp_log(DP_LOG_INFO, TAG, "静止画面：%s", ev->length == 0xFF ? "一直停住，等按键" : "定时");
                    update_position_state(d);
                    emit(d, DP_EV_STILL, ev->length, 0, NULL);
                }
                if (ev->length != 0xFF) {
                    int64_t wait = (int64_t)ev->length * 1000000;
                    if (!d->pf.realtime || dp_now_us() - d->still_since_us >= wait) {
                        dvdnav_still_skip(d->nav);
                        break;
                    }
                }
                dp_sleep_us(d->pf.realtime ? 20000 : 1000);
                break;
            }
            case DVDNAV_WAIT:
                wait_drained(d, d->pf.realtime ? 1500 : 200);
                dvdnav_wait_skip(d->nav);
                break;
            case DVDNAV_SPU_CLUT_CHANGE: {
                const uint32_t *clut = (const uint32_t *)buf;
                pthread_mutex_lock(&d->ov_lock);
                for (int i = 0; i < 16; i++) d->clut[i] = dp_ycrcb_to_rgba(clut[i]);
                ov_bump(d);
                pthread_mutex_unlock(&d->ov_lock);
                break;
            }
            case DVDNAV_SPU_STREAM_CHANGE: {
                dvdnav_spu_stream_change_event_t *ev = (dvdnav_spu_stream_change_event_t *)buf;
                int phys = ev->physical_wide;
                d->spu_phys = phys < 0 ? -1 : (phys & 0x1F);
                pthread_mutex_lock(&d->ov_lock);
                d->spu_show_all = phys >= 0 && !(phys & 0x80);
                ov_bump(d);
                pthread_mutex_unlock(&d->ov_lock);
                break;
            }
            case DVDNAV_AUDIO_STREAM_CHANGE: {
                dvdnav_audio_stream_change_event_t *ev = (dvdnav_audio_stream_change_event_t *)buf;
                /* 光盘没指定音轨（逻辑号还是初始的 15）且用户没手动选过：按偏好的语言选（libdvdnav 只记下偏好，不会自己选） */
                if (ev->logical >= 8 && !d->audio_user && d->opt.audio_lang[0] && dvdnav_is_domain_vts(d->nav)) {
                    int n = dvdnav_get_number_of_streams(d->nav, DVD_AUDIO_STREAM);
                    for (int i = 0; i < n && i < 8; i++) {
                        uint16_t lang = dvdnav_audio_stream_to_lang(d->nav, (uint8_t)i);
                        if ((char)(lang >> 8) == d->opt.audio_lang[0] && (char)(lang & 0xFF) == d->opt.audio_lang[1]) {
                            if (dvdnav_set_active_stream(d->nav, (uint8_t)i, DVD_AUDIO_STREAM) == DVDNAV_STATUS_OK) {
                                dp_log(DP_LOG_INFO, TAG, "按语言偏好（%s）选第 %d 条音轨", d->opt.audio_lang, i + 1);
                            }
                            break;
                        }
                    }
                }
                d->audio_logical = ev->logical;
                set_audio_code(d, audio_code_for(d, ev->physical, ev->logical), ev->physical, "音轨切换", true);
                break;
            }
            case DVDNAV_VTS_CHANGE:
            case DVDNAV_CELL_CHANGE:
                update_position_state(d);
                if (event == DVDNAV_VTS_CHANGE) {
                    /* 换了标题集 / 菜单和正片之间切换：音轨寄存器可能没变（不发音轨事件），但格式表换了，重新算一次
                     * （先更新域，状态里不会出现"还在正片、音轨已经是菜单的"） */
                    set_audio_code(d, audio_code_for(d, dvdnav_get_active_audio_stream(d->nav), d->audio_logical),
                                   dvdnav_get_active_audio_stream(d->nav), "换了标题集", false);
                }
                if (event == DVDNAV_CELL_CHANGE) {
                    dvdnav_cell_change_event_t *ev = (dvdnav_cell_change_event_t *)buf;
                    pthread_mutex_lock(&d->st_lock);
                    d->st.duration_ms = ev->pgc_length / 90;
                    pthread_mutex_unlock(&d->st_lock);
                }
                emit(d, DP_EV_STATE, 0, 0, NULL);
                break;
            case DVDNAV_NAV_PACKET: {
                check_audio_format(d);
                pci_t *pci = dvdnav_get_current_nav_pci(d->nav);
                if (pci) {
                    int64_t s = pci->pci_gi.vobu_s_ptm, e = pci->pci_gi.vobu_e_ptm;
                    if (d->have_vobu && s != d->prev_vobu_end) d->ts_offset += d->prev_vobu_end - s;
                    d->prev_vobu_end = e;
                    d->have_vobu = true;
                }
                update_highlight(d);
                int64_t now = dp_now_us();
                if (now - last_state_us > 250000) {
                    last_state_us = now;
                    update_position_state(d);
                }
                break;
            }
            case DVDNAV_HIGHLIGHT:
                update_highlight(d);
                break;
            case DVDNAV_HOP_CHANNEL:
                flush_all(d, "跳转");
                break;
            case DVDNAV_STOP:
                dp_log(DP_LOG_INFO, TAG, "播放结束");
                d->ended = true;
                update_position_state(d);
                emit(d, DP_EV_END, 0, 0, NULL);
                break;
            default:
                break;
        }
    }
    return NULL;
}

/* ---------------- 打开 / 关闭 ---------------- */

static void dvdnav_log(void *priv, dvdnav_logger_level_t level, const char *fmt, va_list ap) {
    (void)priv;
    char buf[512];
    vsnprintf(buf, sizeof(buf), fmt, ap);
    size_t n = strlen(buf);
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) buf[--n] = 0;
    dp_log(level == DVDNAV_LOGGER_LEVEL_ERROR ? DP_LOG_WARN : DP_LOG_DEBUG, "DVDNAV", "%s", buf);
}

static int stream_seek(void *s, uint64_t pos) { return dp_source_seek(s, pos); }
static int stream_read(void *s, void *buf, int len) { return dp_source_read(s, buf, len); }

dp_dvd *dp_dvd_open(dp_source *src, const dp_platform *pf, const dp_dvd_options *opt, char *err, int errlen) {
    dp_dvd *d = calloc(1, sizeof(*d));
    if (!d) return NULL;
    d->src = src;
    d->pf = *pf;
    d->opt = *opt;
    d->audio_phys = -1;
    d->audio_logical = 15;
    d->audio_code = -1;
    d->spu_phys = -1;
    d->spu_asm_stream = -1;
    dp_spu_asm_reset(&d->spu_asm);
    pthread_mutex_init(&d->cmd_lock, NULL);
    pthread_mutex_init(&d->ov_lock, NULL);
    pthread_mutex_init(&d->st_lock, NULL);
    dp_av_options ao = {
        .tag = TAG, .deinterlace = opt->deinterlace, .vq_bytes = VQ_BYTES, .aq_bytes = AQ_BYTES,
        .overlay = dvd_overlay, .owner = d, .src = src,
    };
    d->av = dp_av_new(pf, &ao);
    if (!d->av) {
        snprintf(err, (size_t)errlen, "内存不足");
        dp_dvd_close(d);
        return NULL;
    }
    for (int i = 0; i < 16; i++) d->clut[i] = 0xFF808080u;

    static dvdnav_stream_cb cb = {.pf_seek = stream_seek, .pf_read = stream_read, .pf_readv = NULL};
    static const dvdnav_logger_cb logcb = {.pf_log = dvdnav_log};
    if (dvdnav_open_stream2(&d->nav, src, &logcb, &cb) != DVDNAV_STATUS_OK || !d->nav) {
        snprintf(err, (size_t)errlen, "dvdnav 打不开这张盘（IFO 读不出或不是 DVD-Video）");
        dp_dvd_close(d);
        return NULL;
    }
    dvdnav_set_readahead_flag(d->nav, 1);
    dvdnav_set_PGC_positioning_flag(d->nav, 1);
    if (opt->region_mask) dvdnav_set_region_mask(d->nav, opt->region_mask);
    if (opt->menu_lang[0]) dvdnav_menu_language_select(d->nav, (char *)opt->menu_lang);
    if (opt->audio_lang[0]) dvdnav_audio_language_select(d->nav, (char *)opt->audio_lang);
    if (opt->spu_lang[0]) dvdnav_spu_language_select(d->nav, (char *)opt->spu_lang);
    int32_t titles = 0;
    dvdnav_get_number_of_titles(d->nav, &titles);
    d->st.titles = titles;
    const char *t = NULL;
    if (dvdnav_get_title_string(d->nav, &t) == DVDNAV_STATUS_OK && t) snprintf(d->st.volume, sizeof(d->st.volume), "%s", t);
    d->st.audio_current = -1;
    d->st.spu_current = -1;
    dp_log(DP_LOG_INFO, TAG, "打开 DVD：%d 个标题，卷标「%s」，%s", titles, d->st.volume,
           opt->start_title > 0 ? "直接播放标题" : "从光盘的首播（片头 / 菜单）开始");
    return d;
}

int dp_dvd_start(dp_dvd *d) {
    if (d->started) return 0;
    if (dp_av_start(d->av) != 0) return -1;
    if (pthread_create(&d->nav_th, NULL, nav_thread, d) != 0) return -1;
    d->started = true;
    return 0;
}

void dp_dvd_command(dp_dvd *d, int cmd, int a1, int a2) {
    pthread_mutex_lock(&d->cmd_lock);
    if (d->cmd_count < 64) {
        d->cmds[(d->cmd_head + d->cmd_count) % 64] = (dp_cmd){cmd, a1, a2};
        d->cmd_count++;
    }
    pthread_mutex_unlock(&d->cmd_lock);
}

void dp_dvd_get_state(dp_dvd *d, dp_dvd_state *out) {
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
    out->clock_pts = as.clock_pts;
    out->video_codec_id = as.video_codec;
    out->audio_codec_id = as.audio_codec;
    out->video_fps = as.video_fps;
}

void dp_dvd_refresh(dp_dvd *d) {
    dp_av_refresh(d->av);
}

void dp_dvd_close(dp_dvd *d) {
    if (!d) return;
    d->quit = true;
    /* 先让可能卡在队列上的导航线程返回，再停导航线程，最后停解码 / 显示线程 */
    if (d->av) dp_av_abort(d->av);
    if (d->started) pthread_join(d->nav_th, NULL);
    if (d->av) dp_av_free(d->av);
    if (d->nav) dvdnav_close(d->nav);
    free(d->ov_buf);
    for (int i = 0; i < d->spu_count; i++) free(d->spus[i].idx);
    dp_spu_asm_free(&d->spu_asm);
    pthread_mutex_destroy(&d->cmd_lock);
    pthread_mutex_destroy(&d->ov_lock);
    pthread_mutex_destroy(&d->st_lock);
    free(d);
}
