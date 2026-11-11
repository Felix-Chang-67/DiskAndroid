/*
 * 原生蓝光播放器在电脑上的测试：用 make_bd.py 生成的测试蓝光（UDF 镜像）走一遍
 *   1. 导航模式：First Play → 电影对象 0 → PlayPL 00000 → H.264 解码、按语言偏好（日语）选 AC-3
 *   2. 换音轨：第 2 条（英语 LPCM，pcm_bluray 解码）
 *   3. 跳到标题 2（PlayPL 00001）
 *   4. 直接播放播放列表 00001（不经菜单）直到结束
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <libavcodec/avcodec.h>

#include "../src/dp_bd.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("✗ "); } else { printf("✓ "); } printf(__VA_ARGS__); printf("\n"); } while (0)

typedef struct {
    FILE *f;
    pthread_mutex_t lock;
    int frames, w, h;
    uint64_t audio_frames;
    int64_t written;
    int ended, errors;
    char last_error[256];
} host;

static int read_cb(void *o, uint64_t off, uint8_t *buf, int len) {
    host *h = o;
    if (fseeko(h->f, (off_t)off, SEEK_SET) != 0) return -1;
    size_t n = fread(buf, 1, (size_t)len, h->f);
    if (n < (size_t)len) memset(buf + n, 0, (size_t)len - n);
    return len;
}

static void hv_present(void *o, const dp_frame *f, const uint32_t *ov, int ov_w, int ov_h, bool changed) {
    host *h = o;
    (void)ov; (void)ov_w; (void)ov_h; (void)changed;
    pthread_mutex_lock(&h->lock);
    h->frames++;
    h->w = f->width;
    h->h = f->height;
    pthread_mutex_unlock(&h->lock);
}
static int ha_open(void *o, int rate, int ch) { (void)o; (void)rate; (void)ch; return 0; }
static int ha_write(void *o, const int16_t *pcm, int n) {
    host *h = o;
    (void)pcm;
    pthread_mutex_lock(&h->lock);
    h->written += n;
    h->audio_frames += (uint64_t)n;
    pthread_mutex_unlock(&h->lock);
    return n;
}
static int64_t ha_played(void *o) { host *h = o; return h->written; }
static void ha_pause(void *o, bool p) { (void)o; (void)p; }
static void ha_flush(void *o) { host *h = o; h->written = 0; }
static void ha_close(void *o) { (void)o; }
static void on_event(void *o, int ev, int a1, int a2, const char *text) {
    host *h = o;
    (void)a1; (void)a2;
    pthread_mutex_lock(&h->lock);
    if (ev == DP_EV_END) h->ended++;
    if (ev == DP_EV_ERROR) {
        h->errors++;
        snprintf(h->last_error, sizeof(h->last_error), "%s", text ? text : "");
    }
    pthread_mutex_unlock(&h->lock);
}

static dp_bd_state wait_for(dp_bd *bd, host *h, int ms, int (*cond)(const dp_bd_state *, const host *)) {
    dp_bd_state s;
    for (int i = 0; i < ms / 10; i++) {
        dp_bd_get_state(bd, &s);
        if (cond(&s, h)) return s;
        dp_sleep_us(10000);
    }
    dp_bd_get_state(bd, &s);
    return s;
}

static int c_playing(const dp_bd_state *s, const host *h) { return s->playlist == 0 && h->frames > 20 && s->audio_codec != 0; }
static int c_lpcm(const dp_bd_state *s, const host *h) { (void)h; return s->audio_codec == AV_CODEC_ID_PCM_BLURAY; }
static int c_title2(const dp_bd_state *s, const host *h) { (void)h; return s->playlist == 1; }
static int c_end(const dp_bd_state *s, const host *h) { (void)s; return h->ended > 0; }

int main(int argc, char **argv) {
    const char *iso = argc > 1 ? argv[1] : "test/data/test_bd.iso";
    host h;
    memset(&h, 0, sizeof(h));
    pthread_mutex_init(&h.lock, NULL);
    h.f = fopen(iso, "rb");
    if (!h.f) {
        printf("打不开 %s\n", iso);
        return 1;
    }
    fseeko(h.f, 0, SEEK_END);
    uint64_t size = (uint64_t)ftello(h.f);
    dp_platform pf = {
        .opaque = &h, .video_present = hv_present, .audio_open = ha_open, .audio_write = ha_write, .audio_played = ha_played,
        .audio_pause = ha_pause, .audio_flush = ha_flush, .audio_close = ha_close, .on_event = on_event, .realtime = false,
    };

    /* ---- 1–3：导航模式 ---- */
    dp_source *src = dp_source_new(read_cb, &h, size, 256 * 1024);
    dp_bd_options opt = {.region = 1, .start_playlist = -1, .deinterlace = true};
    strcpy(opt.audio_lang, "jpn");
    strcpy(opt.pg_lang, "chi");
    strcpy(opt.menu_lang, "jpn");
    char err[256] = "";
    dp_bd *bd = dp_bd_open(src, &pf, &opt, err, sizeof(err));
    CHECK(bd != NULL, "打开测试蓝光（%s）", bd ? "导航模式" : err);
    if (!bd) return 1;
    dp_bd_state s;
    dp_bd_get_state(bd, &s);
    CHECK(s.mode == DP_BD_MODE_MENU, "用光盘程序导航（模式 %d）", s.mode);
    CHECK(s.titles == 2, "盘上有 2 个标题（%d）", s.titles);
    dp_bd_start(bd);
    s = wait_for(bd, &h, 10000, c_playing);
    CHECK(s.playlist == 0, "First Play 播放了 00000.mpls（%d）", s.playlist);
    CHECK(h.frames > 20, "解码并显示了 H.264 画面（%d 帧，%d×%d）", h.frames, h.w, h.h);
    CHECK(h.w == 1280 && h.h == 720, "画面尺寸 1280×720");
    CHECK(s.audio_count == 2, "这一段有 2 条音轨（%d：%s / %s）", s.audio_count, s.audio_desc[0], s.audio_desc[1]);
    CHECK(s.audio_codec == AV_CODEC_ID_AC3, "按语言偏好（日语）选了 AC-3（解码器 %d）", s.audio_codec);
    CHECK(s.chapters == 3, "3 个章节（%d）", s.chapters);
    CHECK(h.audio_frames > 0, "解出了声音（%llu 帧）", (unsigned long long)h.audio_frames);

    dp_bd_command(bd, DP_CMD_AUDIO, 1, 0);
    s = wait_for(bd, &h, 5000, c_lpcm);
    CHECK(s.audio_codec == AV_CODEC_ID_PCM_BLURAY, "换到第 2 条音轨后解码的是 LPCM（pcm_bluray）");
    CHECK(s.audio_current == 1, "当前音轨是第 2 条（%d）", s.audio_current + 1);

    int frames_before = h.frames;
    dp_bd_command(bd, DP_CMD_TITLE_PLAY, 2, 0);
    s = wait_for(bd, &h, 10000, c_title2);
    CHECK(s.playlist == 1, "跳到标题 2 播放 00001.mpls（%d）", s.playlist);
    dp_sleep_us(500000);
    CHECK(h.frames > frames_before, "标题 2 也有画面（%d 帧）", h.frames - frames_before);
    CHECK(h.errors == 0, "没有出错（%s）", h.last_error);
    dp_bd_close(bd);
    dp_source_free(src);

    /* ---- 4：直接播放 ---- */
    h.frames = 0;
    h.ended = 0;
    src = dp_source_new(read_cb, &h, size, 256 * 1024);
    opt.start_playlist = 1;
    bd = dp_bd_open(src, &pf, &opt, err, sizeof(err));
    CHECK(bd != NULL, "直接播放 00001.mpls（%s）", bd ? "已打开" : err);
    if (bd) {
        dp_bd_get_state(bd, &s);
        CHECK(s.mode == DP_BD_MODE_PLAYLIST, "直接播放模式（%d）", s.mode);
        dp_bd_start(bd);
        s = wait_for(bd, &h, 10000, c_end);
        CHECK(h.ended > 0, "播到结尾（%d 帧）", h.frames);
        CHECK(s.ended, "状态是已结束");
        CHECK(h.frames >= 60, "3 秒 25 帧/秒的画面基本都显示了（%d）", h.frames);
        dp_bd_close(bd);
    }
    dp_source_free(src);
    fclose(h.f);
    printf(fails ? "\n有 %d 项失败\n" : "\n全部通过\n", fails);
    return fails ? 1 : 0;
}
