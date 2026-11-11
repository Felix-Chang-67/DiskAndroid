/*
 * 原生 DVD 播放核心在电脑上的测试：用 make_menu_dvd.sh 做的带菜单的测试盘，不按时钟等待（realtime = false），
 * 检查首播 → 菜单（静止画面、按钮高亮）→ 键盘 / 触摸选按钮 → 播放标题（章节、音频解码）→ 播完回菜单。
 * 用法：dp_host_test <menu_dvd.iso>；全部通过返回 0。
 */
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../src/dp_core.h"
#include "../src/dp_dvd.h"

static int failures;
#define CHECK(cond, ...)                                  \
    do {                                                  \
        if (!(cond)) {                                    \
            failures++;                                   \
            fprintf(stderr, "✗ %s:%d ", __FILE__, __LINE__); \
            fprintf(stderr, __VA_ARGS__);                 \
            fprintf(stderr, "\n");                        \
        } else {                                          \
            fprintf(stderr, "✓ ");                        \
            fprintf(stderr, __VA_ARGS__);                 \
            fprintf(stderr, "\n");                        \
        }                                                 \
    } while (0)

/* ---------------- 文件当作光盘 ---------------- */

static int file_read(void *opaque, uint64_t off, uint8_t *buf, int len) {
    FILE *f = opaque;
    if (fseeko(f, (off_t)off, SEEK_SET) != 0) return -1;
    size_t n = fread(buf, 1, (size_t)len, f);
    return (int)n;
}

/* ---------------- 假的平台 ---------------- */

typedef struct {
    pthread_mutex_t lock;
    uint64_t frames, frames_overlay, audio_frames;
    int64_t written;
    uint32_t px_b1, px_b2;      /* 两个按钮中心的叠加层颜色 */
    bool saw_menu;
    int transitions[64][3];     /* (域, 标题, 章节) 的变化序列 */
    int ntrans;
    int titles_played[8];
    int title_audio_codes[8];   /* 播放各标题时最后一次看到的音频流号 */
    int max_chapter[8];
    int ends;
    dp_dvd *d;
} host;

