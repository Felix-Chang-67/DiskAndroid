#include "dp_dvd.h"

#include <dvdnav/dvdnav.h>
#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavutil/channel_layout.h>
#include <libavutil/log.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dp_spu.h"

#define TAG "DVD"
#define OUT_RATE 48000
#define MAX_SPUS 8
#define LATE_DROP (90 * 120)       /* 晚 120 ms 以上的帧丢掉 */
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

    pthread_t nav_th, video_th, audio_th;
    volatile bool quit;
    bool started;
    volatile int flush_gen;   /* 每次清空（跳转）加一：等待中的视频 / 音频据此放弃手头的数据 */

    dp_queue vq, aq;

    pthread_mutex_t cmd_lock;
    dp_cmd cmds[64];
    int cmd_head, cmd_count;

    /* ---- 只在导航线程里用 ---- */
    int audio_phys;          /* 当前物理音轨（0..7），-1 = 无 */
    int audio_code;          /* 要解码的流：AC-3 0x80+n、DTS 0x88+n、LPCM 0xA0+n、MPEG 0xC0+n；-1 = 无 */
    bool audio_user;         /* 用户手动选过音轨：不再按语言偏好自动选 */
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

    /* ---- 时钟 ---- */
    pthread_mutex_t clk_lock;
    int64_t written;                 /* 写给音频输出的帧数 */
    struct { int64_t frame, pts; } amap[64];
    int amap_n;
    int64_t last_audio_us;
    bool sys_valid;
    int64_t sys_pts, sys_us;
    bool clk_paused;
    int64_t pause_us;

    /* ---- 视频线程的唤醒（静止画面时高亮变了要重画）---- */
    pthread_mutex_t vlock;
    pthread_cond_t vcond;
    bool refresh;

    /* ---- 给界面的状态 ---- */
    pthread_mutex_t st_lock;
    dp_dvd_state st;
};

static void emit(dp_dvd *d, int ev, int a1, int a2, const char *text) {
    if (d->pf.on_event) d->pf.on_event(d->pf.opaque, ev, a1, a2, text);
}

/* ---------------- 时钟 ---------------- */

static void clock_reset(dp_dvd *d) {
    pthread_mutex_lock(&d->clk_lock);
    d->amap_n = 0;
    d->sys_valid = false;
    d->last_audio_us = 0;
    pthread_mutex_unlock(&d->clk_lock);
}

/* 音频线程：写入一段 PCM 之前记下"第 written 帧对应的 PTS"。 */
static void clock_audio_mark(dp_dvd *d, int64_t pts, int frames) {
    pthread_mutex_lock(&d->clk_lock);
    if (pts != DP_NOPTS) {
        if (d->amap_n == 64) {
            memmove(d->amap, d->amap + 1, sizeof(d->amap[0]) * 63);
            d->amap_n = 63;
        }
        d->amap[d->amap_n].frame = d->written;
        d->amap[d->amap_n].pts = pts;
        d->amap_n++;
    }
    d->written += frames;
    d->last_audio_us = dp_now_us();
    pthread_mutex_unlock(&d->clk_lock);
}

/* 当前该显示的 PTS：有音频时以音频为准（已经播放出去的那一帧），否则用系统时钟。 */
static int64_t clock_now(dp_dvd *d) {
    pthread_mutex_lock(&d->clk_lock);
    int64_t now = dp_now_us();
    int64_t r = DP_NOPTS;
    bool audio = d->amap_n > 0 && d->last_audio_us != 0 && now - d->last_audio_us < 1500000;
    if (audio && d->pf.audio_played) {
        int64_t played = d->pf.audio_played(d->pf.opaque);
        int i = d->amap_n - 1;
        while (i > 0 && d->amap[i].frame > played) i--;
        if (d->amap[i].frame <= played) r = d->amap[i].pts + (played - d->amap[i].frame) * 90000 / OUT_RATE;
    }
    if (r == DP_NOPTS && d->sys_valid) {
        int64_t t = d->clk_paused ? d->pause_us : now;
        r = d->sys_pts + (t - d->sys_us) * 9 / 100;
    }
    pthread_mutex_unlock(&d->clk_lock);
    return r;
}

