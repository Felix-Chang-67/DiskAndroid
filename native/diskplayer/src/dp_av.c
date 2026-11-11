#include "dp_av.h"

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

#define OUT_RATE 48000
#define LATE_DROP (90 * 120)       /* 晚 120 ms 以上的帧丢掉 */
/* 提前这么多交出去，同时告诉平台这一帧应该在什么时刻显示，由系统对准屏幕刷新 */
#define PRESENT_LEAD (90 * 12)

struct dp_av {
    dp_platform pf;
    dp_av_options opt;
    const char *tag;

    pthread_t video_th, audio_th;
    bool started;
    volatile bool quit;
    volatile int flush_gen;   /* 每次清空（跳转）加一：等待中的视频 / 音频据此放弃手头的数据 */
    volatile bool in_still;

    dp_queue vq, aq;

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
    bool clk_from_audio;

    /* ---- 视频线程的唤醒（静止画面时叠加层变了要重画）---- */
    pthread_mutex_t vlock;
    bool refresh;

    /* ---- 统计 ---- */
    pthread_mutex_t st_lock;
    dp_av_stats st;
};

/* ---------------- 时钟 ---------------- */

static void clock_reset(dp_av *d) {
    pthread_mutex_lock(&d->clk_lock);
    d->amap_n = 0;
    d->sys_valid = false;
    d->last_audio_us = 0;
    pthread_mutex_unlock(&d->clk_lock);
}

