/*
 * 打过补丁的 libaacs：用一张"测试盘"（临时目录里的 AACS/Unit_Key_RO.inf）和模拟光驱检查
 *   1. KEYDB.cfg 里有这张盘的 VUK：不碰光驱，算出 CPS 单元密钥，解密一个 6144 字节的单元
 *   2. 密钥缓存写在 XDG_CACHE_HOME 下面（App 的缓存目录）：KEYDB 里去掉 VUK 也能打开
 *   3. 只有媒体密钥（要向光驱读 Volume ID）：命令经宿主回调发出，格式正确（GET CONFIGURATION 010Dh、
 *      REPORT KEY KEY CLASS 02h 38h）；没有可用的主机证书时不会发 SEND KEY，报 AACS_ERROR_NO_CERT
 *   4. 光驱不支持 AACS（GET CONFIGURATION 返回 CHECK CONDITION）：报 AACS_ERROR_MMC_OPEN，日志里有 sense
 *   5. 没有注册宿主：不发任何命令
 *   6. 日志：转给宿主，密钥、证书、命令数据（16 个以上连续十六进制字符）都被替换掉，只保留盘 ID
 * 模拟光驱给不出 AACS LA 签名的光驱证书，所以真正的主机认证在这里走不到——那一步要在真机上看。
 */
#include <dirent.h>
#include <dlfcn.h>
#include <gcrypt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "dc_host.h"

typedef struct aacs AACS;

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("✗ "); } else { printf("✓ "); } printf(__VA_ARGS__); printf("\n"); } while (0)

static AACS *(*p_init)(void);
static int (*p_open_device)(AACS *, const char *, const char *);
static void (*p_close)(AACS *);
static int (*p_decrypt_unit)(AACS *, uint8_t *);
static const uint8_t *(*p_disc_id)(AACS *);
static int (*p_set_host)(const dc_host_api *);

/* ---------------- 模拟光驱 ---------------- */

static int n_cmds, n_send_key, n_getconf, n_drive_cert, bad_cdb, no_aacs;
static char logs[64 * 1024];
static int n_logs, unredacted;

static int mock_scsi(void *opaque, const uint8_t *cdb, int cdb_len, int dir, uint8_t *buf, int len, uint8_t *sense, int *sense_len, int timeout) {
    (void)opaque; (void)timeout;
    n_cmds++;
    switch (cdb[0]) {
        case 0x46: { /* GET CONFIGURATION：RT = 01b，起始功能 010Dh，10 字节 CDB */
            n_getconf++;
            int alloc = (cdb[7] << 8) | cdb[8];
            if (cdb_len != 10 || (cdb[1] & 3) != 1 || cdb[2] != 0x01 || cdb[3] != 0x0D || alloc != len || dir != DC_DIR_IN) bad_cdb++;
            if (no_aacs) break;
            memset(buf, 0, len);
            buf[3] = 8 + 8;                       /* 头 8 字节 + 一个功能描述符 */
            buf[8] = 0x01; buf[9] = 0x0D;         /* 功能 010Dh：AACS */
            buf[10] = 0x05;                       /* 版本 1、当前有效 */
            buf[11] = 4;
            buf[12] = 0x10;                       /* 能读光驱证书 */
            buf[14] = 1;                          /* 1 个 AGID */
            buf[15] = 1;                          /* AACS 版本 1 */
            return 0;
        }
        case 0xA4: { /* REPORT KEY：KEY CLASS 02h，12 字节 CDB */
            int alloc = (cdb[8] << 8) | cdb[9], fmt = cdb[10] & 0x3f;
            if (cdb_len != 12 || cdb[7] != 0x02 || alloc != len) bad_cdb++;
            if (fmt == 0x38) {                    /* 光驱证书：随便给一份，签名验不过 */
                n_drive_cert++;
                if (len != 96 || dir != DC_DIR_IN) bad_cdb++;
                memset(buf, 0, len);
                buf[1] = 0x5E;
                buf[4] = 0x01;
                return 0;
            }
            if (fmt == 0x00 || fmt == 0x3F) { memset(buf, 0, len); return 0; }
            bad_cdb++;
            break;
        }
        case 0xA3:
            n_send_key++;
            break;
        default:
            bad_cdb++;
            break;
    }
    /* CHECK CONDITION：ILLEGAL REQUEST / INVALID COMMAND OPERATION CODE */
    *sense_len = 18;
    memset(sense, 0, 18);
    sense[0] = 0x70; sense[2] = 0x05; sense[7] = 10; sense[12] = 0x20;
    return 1;
}