static void clock_anchor(dp_dvd *d, int64_t pts) {
    pthread_mutex_lock(&d->clk_lock);
    d->sys_pts = pts;
    d->sys_us = dp_now_us();
    d->sys_valid = true;
    pthread_mutex_unlock(&d->clk_lock);
}

static void clock_pause(dp_dvd *d, bool pause) {
    pthread_mutex_lock(&d->clk_lock);
    int64_t now = dp_now_us();
    if (pause && !d->clk_paused) {
        d->pause_us = now;
    } else if (!pause && d->clk_paused) {
        d->sys_us += now - d->pause_us;
    }
    d->clk_paused = pause;
    pthread_mutex_unlock(&d->clk_lock);
}

static bool clock_paused(dp_dvd *d) {
    pthread_mutex_lock(&d->clk_lock);
    bool p = d->clk_paused;
    pthread_mutex_unlock(&d->clk_lock);
    return p;
}

/* ---------------- 叠加层 ---------------- */

static void ov_bump(dp_dvd *d) {
    d->ov_version++;
    pthread_mutex_lock(&d->vlock);
    d->refresh = true;
    pthread_cond_broadcast(&d->vcond);
    pthread_mutex_unlock(&d->vlock);
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

/* ---------------- 视频线程 ---------------- */

typedef struct {
    const AVCodec *codec;
    AVCodecContext *ctx;
    AVCodecParserContext *parser;
    AVPacket *pkt;
    AVFrame *frame, *filt, *last;
    AVFilterGraph *graph;
    AVFilterContext *bsrc, *bsink;
    int g_w, g_h, g_fmt;
    AVRational g_sar;
    uint32_t *overlay;
    int ov_w, ov_h;
    uint64_t ov_key;
    bool ov_has;
    bool need_first;
    int64_t last_pts;
} vstate;

static void v_reset_codec(vstate *v) {
    if (v->ctx) avcodec_flush_buffers(v->ctx);
    if (v->parser) av_parser_close(v->parser);
    v->parser = av_parser_init(AV_CODEC_ID_MPEG2VIDEO);
    if (v->graph) avfilter_graph_free(&v->graph);
    v->bsrc = v->bsink = NULL;
}

static int v_build_graph(vstate *v, const AVFrame *f) {
    if (v->graph) avfilter_graph_free(&v->graph);
    v->graph = avfilter_graph_alloc();
    if (!v->graph) return -1;
    char args[256];
    AVRational sar = f->sample_aspect_ratio.num ? f->sample_aspect_ratio : (AVRational){1, 1};
    snprintf(args, sizeof(args), "video_size=%dx%d:pix_fmt=%d:time_base=1/90000:pixel_aspect=%d/%d",
             f->width, f->height, f->format, sar.num, sar.den);
    int r = avfilter_graph_create_filter(&v->bsrc, avfilter_get_by_name("buffer"), "in", args, NULL, v->graph);
    if (r >= 0) r = avfilter_graph_create_filter(&v->bsink, avfilter_get_by_name("buffersink"), "out", NULL, NULL, v->graph);
    AVFilterContext *bw = NULL;
    if (r >= 0) r = avfilter_graph_create_filter(&bw, avfilter_get_by_name("bwdif"), "deint", "mode=send_field:parity=auto:deint=interlaced", NULL, v->graph);
    AVFilterContext *fmt = NULL;
    if (r >= 0) r = avfilter_graph_create_filter(&fmt, avfilter_get_by_name("format"), "fmt", "pix_fmts=yuv420p", NULL, v->graph);
    if (r >= 0) r = avfilter_link(v->bsrc, 0, bw, 0);
    if (r >= 0) r = avfilter_link(bw, 0, fmt, 0);
    if (r >= 0) r = avfilter_link(fmt, 0, v->bsink, 0);
    if (r >= 0) r = avfilter_graph_config(v->graph, NULL);
    if (r < 0) {
        dp_log(DP_LOG_WARN, TAG, "去隔行滤镜建不起来（%d），不去隔行", r);
        avfilter_graph_free(&v->graph);
        v->bsrc = v->bsink = NULL;
        return -1;
    }
    v->g_w = f->width;
    v->g_h = f->height;
    v->g_fmt = f->format;
    v->g_sar = sar;
    return 0;
}

/* 按时钟等到该显示的时候；返回 false 表示太晚了要丢掉，或者要退出 / 清空了。 */
static bool v_wait(dp_dvd *d, vstate *v, int64_t pts) {
    if (!d->pf.realtime) return true;
    int gen = d->flush_gen;
    for (;;) {
        if (d->quit || d->flush_gen != gen) return false;
        if (clock_paused(d)) {
            dp_sleep_us(10000);
            continue;
        }
        int64_t now = clock_now(d);
        if (now == DP_NOPTS || pts == DP_NOPTS) {
            if (pts != DP_NOPTS) clock_anchor(d, pts);
            return true;
        }
        int64_t diff = pts - now;
        if (diff <= 0) {
            if (-diff > LATE_DROP && !v->need_first && !d->in_still) return false;
            /* 跳转后、或者音频时钟远远落后（例如刚开始）时，以这一帧重新对齐系统时钟 */
            if (-diff > 90 * 3000) clock_anchor(d, pts);
            return true;
        }
        if (diff > 90 * 2000) {
            /* 差得太远（时间轴不连续），别傻等：重新对齐 */
            clock_anchor(d, pts);
            return true;
        }
        int64_t us = diff * 100 / 9;
        dp_sleep_us(us > 10000 ? 10000 : us);
    }
}

static void v_present(dp_dvd *d, vstate *v, AVFrame *f, bool force) {
    int64_t pts = f->pts;
    if (!force && !v_wait(d, v, pts)) {
        pthread_mutex_lock(&d->st_lock);
        d->st.frames_dropped++;
        pthread_mutex_unlock(&d->st_lock);
        return;
    }
    if (f->width != v->ov_w || f->height != v->ov_h) {
        free(v->overlay);
        v->overlay = malloc((size_t)f->width * (size_t)f->height * 4);
        v->ov_w = f->width;
        v->ov_h = f->height;
        v->ov_key = (uint64_t)-1;
    }
    bool changed = false;
    if (v->overlay) v->ov_has = ov_compose(d, pts, v->overlay, f->width, f->height, &v->ov_key, &changed);
    dp_frame df = {
        .width = f->width, .height = f->height,
        .sar_num = f->sample_aspect_ratio.num ? f->sample_aspect_ratio.num : 1,
        .sar_den = f->sample_aspect_ratio.num ? f->sample_aspect_ratio.den : 1,
        .pts = pts,
        .plane = {f->data[0], f->data[1], f->data[2]},
        .stride = {f->linesize[0], f->linesize[1], f->linesize[2]},
    };
    if (d->pf.video_present) d->pf.video_present(d->pf.opaque, &df, v->ov_has ? v->overlay : NULL, changed);
    if (v->last != f) {
        av_frame_unref(v->last);
        av_frame_ref(v->last, f);
    }
    pthread_mutex_lock(&d->st_lock);
    d->st.frames_shown++;
    d->st.video_w = f->width;
    d->st.video_h = f->height;
    d->st.clock_pts = pts;
    pthread_mutex_unlock(&d->st_lock);
    if (v->need_first) {
        v->need_first = false;
        emit(d, DP_EV_FIRST_FRAME, f->width, f->height, NULL);
    }
}

/* 解码出来的一帧：要去隔行就过滤镜（每场一帧，时间基是 1/180000），然后显示。 */
static void v_output(dp_dvd *d, vstate *v, AVFrame *f, bool drain) {
    if (f->pts == AV_NOPTS_VALUE) f->pts = f->best_effort_timestamp;
    if (f->pts == AV_NOPTS_VALUE && v->last_pts != DP_NOPTS) f->pts = v->last_pts + 3003;
    if (f->pts != AV_NOPTS_VALUE) v->last_pts = f->pts;
    bool interlaced = (f->flags & AV_FRAME_FLAG_INTERLACED) != 0;
    if (d->opt.deinterlace && interlaced && f->format == AV_PIX_FMT_YUV420P) {
        if (!v->graph || f->width != v->g_w || f->height != v->g_h || f->format != v->g_fmt) v_build_graph(v, f);
        if (v->graph && av_buffersrc_add_frame_flags(v->bsrc, f, AV_BUFFERSRC_FLAG_KEEP_REF) >= 0) {
            AVRational tb = av_buffersink_get_time_base(v->bsink);
            while (av_buffersink_get_frame(v->bsink, v->filt) >= 0) {
                if (v->filt->pts != AV_NOPTS_VALUE) v->filt->pts = av_rescale_q(v->filt->pts, tb, (AVRational){1, 90000});
                v_present(d, v, v->filt, drain);
                av_frame_unref(v->filt);
            }
            return;
        }
    }
    v_present(d, v, f, drain);
}

static void v_decode(dp_dvd *d, vstate *v, const uint8_t *data, int size, int64_t pts, bool drain) {
    if (drain) {
        avcodec_send_packet(v->ctx, NULL);
    } else {
        int64_t ipts = pts == DP_NOPTS ? AV_NOPTS_VALUE : pts;
        while (size > 0) {
            uint8_t *out = NULL;
            int out_size = 0;
            int used = av_parser_parse2(v->parser, v->ctx, &out, &out_size, data, size, ipts, ipts, 0);
            if (used < 0) break;
            data += used;
            size -= used;
            ipts = AV_NOPTS_VALUE;
            if (out_size <= 0) continue;
            v->pkt->data = out;
            v->pkt->size = out_size;
            v->pkt->pts = v->parser->pts;
            v->pkt->dts = v->parser->dts;
            if (avcodec_send_packet(v->ctx, v->pkt) < 0) continue;
            while (avcodec_receive_frame(v->ctx, v->frame) >= 0) {
                v_output(d, v, v->frame, false);
                av_frame_unref(v->frame);
            }
        }
        return;
    }
    while (avcodec_receive_frame(v->ctx, v->frame) >= 0) {
        v_output(d, v, v->frame, true);
        av_frame_unref(v->frame);
    }
    if (v->graph) {
        if (av_buffersrc_add_frame(v->bsrc, NULL) < 0) dp_log(DP_LOG_DEBUG, TAG, "滤镜排空失败");
        AVRational tb = av_buffersink_get_time_base(v->bsink);
        while (av_buffersink_get_frame(v->bsink, v->filt) >= 0) {
            if (v->filt->pts != AV_NOPTS_VALUE) v->filt->pts = av_rescale_q(v->filt->pts, tb, (AVRational){1, 90000});
            v_present(d, v, v->filt, true);
            av_frame_unref(v->filt);
        }
    }
    /* 排空之后解码器要重置才能接着解 */
    v_reset_codec(v);
}

static void *video_thread(void *arg) {
    dp_dvd *d = arg;
    vstate v = {0};
    v.codec = avcodec_find_decoder(AV_CODEC_ID_MPEG2VIDEO);
    v.ctx = avcodec_alloc_context3(v.codec);
    v.ctx->pkt_timebase = (AVRational){1, 90000};
    v.ctx->thread_count = 2;
    if (avcodec_open2(v.ctx, v.codec, NULL) < 0) {
        emit(d, DP_EV_ERROR, 0, 0, "MPEG-2 解码器打不开");
        return NULL;
    }
    v.pkt = av_packet_alloc();
    v.frame = av_frame_alloc();
    v.filt = av_frame_alloc();
    v.last = av_frame_alloc();
    v.parser = av_parser_init(AV_CODEC_ID_MPEG2VIDEO);
    v.need_first = true;
    v.last_pts = DP_NOPTS;
    v.ov_key = (uint64_t)-1;
    while (!d->quit) {
        dp_packet *p = dp_queue_get(&d->vq, 20);
        if (!p) {
            if (d->pf.video_idle) d->pf.video_idle(d->pf.opaque);
            /* 没有新帧（静止画面、暂停）：叠加层变了就把最后一帧重画一次 */
            pthread_mutex_lock(&d->vlock);
            bool refresh = d->refresh;
            d->refresh = false;
            pthread_mutex_unlock(&d->vlock);
            if (refresh && v.last->data[0]) v_present(d, &v, v.last, true);
            continue;
        }
        switch (p->kind) {
            case DP_PKT_FLUSH:
                v_reset_codec(&v);
                v.last_pts = DP_NOPTS;
                v.need_first = true;
                break;
            case DP_PKT_DRAIN:
                v_decode(d, &v, NULL, 0, DP_NOPTS, true);
                break;
            case DP_PKT_DATA:
                v_decode(d, &v, p->data, p->size, p->pts, false);
                break;
            default:
                break;
        }
        free(p);
    }
    if (d->pf.video_exit) d->pf.video_exit(d->pf.opaque);
    if (v.graph) avfilter_graph_free(&v.graph);
    av_parser_close(v.parser);
    av_frame_free(&v.frame);
    av_frame_free(&v.filt);
    av_frame_free(&v.last);
    av_packet_free(&v.pkt);
    avcodec_free_context(&v.ctx);
    free(v.overlay);
    return NULL;
}

/* ---------------- 音频线程 ---------------- */

typedef struct {
    int stream;   /* 当前解码器对应的流号 */
    AVCodecContext *ctx;
    AVCodecParserContext *parser;
    AVPacket *pkt;
    AVFrame *frame;
    SwrContext *swr;
    AVChannelLayout swr_in;
    int swr_rate, swr_fmt;
    int16_t *out;
    int out_cap;
    bool opened;
} astate;

static enum AVCodecID audio_codec(int stream) {
    if (stream >= 0x80 && stream <= 0x87) return AV_CODEC_ID_AC3;
    if (stream >= 0x88 && stream <= 0x8F) return AV_CODEC_ID_DTS;
    if (stream >= 0xA0 && stream <= 0xA7) return AV_CODEC_ID_PCM_DVD;
    if (stream >= 0xC0 && stream <= 0xDF) return AV_CODEC_ID_MP2;
    return AV_CODEC_ID_NONE;
}

static void a_close(astate *a) {
    if (a->parser) av_parser_close(a->parser);
    a->parser = NULL;
    avcodec_free_context(&a->ctx);
    swr_free(&a->swr);
    av_channel_layout_uninit(&a->swr_in);
    a->stream = -1;
}

static bool a_open(dp_dvd *d, astate *a, int stream) {
    a_close(a);
    enum AVCodecID id = audio_codec(stream);
    const AVCodec *c = avcodec_find_decoder(id);
    if (!c) return false;
    a->ctx = avcodec_alloc_context3(c);
    a->ctx->pkt_timebase = (AVRational){1, 90000};
    if (id == AV_CODEC_ID_MP2) a->ctx->codec_id = AV_CODEC_ID_MP2;
    if (avcodec_open2(a->ctx, c, NULL) < 0) {
        avcodec_free_context(&a->ctx);
        return false;
    }
    if (id != AV_CODEC_ID_PCM_DVD) a->parser = av_parser_init(id == AV_CODEC_ID_MP2 ? AV_CODEC_ID_MP2 : id);
    a->stream = stream;
    dp_log(DP_LOG_INFO, TAG, "音频解码器：%s（流 %02Xh）", c->name, stream);
    return true;
}

static void a_play(dp_dvd *d, astate *a, AVFrame *f) {
    if (!a->opened) {
        if (d->pf.audio_open && d->pf.audio_open(d->pf.opaque, OUT_RATE, 2) == 0) a->opened = true;
        else return;
    }
    if (!a->swr || av_channel_layout_compare(&a->swr_in, &f->ch_layout) || a->swr_rate != f->sample_rate || a->swr_fmt != f->format) {
        swr_free(&a->swr);
        AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
        if (swr_alloc_set_opts2(&a->swr, &stereo, AV_SAMPLE_FMT_S16, OUT_RATE, &f->ch_layout, f->format, f->sample_rate, 0, NULL) < 0 ||
            swr_init(a->swr) < 0) {
            swr_free(&a->swr);
            return;
        }
        av_channel_layout_uninit(&a->swr_in);
        av_channel_layout_copy(&a->swr_in, &f->ch_layout);
        a->swr_rate = f->sample_rate;
        a->swr_fmt = f->format;
    }
    int max_out = swr_get_out_samples(a->swr, f->nb_samples) + 32;
    if (max_out > a->out_cap) {
        free(a->out);
        a->out = malloc((size_t)max_out * 4);
        a->out_cap = a->out ? max_out : 0;
        if (!a->out) return;
    }
    uint8_t *outp[1] = {(uint8_t *)a->out};
    int n = swr_convert(a->swr, outp, max_out, (const uint8_t **)f->extended_data, f->nb_samples);
    if (n <= 0) return;
    int64_t pts = f->pts != AV_NOPTS_VALUE ? f->pts : DP_NOPTS;
    clock_audio_mark(d, pts, n);
    if (d->pf.audio_write) {
        int done = 0;
        int gen = d->flush_gen;
        while (done < n && !d->quit && d->flush_gen == gen) {
            int w = d->pf.audio_write(d->pf.opaque, a->out + done * 2, n - done);
            if (w < 0) break;
            /* 暂停中写不进去：等着，不能丢（丢了音频时钟就和画面错开了） */
            if (w == 0) {
                dp_sleep_us(5000);
                continue;
            }
            done += w;
        }
    }
    pthread_mutex_lock(&d->st_lock);
    d->st.audio_frames += (uint64_t)n;
    pthread_mutex_unlock(&d->st_lock);
}

static void a_decode(dp_dvd *d, astate *a, dp_packet *p) {
    if (p->stream != a->stream && !a_open(d, a, p->stream)) return;
    const uint8_t *data = p->data;
    int size = p->size;
    int64_t ipts = p->pts == DP_NOPTS ? AV_NOPTS_VALUE : p->pts;
    if (!a->parser) {
        a->pkt->data = (uint8_t *)data;
        a->pkt->size = size;
        a->pkt->pts = ipts;
        if (avcodec_send_packet(a->ctx, a->pkt) >= 0) {
            while (avcodec_receive_frame(a->ctx, a->frame) >= 0) {
                a_play(d, a, a->frame);
                av_frame_unref(a->frame);
            }
        }
        return;
    }
    while (size > 0) {
        uint8_t *out = NULL;
        int out_size = 0;
        int used = av_parser_parse2(a->parser, a->ctx, &out, &out_size, data, size, ipts, ipts, 0);
        if (used < 0) break;
        data += used;
        size -= used;
        ipts = AV_NOPTS_VALUE;
        if (out_size <= 0) continue;
        a->pkt->data = out;
        a->pkt->size = out_size;
        a->pkt->pts = a->parser->pts;
        if (avcodec_send_packet(a->ctx, a->pkt) < 0) continue;
        while (avcodec_receive_frame(a->ctx, a->frame) >= 0) {
            a_play(d, a, a->frame);
            av_frame_unref(a->frame);
        }
    }
}

static void *audio_thread(void *arg) {
    dp_dvd *d = arg;
    astate a = {0};
    a.stream = -1;
    a.pkt = av_packet_alloc();
    a.frame = av_frame_alloc();
    while (!d->quit) {
        dp_packet *p = dp_queue_get(&d->aq, 50);
        if (!p) continue;
        if (p->kind == DP_PKT_FLUSH) {
            if (a.ctx) avcodec_flush_buffers(a.ctx);
            if (a.parser) {
                av_parser_close(a.parser);
                a.parser = av_parser_init(audio_codec(a.stream) == AV_CODEC_ID_MP2 ? AV_CODEC_ID_MP2 : audio_codec(a.stream));
            }
            if (a.opened && d->pf.audio_flush) d->pf.audio_flush(d->pf.opaque);
            pthread_mutex_lock(&d->clk_lock);
            d->amap_n = 0;
            d->written = 0;
            pthread_mutex_unlock(&d->clk_lock);
        } else if (p->kind == DP_PKT_DATA) {
            a_decode(d, &a, p);
        }
        free(p);
    }
    if (a.opened && d->pf.audio_close) d->pf.audio_close(d->pf.opaque);
    a_close(&a);
    av_frame_free(&a.frame);
    av_packet_free(&a.pkt);
    free(a.out);
    return NULL;
}

/* ---------------- 导航线程 ---------------- */

static int64_t parse_pts(const uint8_t *p) {
    return ((int64_t)((p[0] >> 1) & 7) << 30) | ((int64_t)p[1] << 22) | ((int64_t)(p[2] >> 1) << 15) | ((int64_t)p[3] << 7) | (p[4] >> 1);
}

static void push(dp_dvd *d, dp_queue *q, int stream, int64_t pts, const uint8_t *data, int len) {
    if (len <= 0) return;
    dp_packet *p = dp_packet_new(DP_PKT_DATA, stream, pts, data, len);
    if (p) dp_queue_put(q, p, true);
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

static int audio_code_for(dp_dvd *d, int physical, int logical) {
    if (physical < 0) return -1;
    uint16_t fmt = logical >= 0 ? dvdnav_audio_stream_format(d->nav, (uint8_t)logical) : DVD_AUDIO_FORMAT_AC3;
    switch (fmt) {
        case DVD_AUDIO_FORMAT_LPCM: return 0xA0 + (physical & 7);
        case DVD_AUDIO_FORMAT_DTS: return 0x88 + (physical & 7);
        case DVD_AUDIO_FORMAT_MPEG:
        case DVD_AUDIO_FORMAT_MPEG2_EXT: return 0xC0 + (physical & 7);
        default: return 0x80 + (physical & 7);
    }
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
            push(d, &d->vq, id, t, b + q, end - q);
        } else if (id >= 0xC0 && id <= 0xDF) {
            if (audio_selected(d, id)) push(d, &d->aq, id, t, b + q, end - q);
        } else if (id == 0xBD) {
            int sub = b[q];
            if (sub >= 0x20 && sub <= 0x3F) {
                handle_spu(d, sub & 0x1F, b + q + 1, end - q - 1, t);
            } else if (sub >= 0x80 && sub <= 0xAF) {
                /* 子流号 + 帧数 + 首个访问单元指针，之后是数据（LPCM 还带 3 字节头，留给解码器） */
                if (audio_selected(d, sub) && q + 4 < end) push(d, &d->aq, sub, t, b + q + 4, end - q - 4);
            }
        }
    }
}

