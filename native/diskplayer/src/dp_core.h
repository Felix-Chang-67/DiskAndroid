/*
 * DiskAndroid 原生播放器：公共部分（日志、时钟、包队列、带预读的读盘源、平台接口）。
 * 这些代码与 Android 无关，可以在电脑上编译测试（test/dp_host_test.c）。
 */
#pragma once

#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define DP_NOPTS INT64_MIN
#define DP_SECTOR 2048

/* ---------------- 日志 ---------------- */

typedef enum { DP_LOG_DEBUG = 0, DP_LOG_INFO, DP_LOG_WARN, DP_LOG_ERROR } dp_log_level;
typedef void (*dp_log_fn)(void *opaque, dp_log_level level, const char *tag, const char *msg);

void dp_log_set(dp_log_fn fn, void *opaque);
void dp_log(dp_log_level level, const char *tag, const char *fmt, ...) __attribute__((format(printf, 3, 4)));

int64_t dp_now_us(void);
void dp_sleep_us(int64_t us);

/* ---------------- 包队列 ---------------- */

enum {
    DP_PKT_DATA = 0,
    DP_PKT_FLUSH,   /* 跳转：清空解码器 */
    DP_PKT_DRAIN,   /* 静止画面：把解码器里剩下的帧都输出 */
    DP_PKT_EOS,
};

typedef struct dp_packet {
    struct dp_packet *next;
    int kind;
    int stream;     /* 私有流 1 的子流号（0x80 AC-3、0xA0 LPCM…）或 MPEG 流号（0xE0 视频、0xC0 音频） */
    int64_t pts;    /* 90 kHz，已加上不连续点的偏移；DP_NOPTS 表示没有 */
    int size;
    uint8_t data[];
} dp_packet;

dp_packet *dp_packet_new(int kind, int stream, int64_t pts, const uint8_t *data, int size);

typedef struct {
    pthread_mutex_t lock;
    pthread_cond_t cond;
    dp_packet *head, *tail;
    int count;
    size_t bytes, max_bytes;
    bool abort;
} dp_queue;

void dp_queue_init(dp_queue *q, size_t max_bytes);
void dp_queue_destroy(dp_queue *q);
/* 队列满时阻塞（block）或直接放入；队列被中止时返回 false（包已释放）。 */
bool dp_queue_put(dp_queue *q, dp_packet *p, bool block);
/* timeout_ms < 0 一直等；队列中止或超时返回 NULL。 */
dp_packet *dp_queue_get(dp_queue *q, int timeout_ms);
void dp_queue_clear(dp_queue *q);
void dp_queue_set_abort(dp_queue *q, bool abort);
int dp_queue_count(dp_queue *q);
size_t dp_queue_bytes(dp_queue *q);

/* ---------------- 读盘源 ---------------- */

/* 读 [offset, offset+len)，offset 和 len 都是 2048 的整数倍；返回读到的字节数，出错返回 < 0。 */
typedef int (*dp_read_fn)(void *opaque, uint64_t offset, uint8_t *buf, int len);

/*
 * 顺序预读缓存：libdvdread 一次只要几个扇区，光驱每条命令的开销远大于传输本身，
 * 所以每次向光驱要 [prefetch] 字节，后面的读从缓存里拿。读不出的扇区补零（不中断播放）并记日志。
 */
typedef struct {
    dp_read_fn read;
    void *opaque;
    uint64_t size;
    uint64_t pos;
    uint8_t *cache;
    int cache_cap;
    uint64_t cache_off;
    int cache_len;
    pthread_mutex_t lock;
    /* 统计 */
    uint64_t upcalls, bytes_read, errors;
} dp_source;

dp_source *dp_source_new(dp_read_fn read, void *opaque, uint64_t size, int prefetch_bytes);
void dp_source_free(dp_source *s);
/* libdvdread 的流回调：成功返回 0 / 读到的字节数。 */
int dp_source_seek(void *s, uint64_t pos);
int dp_source_read(void *s, void *buf, int len);
/* 按扇区读（libbluray 的 read_blocks 回调用）：返回读到的扇区数。 */
int dp_source_read_blocks(void *s, void *buf, int lba, int num_blocks);

/* ---------------- 平台接口（Android：GLES + AAudio；测试：空实现） ---------------- */

typedef struct {
    int width, height;          /* 解码后的尺寸 */
    int sar_num, sar_den;       /* 像素宽高比 */
    int64_t pts;                /* 90 kHz，连续时间轴 */
    const uint8_t *plane[3];    /* YUV420P */
    int stride[3];
} dp_frame;

typedef struct dp_platform {
    void *opaque;
    /* 视频线程调用：显示一帧；overlay 是与帧同尺寸的 RGBA（预乘之前的普通 RGBA），没有字幕 / 菜单时为 NULL。 */
    void (*video_present)(void *opaque, const dp_frame *f, const uint32_t *overlay, bool overlay_changed);
    /* 视频线程空闲时（约每 20 ms）调用：处理输出窗口的更换 / 销毁；线程退出前调用 video_exit 释放显示资源。可以为 NULL。 */
    void (*video_idle)(void *opaque);
    void (*video_exit)(void *opaque);
    /* 音频：打开（采样率、声道数），阻塞写入（返回写入的帧数，0 = 暂时写不进（暂停中），< 0 出错），已播放出去的帧数，暂停，清空，关闭。 */
    int (*audio_open)(void *opaque, int sample_rate, int channels);
    int (*audio_write)(void *opaque, const int16_t *pcm, int frames);
    int64_t (*audio_played)(void *opaque);
    void (*audio_pause)(void *opaque, bool pause);
    void (*audio_flush)(void *opaque);
    void (*audio_close)(void *opaque);
    /* 给界面的事件（标题 / 章节变化、进入菜单、静止画面、播放结束、出错…）。 */
    void (*on_event)(void *opaque, int event, int arg1, int arg2, const char *text);
    /* false：测试用，不按时钟等待，尽快处理完。 */
    bool realtime;
} dp_platform;

enum {
    DP_EV_STATE = 1,      /* 标题 / 章节 / 域变化，界面重新取状态 */
    DP_EV_STILL,          /* arg1 = 秒数（255 = 一直静止，等按键） */
    DP_EV_MENU,           /* arg1 = 1 进入菜单（有按钮），0 离开 */
    DP_EV_END,            /* 播放结束 */
    DP_EV_ERROR,          /* text = 原因 */
    DP_EV_FIRST_FRAME,    /* 第一帧显示出来 */
};