static void hv_present(void *o, const dp_frame *f, const uint32_t *ov, int ov_w, int ov_h, bool changed) {
    host *h = o;
    (void)changed;
    (void)f;
    pthread_mutex_lock(&h->lock);
    h->frames++;
    if (ov && ov_w > 200 && ov_h > 230) {
        h->frames_overlay++;
        h->px_b1 = ov[130 * ov_w + 200];
        h->px_b2 = ov[230 * ov_w + 200];
    }
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
static int64_t ha_played(void *o) {
    host *h = o;
    pthread_mutex_lock(&h->lock);
    int64_t w = h->written;
    pthread_mutex_unlock(&h->lock);
    return w;
}
static void ha_pause(void *o, bool p) { (void)o; (void)p; }
static void ha_flush(void *o) {
    host *h = o;
    pthread_mutex_lock(&h->lock);
    h->written = 0;
    pthread_mutex_unlock(&h->lock);
}
static void ha_close(void *o) { (void)o; }

static void on_event(void *o, int ev, int a1, int a2, const char *text) {
    host *h = o;
    (void)a2;
    if (ev == DP_EV_STATE || ev == DP_EV_STILL || ev == DP_EV_MENU) {
        dp_dvd_state s;
        dp_dvd_get_state(h->d, &s);
        pthread_mutex_lock(&h->lock);
        int *last = h->ntrans ? h->transitions[h->ntrans - 1] : NULL;
        if (!last || last[0] != s.domain || last[1] != s.title || last[2] != s.chapter) {
            if (h->ntrans < 64) {
                h->transitions[h->ntrans][0] = s.domain;
                h->transitions[h->ntrans][1] = s.title;
                h->transitions[h->ntrans][2] = s.chapter;
                h->ntrans++;
            }
        }
        if (s.domain == DP_DOMAIN_TITLE && s.title > 0 && s.title < 8) {
            h->titles_played[s.title] = 1;
            if (s.audio_code >= 0) h->title_audio_codes[s.title] = s.audio_code;
            if (s.chapter > h->max_chapter[s.title]) h->max_chapter[s.title] = s.chapter;
        }
        if (ev == DP_EV_MENU && a1) h->saw_menu = true;
        pthread_mutex_unlock(&h->lock);
    }
    if (ev == DP_EV_END) h->ends++;
    if (ev == DP_EV_ERROR) fprintf(stderr, "事件：出错 %s\n", text ? text : "");
}

static volatile bool g_saw_lpcm, g_saw_ac3;

static void log_fn(void *o, dp_log_level lv, const char *tag, const char *msg) {
    (void)o;
    if (strstr(msg, "音频解码器：pcm_dvd")) g_saw_lpcm = true;
    if (strstr(msg, "音频解码器：ac3")) g_saw_ac3 = true;
    if (lv >= DP_LOG_INFO || getenv("DP_DEBUG")) fprintf(stderr, "  [%s] %s\n", tag, msg);
}

static bool wait_for(dp_dvd *d, int ms, bool (*pred)(dp_dvd_state *, void *), void *arg, dp_dvd_state *out) {
    for (int t = 0; t < ms; t += 5) {
        dp_dvd_get_state(d, out);
        if (pred(out, arg)) return true;
        dp_sleep_us(5000);
    }
    return false;
}

static bool in_menu_still(dp_dvd_state *s, void *a) { (void)a; return s->domain == DP_DOMAIN_VMG_MENU && s->still && s->buttons == 2; }
static bool button_is(dp_dvd_state *s, void *a) { return s->button == *(int *)a; }

static bool is_yellow(uint32_t px) { return (px & 0xFF) > 0xC0 && ((px >> 8) & 0xFF) > 0xC0 && ((px >> 16) & 0xFF) < 0x60 && (px >> 24) > 0x80; }
static bool is_white(uint32_t px) { return (px & 0xFF) > 0xC0 && ((px >> 8) & 0xFF) > 0xC0 && ((px >> 16) & 0xFF) > 0xC0 && (px >> 24) > 0x80; }

/* 等视频线程把排队的帧处理完（不按时钟时，导航线程会远远跑在前面）。 */
static void settle(host *h) {
    uint64_t last = (uint64_t)-1;
    for (int i = 0; i < 200; i++) {
        pthread_mutex_lock(&h->lock);
        uint64_t f = h->frames;
        pthread_mutex_unlock(&h->lock);
        if (f == last) return;
        last = f;
        dp_sleep_us(20000);
    }
}

static dp_dvd *open_disc(const char *path, host *h, FILE **fp, dp_source **srcp, const char *audio_lang, int start_title) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        perror(path);
        exit(2);
    }
    fseeko(f, 0, SEEK_END);
    uint64_t size = (uint64_t)ftello(f);
    dp_source *src = dp_source_new(file_read, f, size, 256 * 1024);
    dp_platform pf = {
        .opaque = h, .video_present = hv_present, .audio_open = ha_open, .audio_write = ha_write, .audio_played = ha_played,
        .audio_pause = ha_pause, .audio_flush = ha_flush, .audio_close = ha_close, .on_event = on_event, .realtime = false,
    };
    dp_dvd_options opt = {.region_mask = 0xFF, .start_title = start_title, .deinterlace = true};
    strcpy(opt.menu_lang, "ja");
    strcpy(opt.audio_lang, audio_lang);
    strcpy(opt.spu_lang, "ja");
    char err[256] = "";
    dp_dvd *d = dp_dvd_open(src, &pf, &opt, err, sizeof(err));
    if (!d) {
        fprintf(stderr, "打不开：%s\n", err);
        exit(2);
    }
    h->d = d;
    *fp = f;
    *srcp = src;
    return d;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "用法：%s menu_dvd.iso\n", argv[0]);
        return 2;
    }
    dp_log_set(log_fn, NULL);

    /* ======== 第 1 轮：从首播开始，走菜单 ======== */
    host h;
    memset(&h, 0, sizeof(h));
    pthread_mutex_init(&h.lock, NULL);
    FILE *f;
    dp_source *src;
    dp_dvd *d = open_disc(argv[1], &h, &f, &src, "ja", 0);
    dp_dvd_state s;
    dp_dvd_get_state(d, &s);
    CHECK(s.titles == 2, "盘上有 2 个标题（%d）", s.titles);
    dp_dvd_start(d);

    CHECK(wait_for(d, 10000, in_menu_still, NULL, &s), "首播后停在根菜单的静止画面，2 个按钮（域 %d，静止 %d，按钮 %d）", s.domain, s.still, s.buttons);
    CHECK(s.button == 1, "默认选中第 1 个按钮（%d）", s.button);
    settle(&h);
    pthread_mutex_lock(&h.lock);
    uint32_t b1 = h.px_b1, b2 = h.px_b2;
    uint64_t ovf = h.frames_overlay;
    pthread_mutex_unlock(&h.lock);
    CHECK(ovf > 0, "菜单画面带叠加层（%llu 帧）", (unsigned long long)ovf);
    CHECK(is_yellow(b1), "按钮 1 是高亮的黄色（%08X）", b1);
    CHECK(is_white(b2), "按钮 2 是普通的白色（%08X）", b2);

    dp_dvd_command(d, DP_CMD_DOWN, 0, 0);
    int two = 2;
    CHECK(wait_for(d, 3000, button_is, &two, &s), "按“下”选中第 2 个按钮（%d）", s.button);
    settle(&h);
    pthread_mutex_lock(&h.lock);
    b1 = h.px_b1;
    b2 = h.px_b2;
    pthread_mutex_unlock(&h.lock);
    CHECK(is_white(b1) && is_yellow(b2), "高亮移到按钮 2（按钮 1 %08X，按钮 2 %08X）", b1, b2);

    dp_dvd_command(d, DP_CMD_ACTIVATE, 0, 0);
    /* 不按时钟时 3 秒的标题一眨眼就播完了：看事件记录里有没有播过标题 2 */
    dp_sleep_us(300000);
    CHECK(wait_for(d, 15000, in_menu_still, NULL, &s), "标题 2 播完回到菜单");
    pthread_mutex_lock(&h.lock);
    CHECK(h.titles_played[2] && !h.titles_played[1], "按“确定”播放的是标题 2");
    pthread_mutex_unlock(&h.lock);

    /* 触摸：先点按钮 1 的位置（选中），再点一次（确定） */
    dp_dvd_command(d, DP_CMD_MOUSE_SELECT, 200, 130);
    int one = 1;
    CHECK(wait_for(d, 3000, button_is, &one, &s), "点按钮 1 的位置选中它（%d）", s.button);
    dp_dvd_command(d, DP_CMD_MOUSE_ACTIVATE, 200, 130);
    dp_sleep_us(300000);
    CHECK(wait_for(d, 20000, in_menu_still, NULL, &s), "标题 1 播完回到菜单");
    settle(&h);
    pthread_mutex_lock(&h.lock);
    CHECK(h.titles_played[1] && h.titles_played[2], "两个标题都播过了");
    CHECK(h.max_chapter[1] == 3, "标题 1 播到了第 3 章（%d）", h.max_chapter[1]);
    CHECK(h.frames > 250, "显示了足够多的帧（%llu）", (unsigned long long)h.frames);
    CHECK(h.audio_frames > 48000 * 6, "AC-3 解出了音频（%llu 帧）", (unsigned long long)h.audio_frames);
    pthread_mutex_unlock(&h.lock);
    dp_dvd_get_state(d, &s);
    CHECK(s.read_errors == 0, "没有读错（%llu）", (unsigned long long)s.read_errors);
    fprintf(stderr, "  读盘 %llu 字节，经回调 %llu 次；显示 %llu 帧，丢 %llu 帧\n", (unsigned long long)src->bytes_read,
            (unsigned long long)src->upcalls, (unsigned long long)s.frames_shown, (unsigned long long)s.frames_dropped);
    dp_dvd_close(d);
    dp_source_free(src);
    fclose(f);

    /* ======== 第 2 轮：直接播放标题 1，音轨选英语（LPCM），中途换回日语（AC-3）、按章节跳 ======== */
    memset(&h, 0, sizeof(h));
    pthread_mutex_init(&h.lock, NULL);
    g_saw_lpcm = g_saw_ac3 = false;
    d = open_disc(argv[1], &h, &f, &src, "en", 1);
    dp_dvd_start(d);
    dp_sleep_us(300000);
    CHECK(wait_for(d, 20000, in_menu_still, NULL, &s), "直接播放标题 1，播完回菜单");
    pthread_mutex_lock(&h.lock);
    CHECK(h.titles_played[1] && !h.titles_played[2], "播的是标题 1（没有经过菜单选择）");
    CHECK(h.max_chapter[1] == 3, "播到了第 3 章（%d）", h.max_chapter[1]);
    pthread_mutex_unlock(&h.lock);
    CHECK(g_saw_lpcm, "按语言偏好（英语）选了 LPCM 音轨并用 pcm_dvd 解码");
    pthread_mutex_lock(&h.lock);
    CHECK(h.title_audio_codes[1] == 0xA0, "标题 1 解码的是 LPCM（流 %02Xh）", h.title_audio_codes[1]);
    h.title_audio_codes[1] = 0;
    pthread_mutex_unlock(&h.lock);
    /* 再从菜单进标题 1，手动换回日语（AC-3） */
    g_saw_ac3 = false;
    dp_dvd_command(d, DP_CMD_PAUSE, 0, 0);
    dp_dvd_command(d, DP_CMD_TITLE_PLAY, 1, 0);
    dp_dvd_command(d, DP_CMD_AUDIO, 0, 0);
    dp_dvd_command(d, DP_CMD_RESUME, 0, 0);
    dp_sleep_us(300000);
    CHECK(wait_for(d, 20000, in_menu_still, NULL, &s), "换音轨后播完回菜单（域 %d 标题 %d 静止 %d 按钮 %d）", s.domain, s.title, s.still, s.buttons);
    pthread_mutex_lock(&h.lock);
    CHECK(h.title_audio_codes[1] == 0x80, "手动换到第 1 条音轨后解码的是 AC-3（流 %02Xh）", h.title_audio_codes[1]);
    pthread_mutex_unlock(&h.lock);
    dp_dvd_close(d);
    dp_source_free(src);
    fclose(f);

    /* ======== 第 3 轮：只有 LPCM、光盘程序不选音轨（音轨寄存器一直是 15）：从菜单进标题 1，要有声音 ======== */
    if (argc > 2) {
        memset(&h, 0, sizeof(h));
        pthread_mutex_init(&h.lock, NULL);
        g_saw_lpcm = g_saw_ac3 = false;
        d = open_disc(argv[2], &h, &f, &src, "ja", 0);
        dp_dvd_start(d);
        CHECK(wait_for(d, 20000, in_menu_still, NULL, &s), "LPCM 盘：停在菜单");
        dp_dvd_command(d, DP_CMD_MOUSE_ACTIVATE, 200, 130);
        dp_sleep_us(300000);
        CHECK(wait_for(d, 20000, in_menu_still, NULL, &s), "LPCM 盘：标题 1 播完回菜单");
        settle(&h);
        pthread_mutex_lock(&h.lock);
        CHECK(h.titles_played[1], "LPCM 盘：播了标题 1");
        CHECK(h.title_audio_codes[1] == 0xA0, "LPCM 盘：标题 1 按 LPCM 解码（流 %02Xh；以前当成 AC-3，没有声音）", h.title_audio_codes[1]);
        CHECK(h.audio_frames > 48000 * 4, "LPCM 盘：解出了声音（%llu 帧）", (unsigned long long)h.audio_frames);
        pthread_mutex_unlock(&h.lock);
        CHECK(g_saw_lpcm, "LPCM 盘：用的是 pcm_dvd 解码器");
        dp_dvd_close(d);
        dp_source_free(src);
        fclose(f);
    }

    fprintf(stderr, failures ? "\n%d 项失败\n" : "\n全部通过\n", failures);
    return failures ? 1 : 0;
}
