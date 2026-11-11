/*
 * 蓝光解密的端到端测试（电脑上）：libbluray → 打过补丁的 libaacs（解密组件）→ 解密 → 解码播放。
 * 测试盘由 make_bd.py --aacs 生成（测试用的密钥，不是任何真盘的），KEYDB.cfg 在临时目录里。
 *   1. 没有组件（没设 LIBAACS_PATH）：打开时报"该盘有版权保护，需要启用解密组件（AACS）"
 *   2. 有组件、KEYDB 里有 VUK：libbluray 按 LIBAACS_PATH + ".so.0" 找到组件（和 App 一样），组件经 libbluray 的
 *      文件回调读 AACS 目录，解密出能解码的画面和声音
 *   3. 有组件、KEYDB 里没有这张盘：报具体原因（没有处理密钥，带 MKB 版本），不是"需要启用解密组件"
 *   4. 4K UHD（index.bdmv 0300）的加密盘：报"不支持 AACS 2.0"
 * 模拟宿主：认证命令一律失败。这几种情况都不该向光驱发命令，检查确实一条也没发。
 * 用法：dp_bd_aacs_host_test <加密测试盘.iso> <加密 UHD 测试盘.iso> <KEYDB 目录> <libaacs.so（电脑版组件）>
 */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "../src/dc_host.h"
#include "../src/dp_bd.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("✗ "); } else { printf("✓ "); } printf(__VA_ARGS__); printf("\n"); } while (0)

typedef struct {
    FILE *f;
    pthread_mutex_t lock;
    int frames, w, h, ended, errors;
    uint64_t audio_frames;
    int64_t written;
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

/* 模拟宿主：数一数组件发了几条命令（都回 CHECK CONDITION） */
static int n_scsi, n_logs;
static int mock_scsi(void *opaque, const uint8_t *cdb, int cdb_len, int dir, uint8_t *buf, int len, uint8_t *sense, int *sense_len, int timeout) {
    (void)opaque; (void)cdb; (void)cdb_len; (void)dir; (void)buf; (void)len; (void)timeout;
    n_scsi++;
    *sense_len = 18;
    memset(sense, 0, 18);
    sense[0] = 0x70; sense[2] = 0x05; sense[12] = 0x20;
    return 1;
}
static void mock_log(void *opaque, int level, const char *msg) { (void)opaque; (void)level; (void)msg; n_logs++; }

/* 播放器自己的日志（dp_log）：留下 BD 标签的，检查里面的内容；其余照常打印 */
static char bd_log[16384];
static void capture_log(void *opaque, dp_log_level level, const char *tag, const char *msg) {
    (void)opaque;
    (void)level;
    if (strcmp(tag, "BD") == 0 && strlen(bd_log) + strlen(msg) + 2 < sizeof(bd_log)) {
        strcat(bd_log, msg);
        strcat(bd_log, "\n");
    }
    printf("  [%s] %s\n", tag, msg);
}
static dc_host_api mock_host = {DC_HOST_API_VERSION, NULL, mock_scsi, mock_log};

static dp_bd *open_iso(const char *iso, host *h, dp_source **src, char *err, int errlen) {
    memset(h, 0, sizeof(*h));
    pthread_mutex_init(&h->lock, NULL);
    h->f = fopen(iso, "rb");
    if (!h->f) {
        snprintf(err, (size_t)errlen, "打不开 %s", iso);
        return NULL;
    }
    fseeko(h->f, 0, SEEK_END);
    uint64_t size = (uint64_t)ftello(h->f);
    static dp_platform pf;
    dp_platform tmp = {
        .opaque = h, .video_present = hv_present, .audio_open = ha_open, .audio_write = ha_write, .audio_played = ha_played,
        .audio_pause = ha_pause, .audio_flush = ha_flush, .audio_close = ha_close, .on_event = on_event, .realtime = false,
    };
    pf = tmp;
    *src = dp_source_new(read_cb, h, size, 256 * 1024);
    dp_bd_options opt = {.region = 1, .start_playlist = -1, .deinterlace = true};
    strcpy(opt.audio_lang, "jpn");
    strcpy(opt.pg_lang, "chi");
    strcpy(opt.menu_lang, "jpn");
    err[0] = 0;
    return dp_bd_open(*src, &pf, &opt, err, errlen);
}

static void close_iso(dp_bd *bd, host *h, dp_source *src) {
    if (bd) dp_bd_close(bd);
    dp_source_free(src);
    fclose(h->f);
}

static void copy_file(const char *from, const char *to) {
    FILE *a = fopen(from, "rb"), *b = fopen(to, "wb");
    if (!a || !b) { printf("复制 %s 失败\n", from); exit(1); }
    char buf[65536];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), a)) > 0) fwrite(buf, 1, n, b);
    fclose(a);
    fclose(b);
}

