/*
 * DVD 和蓝光共用的解码 / 显示 / 音画同步：视频线程（FFmpeg 解码、bwdif 去隔行、按时钟显示、叠加层）、
 * 音频线程（FFmpeg 解码 → 48 kHz 立体声 → 平台输出）、以音频为主的时钟、每 10 秒的播放统计。
 * 导航（dvdnav / libbluray）在各自的模块里，只往这里送数据包和控制命令。
 */
#pragma once

#include "dp_core.h"

typedef struct dp_av dp_av;

/*
 * 叠加层（字幕 / 菜单）：视频线程每显示一帧调用一次，问 pts 时刻要叠加什么。
 * 返回 true 表示有叠加内容：*rgba 指向 *w × *h 的 RGBA（回调方持有，到下次调用前有效），叠加层铺满整个画面
 * （尺寸可以和视频帧不同，例如蓝光 1920×1080 的菜单配 720p 视频）；*changed 表示和上次给的不同。
 */
typedef bool (*dp_overlay_fn)(void *owner, int64_t pts, int frame_w, int frame_h, const uint32_t **rgba, int *w, int *h, bool *changed);

typedef struct {
    const char *tag;          /* 日志标签 */
    bool deinterlace;
    size_t vq_bytes, aq_bytes;
    dp_overlay_fn overlay;
    void *owner;
    dp_source *src;           /* 统计读盘耗时用，可以为 NULL */
} dp_av_options;

typedef struct {
    uint64_t frames_shown, frames_dropped, audio_frames;
    int video_w, video_h;
    int64_t clock_pts;
} dp_av_stats;

dp_av *dp_av_new(const dp_platform *pf, const dp_av_options *opt);
int dp_av_start(dp_av *av);
/* 让阻塞在队列上的导航线程返回（关闭第一步）。 */
void dp_av_abort(dp_av *av);
/* 停止视频 / 音频线程并释放。 */
void dp_av_free(dp_av *av);

/* ---- 导航线程调用 ---- */
/* 送一段视频 / 音频数据（codec 是 FFmpeg 的 AVCodecID；stream 用来区分同一编码的不同音轨）。队列满时等待；中止时返回 false。 */
bool dp_av_video(dp_av *av, int codec, int64_t pts, int64_t dts, const uint8_t *data, int len);
bool dp_av_audio(dp_av *av, int stream, int codec, int64_t pts, const uint8_t *data, int len);
/* 跳转：丢掉排队的数据，解码器和时钟重来。 */
void dp_av_flush(dp_av *av, const char *why);
/* 换音轨：只清音频。 */
void dp_av_flush_audio(dp_av *av);
/* 静止画面：把解码器里剩下的帧都显示出来。 */
void dp_av_drain(dp_av *av);
/* 等队列里的数据处理完（最多 max_ms）。 */
void dp_av_wait_drained(dp_av *av, int max_ms);
/* 队列里的数据都处理完了（不阻塞）。 */
bool dp_av_queues_empty(dp_av *av);
/* 静止画面期间晚到的帧不丢。 */
void dp_av_set_still(dp_av *av, bool still);

/* ---- 任何线程 ---- */
void dp_av_pause(dp_av *av, bool pause);
bool dp_av_paused(dp_av *av);
/* 叠加层变了 / 换了窗口：把最后一帧再画一次。 */
void dp_av_refresh(dp_av *av);
/* 当前播放到的 PTS（90 kHz），不知道时为 DP_NOPTS。 */
int64_t dp_av_clock(dp_av *av);
void dp_av_get_stats(dp_av *av, dp_av_stats *out);