/* 音频线程：写入一段 PCM 之前记下"第 written 帧对应的 PTS"。 */
static void clock_audio_mark(dp_av *d, int64_t pts, int frames) {
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
static int64_t clock_now(dp_av *d) {
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
    d->clk_from_audio = r != DP_NOPTS;
    if (r == DP_NOPTS && d->sys_valid) {
        int64_t t = d->clk_paused ? d->pause_us : now;
        r = d->sys_pts + (t - d->sys_us) * 9 / 100;
    }
    pthread_mutex_unlock(&d->clk_lock);
    return r;
}

static void clock_anchor(dp_av *d, int64_t pts) {
    pthread_mutex_lock(&d->clk_lock);
    d->sys_pts = pts;
    d->sys_us = dp_now_us();
    d->sys_valid = true;
    pthread_mutex_unlock(&d->clk_lock);
}

static void clock_pause(dp_av *d, bool pause) {
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

static bool clock_paused(dp_av *d) {
    pthread_mutex_lock(&d->clk_lock);
    bool p = d->clk_paused;
    pthread_mutex_unlock(&d->clk_lock);
    return p;
}

/* ---------------- 视频线程 ---------------- */

typedef struct {
    int codec_id;                /* 当前解码器（AVCodecID），0 = 还没有 */
    AVCodecContext *ctx;
    AVCodecParserContext *parser;
    AVPacket *pkt;
    AVFrame *frame, *filt, *last;
    AVFilterGraph *graph;        /* 去隔行（bwdif） */
    AVFilterContext *bsrc, *bsink;
    int g_w, g_h, g_fmt;
    AVRational g_sar;
    bool deint_active;           /* 这个流出现过隔行帧：之后所有帧都过滤镜（逐行帧原样通过），保证帧的顺序 */
    AVFilterGraph *cgraph;       /* 转成 8 位 YUV420P（HEVC Main10 等） */
    AVFilterContext *csrc, *csink;
    int c_w, c_h, c_fmt;
    bool need_first;
    int64_t last_pts;
    double out_fps;              /* 这一帧的输出帧率（给平台挑屏幕刷新率） */
    /* 每 10 秒一次的播放统计 */
    int64_t st_start_us, st_last_present_us, st_max_gap_us, st_max_late_us, st_decode_max_us;
    int st_shown, st_dropped, st_late;
    int64_t st_xruns0;
    uint64_t st_read_us0, st_upcalls0;
} vstate;

static const char *codec_name(int id) {
    const AVCodecDescriptor *desc = avcodec_descriptor_get((enum AVCodecID)id);
    return desc ? desc->name : "?";
}

static void v_free_graphs(vstate *v) {
    if (v->graph) avfilter_graph_free(&v->graph);
    v->bsrc = v->bsink = NULL;
    if (v->cgraph) avfilter_graph_free(&v->cgraph);
    v->csrc = v->csink = NULL;
}

static void v_close_codec(vstate *v) {
    if (v->parser) av_parser_close(v->parser);
    v->parser = NULL;
    avcodec_free_context(&v->ctx);
    v_free_graphs(v);
    v->deint_active = false;
    v->codec_id = 0;
}

static bool v_open_codec(dp_av *d, vstate *v, int codec_id) {
    v_close_codec(v);
    const AVCodec *c = avcodec_find_decoder((enum AVCodecID)codec_id);
    if (!c) {
        dp_log(DP_LOG_ERROR, d->tag, "没有 %s 视频解码器", codec_name(codec_id));
        return false;
    }
    v->ctx = avcodec_alloc_context3(c);
    v->ctx->pkt_timebase = (AVRational){1, 90000};
    /* MPEG-2（DVD）2 线程足够；H.264 / HEVC / VC-1（蓝光 1080）用 4 线程 */
    v->ctx->thread_count = codec_id == AV_CODEC_ID_MPEG2VIDEO || codec_id == AV_CODEC_ID_MPEG1VIDEO ? 2 : 4;
    if (avcodec_open2(v->ctx, c, NULL) < 0) {
        dp_log(DP_LOG_ERROR, d->tag, "%s 视频解码器打不开", c->name);
        avcodec_free_context(&v->ctx);
        return false;
    }
    v->parser = av_parser_init((enum AVCodecID)codec_id);
    v->codec_id = codec_id;
    pthread_mutex_lock(&d->st_lock);
    d->st.video_codec = codec_id;
    pthread_mutex_unlock(&d->st_lock);
    dp_log(DP_LOG_INFO, d->tag, "视频解码器：%s（%d 线程）", c->name, v->ctx->thread_count);
    return true;
}

static void v_reset_codec(vstate *v) {
    if (v->ctx) avcodec_flush_buffers(v->ctx);
    if (v->parser) {
        av_parser_close(v->parser);
        v->parser = av_parser_init((enum AVCodecID)v->codec_id);
    }
    v_free_graphs(v);
}

static int v_build_graph(dp_av *d, vstate *v, const AVFrame *f) {
    if (v->graph) avfilter_graph_free(&v->graph);
    v->graph = avfilter_graph_alloc();
    if (!v->graph) return -1;
    /* 1080i 的去隔行很吃 CPU：滤镜也用多线程 */
    v->graph->nb_threads = f->height > 576 ? 4 : 1;
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
        dp_log(DP_LOG_WARN, d->tag, "去隔行滤镜建不起来（%d），不去隔行", r);
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

static void v_present(dp_av *d, vstate *v, AVFrame *f, bool force);

/*
 * 视频线程空着（队列没数据、暂停、等时间到）时：让平台处理窗口更换（界面的 Surface 销毁 / 重建），
 * 界面要求重画（回到播放界面、叠加层变了）时把上一帧再画一次。
 */
static void v_idle(dp_av *d, vstate *v) {
    if (d->pf.video_idle) d->pf.video_idle(d->pf.opaque);
    pthread_mutex_lock(&d->vlock);
    bool refresh = d->refresh;
    d->refresh = false;
    pthread_mutex_unlock(&d->vlock);
    if (refresh && v->last->data[0]) v_present(d, v, v->last, true);
}

/* 按时钟等到该显示的时候；返回 false 表示太晚了要丢掉，或者要退出 / 清空了。 */
static bool v_wait(dp_av *d, vstate *v, int64_t pts, int64_t *present_ns) {
    *present_ns = 0;
    if (!d->pf.realtime) return true;
    int gen = d->flush_gen;
    for (;;) {
        if (d->quit || d->flush_gen != gen) return false;
        if (clock_paused(d)) {
            v_idle(d, v);
            dp_sleep_us(10000);
            continue;
        }
        int64_t now = clock_now(d);
        if (now == DP_NOPTS || pts == DP_NOPTS) {
            if (pts != DP_NOPTS) clock_anchor(d, pts);
            return true;
        }
        int64_t diff = pts - now;
        if (diff <= PRESENT_LEAD) {
            if (-diff > LATE_DROP && !v->need_first && !d->in_still) return false;
            /* 跳转后、或者音频时钟远远落后（例如刚开始）时，以这一帧重新对齐系统时钟 */
            if (-diff > 90 * 3000) clock_anchor(d, pts);
            if (diff > 0) *present_ns = dp_now_us() * 1000 + diff * 100000 / 9;
            if (-diff > 90 * 20) {
                v->st_late++;
                if (-diff * 100 / 9 > v->st_max_late_us) v->st_max_late_us = -diff * 100 / 9;
            }
            return true;
        }
        if (diff > 90 * 2000) {
            /* 差得太远（时间轴不连续），别傻等：重新对齐 */
            clock_anchor(d, pts);
            return true;
        }
        int64_t us = diff * 100 / 9;
        if (us > 10000) {
            v_idle(d, v);
            us = 10000;
        }
        dp_sleep_us(us);
    }
}

/* 每 10 秒把播放统计写进日志（查卡顿用）。 */
static void v_stats(dp_av *d, vstate *v, int64_t now_us) {
    dp_source *src = d->opt.src;
    if (v->st_start_us == 0) {
        v->st_start_us = now_us;
        v->st_xruns0 = d->pf.audio_xruns ? d->pf.audio_xruns(d->pf.opaque) : 0;
        v->st_read_us0 = src ? src->read_us : 0;
        v->st_upcalls0 = src ? src->upcalls : 0;
        return;
    }
    int64_t span = now_us - v->st_start_us;
    if (span < 10000000) return;
    int64_t xr = d->pf.audio_xruns ? d->pf.audio_xruns(d->pf.opaque) : 0;
    uint64_t read_us = src ? src->read_us : 0, upcalls = src ? src->upcalls : 0, read_max = src ? src->read_max_us : 0;
    dp_log(DP_LOG_INFO, d->tag,
           "播放统计（%.0f 秒）：显示 %d 帧（%.1f 帧/秒，输出 %.2f），丢 %d 帧，晚到 20 ms 以上 %d 帧（最晚 %lld ms），相邻两帧最长隔 %lld ms，"
           "解码一帧最长 %lld ms；视频队列 %zu KB，音频队列 %zu KB；时钟：%s；音频欠载 %lld 次；读盘 %llu 次共 %llu ms（最长一次 %llu ms）",
           span / 1e6, v->st_shown, v->st_shown * 1e6 / span, v->out_fps, v->st_dropped, v->st_late, (long long)(v->st_max_late_us / 1000),
           (long long)(v->st_max_gap_us / 1000), (long long)(v->st_decode_max_us / 1000), dp_queue_bytes(&d->vq) / 1024, dp_queue_bytes(&d->aq) / 1024,
           d->clk_from_audio ? "音频" : "系统", (long long)(xr - v->st_xruns0), (unsigned long long)(upcalls - v->st_upcalls0),
           (unsigned long long)((read_us - v->st_read_us0) / 1000), (unsigned long long)(read_max / 1000));
    if (src) src->read_max_us = 0;
    v->st_start_us = now_us;
    v->st_xruns0 = xr;
    v->st_read_us0 = read_us;
    v->st_upcalls0 = upcalls;
    v->st_shown = v->st_dropped = v->st_late = 0;
    v->st_max_gap_us = v->st_max_late_us = v->st_decode_max_us = 0;
}

static void v_present(dp_av *d, vstate *v, AVFrame *f, bool force) {
    int64_t pts = f->pts;
    int64_t present_ns = 0;
    if (!force && !v_wait(d, v, pts, &present_ns)) {
        pthread_mutex_lock(&d->st_lock);
        d->st.frames_dropped++;
        pthread_mutex_unlock(&d->st_lock);
        v->st_dropped++;
        return;
    }
    const uint32_t *ov = NULL;
    int ov_w = 0, ov_h = 0;
    bool changed = false;
    if (d->opt.overlay && !d->opt.overlay(d->opt.owner, pts, f->width, f->height, &ov, &ov_w, &ov_h, &changed)) ov = NULL;
    dp_frame df = {
        .width = f->width, .height = f->height,
        .sar_num = f->sample_aspect_ratio.num ? f->sample_aspect_ratio.num : 1,
        .sar_den = f->sample_aspect_ratio.num ? f->sample_aspect_ratio.den : 1,
        .pts = pts,
        .plane = {f->data[0], f->data[1], f->data[2]},
        .stride = {f->linesize[0], f->linesize[1], f->linesize[2]},
        .fps = v->out_fps,
        .present_ns = present_ns,
    };
    if (d->pf.video_present) d->pf.video_present(d->pf.opaque, &df, ov, ov_w, ov_h, changed);
    if (!force && d->pf.realtime) {
        int64_t now_us = dp_now_us();
        if (v->st_last_present_us && now_us - v->st_last_present_us > v->st_max_gap_us && !d->in_still && !clock_paused(d)) {
            v->st_max_gap_us = now_us - v->st_last_present_us;
        }
        v->st_last_present_us = now_us;
        v->st_shown++;
        v_stats(d, v, now_us);
    }
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
        if (d->pf.on_event) d->pf.on_event(d->pf.opaque, DP_EV_FIRST_FRAME, f->width, f->height, NULL);
    }
}

/* 一帧的时长（90 kHz）：解码器给的，或者按帧率算。 */
static int64_t v_frame_duration(vstate *v, const AVFrame *f) {
    if (f->duration > 0) return f->duration;
    AVRational fr = v->ctx->framerate;
    if (fr.num > 0 && fr.den > 0) return av_rescale_q(1, av_inv_q(fr), (AVRational){1, 90000});
    return 3003;
}

/* 转成 8 位 YUV420P 的滤镜（画面这一层只认 YUV420P）。 */
static bool v_convert(dp_av *d, vstate *v, AVFrame *f, AVFrame *out) {
    if (!v->cgraph || f->width != v->c_w || f->height != v->c_h || f->format != v->c_fmt) {
        if (v->cgraph) avfilter_graph_free(&v->cgraph);
        v->cgraph = avfilter_graph_alloc();
        if (!v->cgraph) return false;
        char args[256];
        AVRational sar = f->sample_aspect_ratio.num ? f->sample_aspect_ratio : (AVRational){1, 1};
        snprintf(args, sizeof(args), "video_size=%dx%d:pix_fmt=%d:time_base=1/90000:pixel_aspect=%d/%d", f->width, f->height, f->format,
                 sar.num, sar.den);
        AVFilterContext *fmt = NULL;
        int r = avfilter_graph_create_filter(&v->csrc, avfilter_get_by_name("buffer"), "in", args, NULL, v->cgraph);
        if (r >= 0) r = avfilter_graph_create_filter(&v->csink, avfilter_get_by_name("buffersink"), "out", NULL, NULL, v->cgraph);
        if (r >= 0) r = avfilter_graph_create_filter(&fmt, avfilter_get_by_name("format"), "fmt", "pix_fmts=yuv420p", NULL, v->cgraph);
        if (r >= 0) r = avfilter_link(v->csrc, 0, fmt, 0);
        if (r >= 0) r = avfilter_link(fmt, 0, v->csink, 0);
        if (r >= 0) r = avfilter_graph_config(v->cgraph, NULL);
        if (r < 0) {
            dp_log(DP_LOG_WARN, d->tag, "像素格式 %d 转换不了（%d）", f->format, r);
            avfilter_graph_free(&v->cgraph);
            return false;
        }
        v->c_w = f->width;
        v->c_h = f->height;
        v->c_fmt = f->format;
    }
    if (av_buffersrc_add_frame_flags(v->csrc, f, AV_BUFFERSRC_FLAG_KEEP_REF) < 0) return false;
    return av_buffersink_get_frame(v->csink, out) >= 0;
}

/* 一帧 YUV420P：要去隔行就过滤镜（每场一帧，时间基换回 90 kHz），然后显示。 */
static void v_output420(dp_av *d, vstate *v, AVFrame *f, bool drain, double fps) {
    bool interlaced = (f->flags & AV_FRAME_FLAG_INTERLACED) != 0;
    v->out_fps = v->deint_active ? fps * 2 : fps;
    if (d->opt.deinterlace && (interlaced || v->deint_active)) {
        if (!v->graph || f->width != v->g_w || f->height != v->g_h || f->format != v->g_fmt) v_build_graph(d, v, f);
        if (v->graph && av_buffersrc_add_frame_flags(v->bsrc, f, AV_BUFFERSRC_FLAG_KEEP_REF) >= 0) {
            v->deint_active = true;
            v->out_fps = fps * 2;
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

/* 解码出来的一帧：补时间戳，必要时转格式，再去隔行 / 显示。 */
static void v_output(dp_av *d, vstate *v, AVFrame *f, bool drain) {
    if (f->pts == AV_NOPTS_VALUE) f->pts = f->best_effort_timestamp;
    if (f->pts == AV_NOPTS_VALUE && v->last_pts != DP_NOPTS) f->pts = v->last_pts + v_frame_duration(v, f);
    if (f->pts != AV_NOPTS_VALUE) v->last_pts = f->pts;
    double fps = v->ctx->framerate.num > 0 && v->ctx->framerate.den > 0 ? av_q2d(v->ctx->framerate) : 0;
    if (f->format == AV_PIX_FMT_YUV420P || f->format == AV_PIX_FMT_YUVJ420P) {
        v_output420(d, v, f, drain, fps);
        return;
    }
    AVFrame *c = av_frame_alloc();
    if (!c) return;
    if (v_convert(d, v, f, c)) {
        c->flags = f->flags;
        c->pts = f->pts;
        v_output420(d, v, c, drain, fps);
    }
    av_frame_free(&c);
}

static void v_decode(dp_av *d, vstate *v, const uint8_t *data, int size, int64_t pts, int64_t dts, bool drain) {
    if (!v->ctx) return;
    if (drain) {
        avcodec_send_packet(v->ctx, NULL);
        while (avcodec_receive_frame(v->ctx, v->frame) >= 0) {
            v_output(d, v, v->frame, true);
            av_frame_unref(v->frame);
        }
        if (v->graph) {
            if (av_buffersrc_add_frame(v->bsrc, NULL) < 0) dp_log(DP_LOG_DEBUG, d->tag, "滤镜排空失败");
            AVRational tb = av_buffersink_get_time_base(v->bsink);
            while (av_buffersink_get_frame(v->bsink, v->filt) >= 0) {
                if (v->filt->pts != AV_NOPTS_VALUE) v->filt->pts = av_rescale_q(v->filt->pts, tb, (AVRational){1, 90000});
                v_present(d, v, v->filt, true);
                av_frame_unref(v->filt);
            }
        }
        /* 排空之后解码器要重置才能接着解 */
        v_reset_codec(v);
        return;
    }
    int64_t ipts = pts == DP_NOPTS ? AV_NOPTS_VALUE : pts;
    int64_t idts = dts == DP_NOPTS ? AV_NOPTS_VALUE : dts;
    while (size > 0) {
        uint8_t *out = NULL;
        int out_size = 0;
        int used;
        if (v->parser) {
            used = av_parser_parse2(v->parser, v->ctx, &out, &out_size, data, size, ipts, idts, 0);
            if (used < 0) break;
        } else {
            out = (uint8_t *)data;
            out_size = used = size;
        }
        data += used;
        size -= used;
        if (out_size <= 0) {
            ipts = idts = AV_NOPTS_VALUE;
            continue;
        }
        v->pkt->data = out;
        v->pkt->size = out_size;
        v->pkt->pts = v->parser ? v->parser->pts : ipts;
        v->pkt->dts = v->parser ? v->parser->dts : idts;
        ipts = idts = AV_NOPTS_VALUE;
        /* 统计解码耗时（不含等待显示） */
        int64_t t0 = dp_now_us();
        if (avcodec_send_packet(v->ctx, v->pkt) < 0) continue;
        for (;;) {
            int r = avcodec_receive_frame(v->ctx, v->frame);
            int64_t took = dp_now_us() - t0;
            if (took > v->st_decode_max_us) v->st_decode_max_us = took;
            if (r < 0) break;
            v_output(d, v, v->frame, false);
            av_frame_unref(v->frame);
            t0 = dp_now_us();
        }
    }
}

static void *video_thread(void *arg) {
    dp_av *d = arg;
    vstate v;
    memset(&v, 0, sizeof(v));
    dp_thread_priority(-4, "视频");
    v.pkt = av_packet_alloc();
    v.frame = av_frame_alloc();
    v.filt = av_frame_alloc();
    v.last = av_frame_alloc();
    v.need_first = true;
    v.last_pts = DP_NOPTS;
    while (!d->quit) {
        dp_packet *p = dp_queue_get(&d->vq, 20);
        if (!p) {
            /* 没有新帧（静止画面、暂停）：窗口变了、叠加层变了就把最后一帧重画一次 */
            v_idle(d, &v);
            continue;
        }
        switch (p->kind) {
            case DP_PKT_FLUSH:
                v_reset_codec(&v);
                v.last_pts = DP_NOPTS;
                v.need_first = true;
                break;
            case DP_PKT_DRAIN:
                v_decode(d, &v, NULL, 0, DP_NOPTS, DP_NOPTS, true);
                break;
            case DP_PKT_DATA:
                if (p->codec != v.codec_id && !v_open_codec(d, &v, p->codec)) break;
                v_decode(d, &v, p->data, p->size, p->pts, p->dts, false);
                break;
            default:
                break;
        }
        free(p);
    }
    if (d->pf.video_exit) d->pf.video_exit(d->pf.opaque);
    v_close_codec(&v);
    av_frame_free(&v.frame);
    av_frame_free(&v.filt);
    av_frame_free(&v.last);
    av_packet_free(&v.pkt);
    return NULL;
}

/* ---------------- 音频线程 ---------------- */

typedef struct {
    int stream, codec_id;   /* 当前解码器对应的流和编码 */
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

static void a_close(astate *a) {
    if (a->parser) av_parser_close(a->parser);
    a->parser = NULL;
    avcodec_free_context(&a->ctx);
    swr_free(&a->swr);
    av_channel_layout_uninit(&a->swr_in);
    a->stream = -1;
    a->codec_id = 0;
}

/* 有解析器的格式先过解析器（AC-3 / DTS / TrueHD / MPEG）；LPCM 每个包本身就是完整的。 */
static bool a_needs_parser(int id) {
    return id != AV_CODEC_ID_PCM_DVD && id != AV_CODEC_ID_PCM_BLURAY;
}

static bool a_open(dp_av *d, astate *a, int stream, int codec_id) {
    a_close(a);
    const AVCodec *c = avcodec_find_decoder((enum AVCodecID)codec_id);
    if (!c) {
        dp_log(DP_LOG_WARN, d->tag, "没有 %s 音频解码器", codec_name(codec_id));
        return false;
    }
    a->ctx = avcodec_alloc_context3(c);
    a->ctx->pkt_timebase = (AVRational){1, 90000};
    /* 多声道一律缩混成立体声（TrueHD / DTS-HD 可以直接要立体声，省得解全部声道） */
    AVChannelLayout stereo = AV_CHANNEL_LAYOUT_STEREO;
    av_opt_set_chlayout(a->ctx, "downmix", &stereo, AV_OPT_SEARCH_CHILDREN);
    if (avcodec_open2(a->ctx, c, NULL) < 0) {
        avcodec_free_context(&a->ctx);
        return false;
    }
    if (a_needs_parser(codec_id)) a->parser = av_parser_init((enum AVCodecID)codec_id);
    a->stream = stream;
    a->codec_id = codec_id;
    pthread_mutex_lock(&d->st_lock);
    d->st.audio_codec = codec_id;
    pthread_mutex_unlock(&d->st_lock);
    dp_log(DP_LOG_INFO, d->tag, "音频解码器：%s（流 %Xh）", c->name, stream);
    return true;
}

static void a_play(dp_av *d, astate *a, AVFrame *f) {
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

static void a_decode(dp_av *d, astate *a, dp_packet *p) {
    if ((p->stream != a->stream || p->codec != a->codec_id) && !a_open(d, a, p->stream, p->codec)) return;
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
    dp_av *d = arg;
    astate a;
    memset(&a, 0, sizeof(a));
    a.stream = -1;
    dp_thread_priority(-16, "音频");
    a.pkt = av_packet_alloc();
    a.frame = av_frame_alloc();
    while (!d->quit) {
        dp_packet *p = dp_queue_get(&d->aq, 50);
        if (!p) continue;
        if (p->kind == DP_PKT_FLUSH) {
            if (a.ctx) avcodec_flush_buffers(a.ctx);
            if (a.parser) {
                av_parser_close(a.parser);
                a.parser = av_parser_init((enum AVCodecID)a.codec_id);
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

/* ---------------- FFmpeg 日志 ---------------- */

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

/* ---------------- 接口 ---------------- */

dp_av *dp_av_new(const dp_platform *pf, const dp_av_options *opt) {
    dp_av *d = calloc(1, sizeof(*d));
    if (!d) return NULL;
    av_log_set_callback(av_log_cb);
    d->pf = *pf;
    d->opt = *opt;
    d->tag = opt->tag ? opt->tag : "AV";
    pthread_mutex_init(&d->clk_lock, NULL);
    pthread_mutex_init(&d->vlock, NULL);
    pthread_mutex_init(&d->st_lock, NULL);
    dp_queue_init(&d->vq, opt->vq_bytes ? opt->vq_bytes : (6 << 20));
    dp_queue_init(&d->aq, opt->aq_bytes ? opt->aq_bytes : (1 << 20));
    return d;
}

int dp_av_start(dp_av *d) {
    if (d->started) return 0;
    d->started = true;
    if (pthread_create(&d->video_th, NULL, video_thread, d) != 0) return -1;
    if (pthread_create(&d->audio_th, NULL, audio_thread, d) != 0) return -1;
    return 0;
}

void dp_av_abort(dp_av *d) {
    d->quit = true;
    dp_queue_set_abort(&d->vq, true);
    dp_queue_set_abort(&d->aq, true);
}

void dp_av_free(dp_av *d) {
    if (!d) return;
    dp_av_abort(d);
    if (d->started) {
        pthread_join(d->video_th, NULL);
        pthread_join(d->audio_th, NULL);
    }
    dp_queue_destroy(&d->vq);
    dp_queue_destroy(&d->aq);
    pthread_mutex_destroy(&d->clk_lock);
    pthread_mutex_destroy(&d->vlock);
    pthread_mutex_destroy(&d->st_lock);
    free(d);
}

bool dp_av_video(dp_av *d, int codec, int64_t pts, int64_t dts, const uint8_t *data, int len) {
    if (len <= 0) return true;
    dp_packet *p = dp_packet_new(DP_PKT_DATA, 0, pts, data, len);
    if (!p) return false;
    p->codec = codec;
    p->dts = dts;
    return dp_queue_put(&d->vq, p, true);
}

bool dp_av_audio(dp_av *d, int stream, int codec, int64_t pts, const uint8_t *data, int len) {
    if (len <= 0) return true;
    dp_packet *p = dp_packet_new(DP_PKT_DATA, stream, pts, data, len);
    if (!p) return false;
    p->codec = codec;
    return dp_queue_put(&d->aq, p, true);
}

void dp_av_flush(dp_av *d, const char *why) {
    d->flush_gen++;
    dp_queue_clear(&d->vq);
    dp_queue_clear(&d->aq);
    dp_queue_put(&d->vq, dp_packet_new(DP_PKT_FLUSH, 0, DP_NOPTS, NULL, 0), false);
    dp_queue_put(&d->aq, dp_packet_new(DP_PKT_FLUSH, 0, DP_NOPTS, NULL, 0), false);
    clock_reset(d);
    d->in_still = false;
    dp_log(DP_LOG_DEBUG, d->tag, "清空解码器（%s）", why);
}

void dp_av_flush_audio(dp_av *d) {
    dp_queue_clear(&d->aq);
    dp_queue_put(&d->aq, dp_packet_new(DP_PKT_FLUSH, 0, DP_NOPTS, NULL, 0), false);
}

void dp_av_drain(dp_av *d) {
    dp_queue_put(&d->vq, dp_packet_new(DP_PKT_DRAIN, 0, DP_NOPTS, NULL, 0), true);
}

void dp_av_wait_drained(dp_av *d, int max_ms) {
    int64_t until = dp_now_us() + (int64_t)max_ms * 1000;
    while (!d->quit && dp_now_us() < until) {
        if (dp_queue_count(&d->vq) == 0 && dp_queue_count(&d->aq) == 0) break;
        dp_sleep_us(5000);
    }
}

bool dp_av_queues_empty(dp_av *d) {
    return dp_queue_count(&d->vq) == 0 && dp_queue_count(&d->aq) == 0;
}

void dp_av_set_still(dp_av *d, bool still) {
    d->in_still = still;
}

void dp_av_pause(dp_av *d, bool pause) {
    clock_pause(d, pause);
    if (d->pf.audio_pause) d->pf.audio_pause(d->pf.opaque, pause);
}

bool dp_av_paused(dp_av *d) {
    return clock_paused(d);
}

void dp_av_refresh(dp_av *d) {
    pthread_mutex_lock(&d->vlock);
    d->refresh = true;
    pthread_mutex_unlock(&d->vlock);
}

int64_t dp_av_clock(dp_av *d) {
    return clock_now(d);
}

void dp_av_get_stats(dp_av *d, dp_av_stats *out) {
    pthread_mutex_lock(&d->st_lock);
    *out = d->st;
    pthread_mutex_unlock(&d->st_lock);
    out->clock_pts = clock_now(d);
}
