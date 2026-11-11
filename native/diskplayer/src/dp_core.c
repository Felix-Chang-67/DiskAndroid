#define _GNU_SOURCE
#include "dp_core.h"

#include <errno.h>
#include <sys/resource.h>
#include <unistd.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ---------------- 日志 ---------------- */

static dp_log_fn g_log_fn;
static void *g_log_opaque;

void dp_log_set(dp_log_fn fn, void *opaque) {
    g_log_fn = fn;
    g_log_opaque = opaque;
}

void dp_log(dp_log_level level, const char *tag, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (g_log_fn) {
        g_log_fn(g_log_opaque, level, tag, buf);
    } else {
        fprintf(stderr, "[%s] %s\n", tag, buf);
    }
}

int64_t dp_now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

void dp_thread_priority(int nice, const char *name) {
    if (setpriority(PRIO_PROCESS, (id_t)gettid(), nice) != 0) {
        dp_log(DP_LOG_DEBUG, "DVD", "%s线程优先级设不了（%d）：%s", name, nice, strerror(errno));
    }
}

void dp_sleep_us(int64_t us) {
    if (us <= 0) return;
    struct timespec ts = {(time_t)(us / 1000000), (long)(us % 1000000) * 1000};
    while (nanosleep(&ts, &ts) != 0 && errno == EINTR) {
    }
}

/* ---------------- 包队列 ---------------- */

/* FFmpeg 的解析器 / 解码器会读到数据末尾之后（AV_INPUT_BUFFER_PADDING_SIZE = 64），所以每个包后面补 64 个零字节。 */
#define DP_PADDING 64

dp_packet *dp_packet_new(int kind, int stream, int64_t pts, const uint8_t *data, int size) {
    dp_packet *p = malloc(sizeof(dp_packet) + (size > 0 ? (size_t)size : 0) + DP_PADDING);
    if (!p) return NULL;
    memset(p->data + (size > 0 ? size : 0), 0, DP_PADDING);
    p->next = NULL;
    p->kind = kind;
    p->stream = stream;
    p->codec = 0;
    p->pts = pts;
    p->dts = DP_NOPTS;
    p->size = size > 0 ? size : 0;
    if (size > 0 && data) memcpy(p->data, data, size);
    return p;
}

void dp_queue_init(dp_queue *q, size_t max_bytes) {
    memset(q, 0, sizeof(*q));
    pthread_mutex_init(&q->lock, NULL);
    pthread_cond_init(&q->cond, NULL);
    q->max_bytes = max_bytes;
}

void dp_queue_destroy(dp_queue *q) {
    dp_queue_clear(q);
    pthread_mutex_destroy(&q->lock);
    pthread_cond_destroy(&q->cond);
}

bool dp_queue_put(dp_queue *q, dp_packet *p, bool block) {
    pthread_mutex_lock(&q->lock);
    while (block && !q->abort && q->bytes + (size_t)p->size > q->max_bytes && q->count > 0) {
        pthread_cond_wait(&q->cond, &q->lock);
    }
    if (q->abort) {
        pthread_mutex_unlock(&q->lock);
        free(p);
        return false;
    }
    p->next = NULL;
    if (q->tail) q->tail->next = p; else q->head = p;
    q->tail = p;
    q->count++;
    q->bytes += (size_t)p->size;
    pthread_cond_broadcast(&q->cond);
    pthread_mutex_unlock(&q->lock);
    return true;
}

dp_packet *dp_queue_get(dp_queue *q, int timeout_ms) {
    pthread_mutex_lock(&q->lock);
    if (!q->head && !q->abort && timeout_ms != 0) {
        if (timeout_ms < 0) {
            while (!q->head && !q->abort) pthread_cond_wait(&q->cond, &q->lock);
        } else {
            struct timespec ts;
            clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_sec += timeout_ms / 1000;
            ts.tv_nsec += (long)(timeout_ms % 1000) * 1000000;
            if (ts.tv_nsec >= 1000000000) {
                ts.tv_sec++;
                ts.tv_nsec -= 1000000000;
            }
            while (!q->head && !q->abort) {
                if (pthread_cond_timedwait(&q->cond, &q->lock, &ts) == ETIMEDOUT) break;
            }
        }
    }
    dp_packet *p = NULL;
    if (!q->abort && q->head) {
        p = q->head;
        q->head = p->next;
        if (!q->head) q->tail = NULL;
        q->count--;
        q->bytes -= (size_t)p->size;
        pthread_cond_broadcast(&q->cond);
    }
    pthread_mutex_unlock(&q->lock);
    return p;
}

void dp_queue_clear(dp_queue *q) {
    pthread_mutex_lock(&q->lock);
    dp_packet *p = q->head;
    while (p) {
        dp_packet *n = p->next;
        free(p);
        p = n;
    }
    q->head = q->tail = NULL;
    q->count = 0;
    q->bytes = 0;
    pthread_cond_broadcast(&q->cond);
    pthread_mutex_unlock(&q->lock);
}