int main(int argc, char **argv) {
    if (argc < 5) {
        printf("用法：%s <加密测试盘.iso> <加密 UHD 测试盘.iso> <KEYDB 目录> <libaacs.so>\n", argv[0]);
        return 2;
    }
    const char *iso = argv[1], *uhd_iso = argv[2], *keydb = argv[3], *lib = argv[4];
    char tmp[256], comp[300], base[300], cache[300], empty_cfg[300], path[512];
    snprintf(tmp, sizeof(tmp), "/tmp/dp_bd_aacs_test.%d", (int)getpid());
    mkdir(tmp, 0700);
    /* App 里组件文件名以 .so.0 结尾，LIBAACS_PATH 是去掉后缀的路径（libbluray 自己加 ".so.0"） */
    snprintf(comp, sizeof(comp), "%s/libaacs-test.so.0", tmp);
    snprintf(base, sizeof(base), "%s/libaacs-test", tmp);
    snprintf(cache, sizeof(cache), "%s/cache", tmp);
    snprintf(empty_cfg, sizeof(empty_cfg), "%s/empty", tmp);
    copy_file(lib, comp);
    unsetenv("LIBAACS_PATH");
    /* 和 App 一样："系统配置目录"是个不存在的目录（libaacs 只在第一次用时读这个变量，之后不变） */
    snprintf(path, sizeof(path), "%s/.no-system-config", tmp);
    setenv("XDG_CONFIG_DIRS", path, 1);
    setenv("XDG_CONFIG_HOME", keydb, 1);
    setenv("XDG_CACHE_HOME", cache, 1);
    setenv("HOME", tmp, 1);

    host h;
    dp_source *src;
    char err[256];
    dp_log_set(capture_log, NULL);

    /* ---- 1：没有组件 ---- */
    dp_bd *bd = open_iso(iso, &h, &src, err, sizeof(err));
    CHECK(bd == NULL && strstr(err, "需要启用解密组件"), "没有组件：%s", err[0] ? err : "（居然打开了）");
    close_iso(bd, &h, src);

    /* ---- 2：有组件、KEYDB 里有 VUK ---- */
    void *lh = dlopen(comp, RTLD_NOW | RTLD_LOCAL);
    CHECK(lh != NULL, "加载组件（%s）", lh ? "libaacs-test.so.0" : dlerror());
    if (!lh) return 1;
    int (*set_host)(const dc_host_api *) = (int (*)(const dc_host_api *))dlsym(lh, "aacs_set_host");
    CHECK(set_host && set_host(&mock_host) == DC_HOST_API_VERSION, "注册宿主接口");
    setenv("LIBAACS_PATH", base, 1);
    bd = open_iso(iso, &h, &src, err, sizeof(err));
    CHECK(bd != NULL, "KEYDB 里有 VUK：打开加密蓝光（%s）", bd ? "成功" : err);
    if (bd) {
        dp_bd_state s;
        dp_bd_get_state(bd, &s);
        CHECK(s.aacs, "状态里标了 AACS");
        CHECK(strstr(bd_log, "总线加密 开") != NULL, "读出了内容证书里的总线加密标志");
        CHECK(strstr(bd_log, "盘 ID ") != NULL, "日志里有盘 ID（查 KEYDB 用）");
        dp_bd_start(bd);
        for (int i = 0; i < 1000; i++) {
            dp_bd_get_state(bd, &s);
            if (h.frames > 40 && h.audio_frames > 48000) break;
            dp_sleep_us(10000);
        }
        CHECK(h.frames > 40, "解密后的 H.264 能解码显示（%d 帧，%d×%d）", h.frames, h.w, h.h);
        CHECK(h.audio_frames > 48000, "声音也解出来了（%llu 帧）", (unsigned long long)h.audio_frames);
        CHECK(h.errors == 0, "播放中没有出错（%s）", h.last_error);
        CHECK(s.read_errors == 0, "libbluray 没有丢弃单元（%llu）", (unsigned long long)s.read_errors);
    }
    close_iso(bd, &h, src);
    CHECK(n_scsi == 0, "没有向光驱发认证命令（%d 条）", n_scsi);
    CHECK(n_logs > 0, "组件日志转给了宿主（%d 条）", n_logs);

    /* ---- 3：KEYDB 里没有这张盘 ---- */
    snprintf(path, sizeof(path), "%s/aacs", empty_cfg);
    mkdir(empty_cfg, 0700);
    mkdir(path, 0700);
    snprintf(path, sizeof(path), "%s/aacs/KEYDB.cfg", empty_cfg);
    FILE *kf = fopen(path, "w");
    fputs("; no entries\n0x0000000000000000000000000000000000000001 = Other Disc | D | 2026-01-01\n", kf);
    fclose(kf);
    setenv("XDG_CONFIG_HOME", empty_cfg, 1);
    snprintf(path, sizeof(path), "%s/cache2", tmp);
    setenv("XDG_CACHE_HOME", path, 1);
    bd = open_iso(iso, &h, &src, err, sizeof(err));
    CHECK(bd == NULL && strstr(err, "KEYDB.cfg 里没有这张盘的密钥") && strstr(err, "77"), "KEYDB 里没有这张盘：%s", err[0] ? err : "（居然打开了）");
    close_iso(bd, &h, src);

    /* ---- 4：4K UHD ---- */
    setenv("XDG_CONFIG_HOME", keydb, 1);
    bd = open_iso(uhd_iso, &h, &src, err, sizeof(err));
    CHECK(bd == NULL && strstr(err, "AACS 2.0"), "UHD 加密盘：%s", err[0] ? err : "（居然打开了）");
    close_iso(bd, &h, src);
    CHECK(n_scsi == 0, "全程没有向光驱发命令（%d 条）", n_scsi);

    char cmd[600];
    snprintf(cmd, sizeof(cmd), "rm -rf '%s'", tmp);
    if (system(cmd) != 0) printf("清理临时目录失败\n");
    printf(fails ? "\n有 %d 项失败\n" : "\n全部通过\n", fails);
    return fails ? 1 : 0;
}