static void flush_all(dp_dvd *d, const char *why) {
    d->flush_gen++;
    dp_queue_clear(&d->vq);
    dp_queue_clear(&d->aq);
    dp_queue_put(&d->vq, dp_packet_new(DP_PKT_FLUSH, 0, DP_NOPTS, NULL, 0), false);
    dp_queue_put(&d->aq, dp_packet_new(DP_PKT_FLUSH, 0, DP_NOPTS, NULL, 0), false);
    dp_spu_asm_reset(&d->spu_asm);
    ov_clear_spus(d);
    clock_reset(d);
    d->have_vobu = false;
    d->in_still = false;
    dp_log(DP_LOG_DEBUG, TAG, "清空解码器（%s）", why);
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
    /* dvdnav 的 get_active_*_stream 返回物理流号；界面要的是逻辑序号（音轨 1、2…），反查一下 */
    int8_t aphys = dvdnav_get_active_audio_stream(d->nav);
    s->audio_current = -1;
    for (int i = 0; aphys >= 0 && i < s->audio_count; i++) {
        if (dvdnav_get_audio_logical_stream(d->nav, (uint8_t)i) == aphys) {
            s->audio_current = i;
            break;
        }
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
            clock_pause(d, true);
            if (d->pf.audio_pause) d->pf.audio_pause(d->pf.opaque, true);
            break;
        case DP_CMD_RESUME:
            d->paused = false;
            clock_pause(d, false);
            if (d->pf.audio_pause) d->pf.audio_pause(d->pf.opaque, false);
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
    int64_t until = dp_now_us() + (int64_t)max_ms * 1000;
    while (!d->quit && dp_now_us() < until) {
        if (dp_queue_count(&d->vq) == 0 && dp_queue_count(&d->aq) == 0) break;
        dp_sleep_us(5000);
    }
}

static void *nav_thread(void *arg) {
    dp_dvd *d = arg;
    uint8_t buf[DVD_VIDEO_LB_LEN];
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
                d->in_still = false;
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
                    dp_queue_put(&d->vq, dp_packet_new(DP_PKT_DRAIN, 0, DP_NOPTS, NULL, 0), true);
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
                int code = audio_code_for(d, ev->physical, ev->logical);
                if (code != d->audio_code) {
                    d->audio_phys = ev->physical;
                    d->audio_code = code;
                    dp_log(DP_LOG_INFO, TAG, "音轨：逻辑 %d，物理 %d，流 %02Xh", ev->logical, ev->physical, code < 0 ? 0 : code);
                    pthread_mutex_lock(&d->st_lock);
                    d->st.audio_code = code;
                    pthread_mutex_unlock(&d->st_lock);
                    emit(d, DP_EV_STATE, 0, 0, NULL);
                    dp_queue_clear(&d->aq);
                    dp_queue_put(&d->aq, dp_packet_new(DP_PKT_FLUSH, 0, DP_NOPTS, NULL, 0), false);
                }
                break;
            }
            case DVDNAV_VTS_CHANGE:
            case DVDNAV_CELL_CHANGE:
                update_position_state(d);
                if (event == DVDNAV_CELL_CHANGE) {
                    dvdnav_cell_change_event_t *ev = (dvdnav_cell_change_event_t *)buf;
                    pthread_mutex_lock(&d->st_lock);
                    d->st.duration_ms = ev->pgc_length / 90;
                    pthread_mutex_unlock(&d->st_lock);
                }
                emit(d, DP_EV_STATE, 0, 0, NULL);
                break;
            case DVDNAV_NAV_PACKET: {
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

/* FFmpeg 的错误进日志（坏扇区补零后解码器会连续报错，限流：每 10 秒最多 20 条） */
static void av_log_cb(void *avcl, int level, const char *fmt, va_list ap) {
    (void)avcl;
    if (level > AV_LOG_ERROR) return;
    static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
    static int64_t window_us;
    static int count, dropped;
    pthread_mutex_lock(&lock);
    int64_t now = dp_now_us();
    if (now - window_us > 10000000) {
        if (dropped) dp_log(DP_LOG_WARN, "FFMPEG", "（另有 %d 条解码错误没有记录）", dropped);
        window_us = now;
        count = dropped = 0;
    }
    bool emit_it = count++ < 20;
    if (!emit_it) dropped++;
    pthread_mutex_unlock(&lock);
    if (!emit_it) return;
    char buf[512];
    vsnprintf(buf, sizeof(buf), fmt, ap);
    size_t n = strlen(buf);
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) buf[--n] = 0;
    dp_log(DP_LOG_WARN, "FFMPEG", "%s", buf);
}

static int stream_seek(void *s, uint64_t pos) { return dp_source_seek(s, pos); }
static int stream_read(void *s, void *buf, int len) { return dp_source_read(s, buf, len); }

dp_dvd *dp_dvd_open(dp_source *src, const dp_platform *pf, const dp_dvd_options *opt, char *err, int errlen) {
    dp_dvd *d = calloc(1, sizeof(*d));
    if (!d) return NULL;
    av_log_set_callback(av_log_cb);
    d->src = src;
    d->pf = *pf;
    d->opt = *opt;
    d->audio_phys = -1;
    d->audio_code = -1;
    d->spu_phys = -1;
    d->spu_asm_stream = -1;
    dp_spu_asm_reset(&d->spu_asm);
    pthread_mutex_init(&d->cmd_lock, NULL);
    pthread_mutex_init(&d->ov_lock, NULL);
    pthread_mutex_init(&d->clk_lock, NULL);
    pthread_mutex_init(&d->vlock, NULL);
    pthread_cond_init(&d->vcond, NULL);
    pthread_mutex_init(&d->st_lock, NULL);
    dp_queue_init(&d->vq, VQ_BYTES);
    dp_queue_init(&d->aq, AQ_BYTES);
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
    d->started = true;
    if (pthread_create(&d->video_th, NULL, video_thread, d) != 0) return -1;
    if (pthread_create(&d->audio_th, NULL, audio_thread, d) != 0) return -1;
    if (pthread_create(&d->nav_th, NULL, nav_thread, d) != 0) return -1;
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
    out->clock_pts = clock_now(d);
}

void dp_dvd_refresh(dp_dvd *d) {
    pthread_mutex_lock(&d->vlock);
    d->refresh = true;
    pthread_cond_broadcast(&d->vcond);
    pthread_mutex_unlock(&d->vlock);
}

void dp_dvd_close(dp_dvd *d) {
    if (!d) return;
    d->quit = true;
    dp_queue_set_abort(&d->vq, true);
    dp_queue_set_abort(&d->aq, true);
    if (d->started) {
        pthread_join(d->nav_th, NULL);
        pthread_join(d->video_th, NULL);
        pthread_join(d->audio_th, NULL);
    }
    if (d->nav) dvdnav_close(d->nav);
    dp_queue_destroy(&d->vq);
    dp_queue_destroy(&d->aq);
    for (int i = 0; i < d->spu_count; i++) free(d->spus[i].idx);
    dp_spu_asm_free(&d->spu_asm);
    pthread_mutex_destroy(&d->cmd_lock);
    pthread_mutex_destroy(&d->ov_lock);
    pthread_mutex_destroy(&d->clk_lock);
    pthread_mutex_destroy(&d->vlock);
    pthread_cond_destroy(&d->vcond);
    pthread_mutex_destroy(&d->st_lock);
    free(d);
}