void dp_queue_set_abort(dp_queue *q, bool abort) {
    pthread_mutex_lock(&q->lock);
    q->abort = abort;
    pthread_cond_broadcast(&q->cond);
    pthread_mutex_unlock(&q->lock);
}

int dp_queue_count(dp_queue *q) {
    pthread_mutex_lock(&q->lock);
    int n = q->count;
    pthread_mutex_unlock(&q->lock);
    return n;
}

size_t dp_queue_bytes(dp_queue *q) {
    pthread_mutex_lock(&q->lock);
    size_t n = q->bytes;
    pthread_mutex_unlock(&q->lock);
    return n;
}

/* ---------------- 读盘源 ---------------- */

dp_source *dp_source_new(dp_read_fn read, void *opaque, uint64_t size, int prefetch_bytes) {
    dp_source *s = calloc(1, sizeof(*s));
    if (!s) return NULL;
    s->read = read;
    s->opaque = opaque;
    s->size = size;
    s->cache_cap = (prefetch_bytes / DP_SECTOR) * DP_SECTOR;
    if (s->cache_cap < DP_SECTOR) s->cache_cap = DP_SECTOR;
    s->cache = malloc((size_t)s->cache_cap);
    pthread_mutex_init(&s->lock, NULL);
    if (!s->cache) {
        free(s);
        return NULL;
    }
    return s;
}

void dp_source_free(dp_source *s) {
    if (!s) return;
    pthread_mutex_destroy(&s->lock);
    free(s->cache);
    free(s);
}

/* 从 off 起填满缓存（不超过盘尾）。整块读不出时逐扇区读，读不出的扇区补零。 */
static void fill_cache(dp_source *s, uint64_t off) {
    uint64_t left = s->size > off ? s->size - off : 0;
    int len = (int)(left < (uint64_t)s->cache_cap ? left : (uint64_t)s->cache_cap);
    s->cache_off = off;
    s->cache_len = 0;
    if (len <= 0) return;
    s->upcalls++;
    int64_t t0 = dp_now_us();
    int n = s->read(s->opaque, off, s->cache, len);
    uint64_t took = (uint64_t)(dp_now_us() - t0);
    s->read_us += took;
    if (took > s->read_max_us) s->read_max_us = took;
    if (n == len) {
        s->cache_len = len;
        s->bytes_read += (uint64_t)n;
        return;
    }
    /* 逐扇区重读：坏扇区补零，盘片取出等（返回 -2）就停下 */
    int done = n > 0 ? (n / DP_SECTOR) * DP_SECTOR : 0;
    while (done < len) {
        s->upcalls++;
        int r = s->read(s->opaque, off + (uint64_t)done, s->cache + done, DP_SECTOR);
        if (r == -2) break;
        if (r != DP_SECTOR) {
            memset(s->cache + done, 0, DP_SECTOR);
            s->errors++;
            dp_log(DP_LOG_WARN, "SRC", "扇区 %llu 读不出，补零", (unsigned long long)((off + (uint64_t)done) / DP_SECTOR));
        }
        done += DP_SECTOR;
    }
    s->cache_len = done;
    s->bytes_read += (uint64_t)done;
}

static int read_at(dp_source *s, uint64_t off, uint8_t *dst, int len) {
    int total = 0;
    while (len > 0) {
        if (off < s->cache_off || off >= s->cache_off + (uint64_t)s->cache_len) {
            fill_cache(s, off - off % DP_SECTOR);
            if (s->cache_len == 0) break;
        }
        int avail = (int)(s->cache_off + (uint64_t)s->cache_len - off);
        int n = len < avail ? len : avail;
        memcpy(dst + total, s->cache + (off - s->cache_off), (size_t)n);
        total += n;
        off += (uint64_t)n;
        len -= n;
    }
    return total;
}

int dp_source_seek(void *opaque, uint64_t pos) {
    dp_source *s = opaque;
    pthread_mutex_lock(&s->lock);
    s->pos = pos;
    pthread_mutex_unlock(&s->lock);
    return 0;
}

int dp_source_read(void *opaque, void *buf, int len) {
    dp_source *s = opaque;
    pthread_mutex_lock(&s->lock);
    int n = read_at(s, s->pos, buf, len);
    s->pos += (uint64_t)(n > 0 ? n : 0);
    pthread_mutex_unlock(&s->lock);
    return n > 0 ? n : (len == 0 ? 0 : -1);
}

int dp_source_read_blocks(void *opaque, void *buf, int lba, int num_blocks) {
    dp_source *s = opaque;
    pthread_mutex_lock(&s->lock);
    int n = read_at(s, (uint64_t)lba * DP_SECTOR, buf, num_blocks * DP_SECTOR);
    pthread_mutex_unlock(&s->lock);
    return n / DP_SECTOR;
}