static int has_long_hex(const char *s) {
    int run = 0;
    for (; *s; s++) {
        if ((*s >= '0' && *s <= '9') || (*s >= 'a' && *s <= 'f') || (*s >= 'A' && *s <= 'F')) {
            if (++run >= 16) return 1;
        } else {
            run = 0;
        }
    }
    return 0;
}

static void mock_log(void *opaque, int level, const char *msg) {
    (void)opaque; (void)level;
    n_logs++;
    if (has_long_hex(msg) && !strstr(msg, "Disc ID:") && !strstr(msg, "config entry for discid")) {
        unredacted++;
        printf("    没打码的日志：%s\n", msg);
    }
    if (strlen(logs) + strlen(msg) + 2 < sizeof(logs)) {
        strcat(logs, msg);
        strcat(logs, "\n");
    }
}

static dc_host_api host = {DC_HOST_API_VERSION, NULL, mock_scsi, mock_log};

/* ---------------- 测试盘 ---------------- */

static char root[256];

static void mkdirs(const char *path) {
    char tmp[512];
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') { *p = 0; mkdir(tmp, 0700); *p = '/'; }
    }
    mkdir(tmp, 0700);
}

static void write_file(const char *path, const void *data, size_t len) {
    FILE *f = fopen(path, "wb");
    if (!f) { perror(path); exit(1); }
    fwrite(data, 1, len, f);
    fclose(f);
}

static void hex(char *out, const uint8_t *b, int n) {
    for (int i = 0; i < n; i++) sprintf(out + 2 * i, "%02x", b[i]);
}

static void aes_ecb(const uint8_t *key, const uint8_t *in, uint8_t *out) {
    gcry_cipher_hd_t h;
    gcry_cipher_open(&h, GCRY_CIPHER_AES, GCRY_CIPHER_MODE_ECB, 0);
    gcry_cipher_setkey(h, key, 16);
    gcry_cipher_encrypt(h, out, 16, in, 16);
    gcry_cipher_close(h);
}

static void keydb(const char *dir, const char *entry) {
    char path[512];
    snprintf(path, sizeof(path), "%s/aacs", dir);
    mkdirs(path);
    snprintf(path, sizeof(path), "%s/aacs/KEYDB.cfg", dir);
    write_file(path, entry, strlen(entry));
}

static int open_disc(AACS **out) {
    AACS *a = p_init();
    if (!a) return -100;
    int r = p_open_device(a, root, NULL);
    *out = a;
    return r;
}

int main(int argc, char **argv) {
    const char *lib = argc > 1 ? argv[1] : "libaacs.so";
    void *h = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    if (!h) { printf("打不开 %s：%s\n", lib, dlerror()); return 1; }
    p_init = (AACS * (*)(void)) dlsym(h, "aacs_init");
    p_open_device = (int (*)(AACS *, const char *, const char *))dlsym(h, "aacs_open_device");
    p_close = (void (*)(AACS *))dlsym(h, "aacs_close");
    p_decrypt_unit = (int (*)(AACS *, uint8_t *))dlsym(h, "aacs_decrypt_unit");
    p_disc_id = (const uint8_t *(*)(AACS *))dlsym(h, "aacs_get_disc_id");
    p_set_host = (int (*)(const dc_host_api *))dlsym(h, "aacs_set_host");
    CHECK(p_set_host != NULL, "组件导出 aacs_set_host");
    if (!p_set_host || !p_init || !p_open_device) return 1;
    gcry_check_version(NULL);

    snprintf(root, sizeof(root), "/tmp/aacs_host_test.%d", (int)getpid());
    char disc[300], cfg[300], cache[300], cache2[300], path[512];
    snprintf(disc, sizeof(disc), "%s/AACS", root);
    snprintf(cfg, sizeof(cfg), "%s-config", root);
    snprintf(cache, sizeof(cache), "%s-cache", root);
    snprintf(cache2, sizeof(cache2), "%s-cache2", root);
    mkdirs(disc);
    setenv("XDG_CONFIG_HOME", cfg, 1);
    setenv("XDG_CONFIG_DIRS", cfg, 1);
    setenv("XDG_CACHE_HOME", cache, 1);
    unsetenv("AACS_HOME");

    /* 密钥：VUK → 加密的单元密钥；Unit_Key_RO.inf 的 SHA-1 就是盘 ID */
    uint8_t vuk[16], uk[16], enc_uk[16], mk[16], vid[16];
    for (int i = 0; i < 16; i++) { vid[i] = (uint8_t)(0x11 * i + 3); uk[i] = (uint8_t)(0xA5 ^ (7 * i)); mk[i] = (uint8_t)(0x40 + i); }
    /* VUK = AES-128D(MK, VID) XOR VID（和真盘一样由媒体密钥和卷标识算出来） */
    gcry_cipher_hd_t vh;
    gcry_cipher_open(&vh, GCRY_CIPHER_AES, GCRY_CIPHER_MODE_ECB, 0);
    gcry_cipher_setkey(vh, mk, 16);
    gcry_cipher_decrypt(vh, vuk, 16, vid, 16);
    gcry_cipher_close(vh);
    for (int i = 0; i < 16; i++) vuk[i] ^= vid[i];
    aes_ecb(vuk, uk, enc_uk);
    uint8_t ukf[4096];             /* libaacs 只认 2 KB 以上的文件 */
    memset(ukf, 0, sizeof(ukf));
    ukf[3] = 64;                   /* 单元密钥块位置 */
    ukf[16] = 1;                   /* 应用类型：BDMV */
    ukf[17] = 1;                   /* 1 个 BDMV 目录 */
    ukf[65] = 1;                   /* 1 个 CPS 单元 */
    memcpy(ukf + 64 + 48, enc_uk, 16);
    snprintf(path, sizeof(path), "%s/Unit_Key_RO.inf", disc);
    write_file(path, ukf, sizeof(ukf));
    /* CPS 单元 1 的使用规则：加密的（增强标题使用规则）。没有这个文件 libaacs 会当成不加密 */
    uint8_t cci[2048];
    memset(cci, 0, sizeof(cci));
    cci[1] = 1;                    /* 1 条 */
    cci[16] = 0x01; cci[17] = 0x11; /* 类型 0111h */
    cci[18] = 0x01;                /* 版本 0100h，数据长度 0 */
    snprintf(path, sizeof(path), "%s/CPSUnit00001.cci", disc);
    write_file(path, cci, sizeof(cci));
    uint8_t disc_id[20];
    gcry_md_hash_buffer(GCRY_MD_SHA1, disc_id, ukf, sizeof(ukf));
    char id_hex[41], vuk_hex[33], mk_hex[33], vid_hex[33], entry[512];
    hex(id_hex, disc_id, 20);
    hex(vuk_hex, vuk, 16);
    hex(mk_hex, mk, 16);
    hex(vid_hex, vid, 16);

    /* 一个加密的单元：32 个 192 字节的源包（第 4 字节是 TS 同步字 0x47），第一个字节标了"加密" */
    uint8_t plain[6144], unit[6144];
    for (int i = 0; i < 6144; i++) plain[i] = (uint8_t)(i * 7 + 1);
    for (int i = 0; i < 6144; i += 192) { plain[i] = 0; plain[i + 4] = 0x47; }
    plain[0] = 0xC0;
    memcpy(unit, plain, sizeof(unit));
    uint8_t key[16];
    aes_ecb(uk, plain, key);
    for (int i = 0; i < 16; i++) key[i] ^= plain[i];
    static const uint8_t iv[16] = {0x0b, 0xa0, 0xf8, 0xdd, 0xfe, 0xa6, 0x1f, 0xb3, 0xd8, 0xdf, 0x9f, 0x56, 0x6a, 0x05, 0x0f, 0x78};
    gcry_cipher_hd_t ch;
    gcry_cipher_open(&ch, GCRY_CIPHER_AES, GCRY_CIPHER_MODE_CBC, 0);
    gcry_cipher_setkey(ch, key, 16);
    gcry_cipher_setiv(ch, iv, 16);
    gcry_cipher_encrypt(ch, unit + 16, sizeof(unit) - 16, plain + 16, sizeof(plain) - 16);
    gcry_cipher_close(ch);

    CHECK(p_set_host(&host) == DC_HOST_API_VERSION, "注册宿主接口（版本 %d）", DC_HOST_API_VERSION);

    /* ---- 1：KEYDB 里有 VUK ---- */
    snprintf(entry, sizeof(entry), "; test\n0x%s = Test Disc | D | 2026-11-11 | V | 0x%s\n", id_hex, vuk_hex);
    keydb(cfg, entry);
    AACS *a = NULL;
    int r = open_disc(&a);
    CHECK(r == 0, "KEYDB 里有 VUK：打开成功（%d）", r);
    if (r != 0) printf("组件日志：\n%s", logs);
    CHECK(n_cmds == 0, "没有向光驱发命令（%d 条）", n_cmds);
    const uint8_t *got_id = a && p_disc_id ? p_disc_id(a) : NULL;
    CHECK(got_id && memcmp(got_id, disc_id, 20) == 0, "盘 ID = Unit_Key_RO.inf 的 SHA-1");
    uint8_t buf[6144];
    memcpy(buf, unit, sizeof(buf));
    int d = a ? p_decrypt_unit(a, buf) : 0;
    CHECK(d == 1 && memcmp(buf + 16, plain + 16, sizeof(buf) - 16) == 0, "解密出原来的单元（%d）", d);
    if (a) p_close(a);

    /* ---- 2：媒体密钥 + 卷标识算出 VUK，缓存在 XDG_CACHE_HOME（App 的缓存目录） ---- */
    snprintf(entry, sizeof(entry), "0x%s = Test Disc | M | 0x%s | I | 0x%s\n", id_hex, mk_hex, vid_hex);
    keydb(cfg, entry);
    r = open_disc(&a);
    CHECK(r == 0 && n_cmds == 0, "KEYDB 里有媒体密钥和卷标识：算出 VUK（%d）", r);
    memcpy(buf, unit, sizeof(buf));
    CHECK(a && p_decrypt_unit(a, buf) == 1 && memcmp(buf + 16, plain + 16, sizeof(buf) - 16) == 0, "用算出的 VUK 解密");
    if (a) p_close(a);
    snprintf(path, sizeof(path), "%s/aacs/vuk/%s", cache, id_hex);
    struct stat st;
    CHECK(stat(path, &st) == 0, "算出的 VUK 缓存在 App 的缓存目录（…/aacs/vuk/<盘 ID>）");
    snprintf(entry, sizeof(entry), "0x%s = Test Disc | D | 2026-11-11\n", id_hex);
    keydb(cfg, entry);
    r = open_disc(&a);
    CHECK(r == 0 && n_cmds == 0, "KEYDB 里没有密钥：用缓存打开（%d）", r);
    memcpy(buf, unit, sizeof(buf));
    CHECK(a && p_decrypt_unit(a, buf) == 1 && memcmp(buf + 16, plain + 16, sizeof(buf) - 16) == 0, "用缓存的 VUK 解密");
    if (a) p_close(a);

    /* ---- 3：只有媒体密钥，要向光驱读 Volume ID ---- */
    setenv("XDG_CACHE_HOME", cache2, 1);
    snprintf(entry, sizeof(entry), "0x%s = Test Disc | M | 0x%s\n", id_hex, mk_hex);
    keydb(cfg, entry);
    n_cmds = 0;
    logs[0] = 0;
    r = open_disc(&a);
    CHECK(r == -4, "没有主机证书：AACS_ERROR_NO_CERT（%d）", r);
    if (r != -4) printf("组件日志：\n%s", logs);
    CHECK(n_getconf >= 1, "发了 GET CONFIGURATION 查 AACS 功能（%d 次）", n_getconf);
    CHECK(n_drive_cert >= 1, "读了光驱证书（REPORT KEY 38h，%d 次）", n_drive_cert);
    CHECK(n_send_key == 0, "没有证书就不发 SEND KEY");
    CHECK(bad_cdb == 0, "命令格式都对（错 %d 处）", bad_cdb);
    if (a) p_close(a);

    /* ---- 4：光驱不支持 AACS ---- */
    no_aacs = 1;
    n_cmds = 0;
    logs[0] = 0;
    r = open_disc(&a);
    CHECK(r == -6, "光驱不支持 AACS：AACS_ERROR_MMC_OPEN（%d）", r);
    CHECK(strstr(logs, "sense 5/20/00") != NULL, "日志里有 sense（5/20/00）");
    if (a) p_close(a);
    no_aacs = 0;

    /* ---- 5：没有宿主 ---- */
    p_set_host(NULL);
    n_cmds = 0;
    r = open_disc(&a);
    CHECK(r == -6 && n_cmds == 0, "没注册宿主：不发命令，AACS_ERROR_MMC_OPEN（%d）", r);
    if (a) p_close(a);

    /* ---- 6：日志 ---- */
    CHECK(n_logs > 10, "组件日志转给了宿主（%d 条）", n_logs);
    CHECK(unredacted == 0, "密钥 / 证书 / 命令数据都打了码（漏了 %d 条）", unredacted);
    p_set_host(&host);
    logs[0] = 0;
    setenv("XDG_CACHE_HOME", cache, 1);
    r = open_disc(&a);
    CHECK(strstr(logs, id_hex) != NULL, "日志保留盘 ID（查 KEYDB 用）");
    CHECK(strstr(logs, vuk_hex) == NULL, "日志里没有 VUK");
    if (a) p_close(a);

    char cmd[1400];
    snprintf(cmd, sizeof(cmd), "rm -rf '%s' '%s' '%s' '%s'", root, cfg, cache, cache2);
    if (system(cmd) != 0) printf("清理临时目录失败\n");
    printf(fails ? "\n有 %d 项失败\n" : "\n全部通过\n", fails);
    return fails ? 1 : 0;
}
