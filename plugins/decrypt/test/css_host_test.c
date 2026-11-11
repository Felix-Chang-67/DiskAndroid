/*
 * 打过补丁的 libdvdcss 通过宿主回调发认证命令：用模拟光驱检查命令格式（MMC REPORT KEY / SEND KEY /
 * READ DVD STRUCTURE、KEY CLASS 00h）、认证流程的顺序，以及永远不会发 SEND KEY 06h（改地区码）。
 * 模拟光驱不会算 CSS 的 KEY1，所以认证会在"找不到 variant"那一步失败——这里只验证命令通路。
 */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dc_host.h"

typedef struct dvdcss_s *dvdcss_t;
typedef struct {
    int (*pf_seek)(void *, uint64_t);
    int (*pf_read)(void *, void *, int);
    int (*pf_readv)(void *, const void *, int);
} stream_cb;

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("✗ "); printf(__VA_ARGS__); printf("\n"); } else { printf("✓ "); printf(__VA_ARGS__); printf("\n"); } } while (0)

static int n_cmds, n_rpc_set, saw_agid, saw_key1, saw_copyright, saw_rpc_state, n_logs;
static char order[64];

static int mock_scsi(void *opaque, const uint8_t *cdb, int cdb_len, int dir, uint8_t *buf, int len, uint8_t *sense, int *sense_len, int timeout) {
    (void)opaque; (void)timeout;
    n_cmds++;
    if (cdb_len != 12) { printf("CDB 长度不对：%d\n", cdb_len); fails++; }
    int alloc = (cdb[8] << 8) | cdb[9];
    if (alloc != len) { printf("分配长度 %d 和缓冲区 %d 不一致（操作码 %02X）\n", alloc, len, cdb[0]); fails++; }
    int fmt = cdb[10] & 0x3f, agid = cdb[10] >> 6;
    if (cdb[0] == 0xA3) {
        if (cdb[7] != 0x00) fails++;
        if (fmt == 0x06) n_rpc_set++;
        if (dir != DC_DIR_OUT) { printf("SEND KEY 方向不对\n"); fails++; }
        if (fmt == 0x01) { strcat(order, "C"); if (len != 16 || buf[1] != 0x0e) { printf("SEND KEY challenge 格式不对\n"); fails++; } }
        return 0;
    }
    if (cdb[0] == 0xA4) {
        if (cdb[7] != 0x00) { printf("KEY CLASS 应为 00h\n"); fails++; }
        if (dir != (len ? DC_DIR_IN : DC_DIR_NONE)) { printf("REPORT KEY 方向不对（%d）\n", dir); fails++; }
        memset(buf, 0, len);
        switch (fmt) {
            case 0x00: saw_agid++; strcat(order, "A"); if (len != 8) fails++; buf[7] = 1 << 6; return 0;   /* AGID 1 */
            case 0x02: saw_key1++; strcat(order, "K"); if (len != 12 || agid != 1) { printf("KEY1 请求不对\n"); fails++; } return 0;
            case 0x08: saw_rpc_state++; buf[4] = 0x40 | 5; buf[5] = 0xFD; buf[6] = 1; return 0;           /* RPC-II，地区 2 */
            case 0x3F: strcat(order, "I"); return 0;
            default: break;
        }
        *sense_len = 18;
        memset(sense, 0, 18);
        sense[0] = 0x70; sense[2] = 0x05; sense[12] = 0x24;
        return 1;
    }
    if (cdb[0] == 0xAD) {
        memset(buf, 0, len);
        if (cdb[7] == 0x01) { saw_copyright++; if (len != 8) fails++; buf[4] = 1; buf[5] = 0xFD; return 0; }   /* CSS，地区 2 */
        return -1;
    }
    printf("意外的操作码 %02X\n", cdb[0]);
    fails++;
    return -1;
}

static void mock_log(void *opaque, int level, const char *msg) {
    (void)opaque; (void)level;
    n_logs++;
    if (n_logs <= 40) printf("  [libdvdcss] %s\n", msg);
}

static uint8_t image[2048 * 64];
static uint64_t pos;
static int s_seek(void *p, uint64_t at) { (void)p; pos = at; return 0; }
static int s_read(void *p, void *buf, int n) { (void)p; if (pos + n > sizeof(image)) n = (int)(sizeof(image) - pos); memcpy(buf, image + pos, n); pos += n; return n; }

int main(int argc, char **argv) {
    void *lib = dlopen(argc > 1 ? argv[1] : "libdvdcss.so.2", RTLD_NOW);
    if (!lib) { printf("✗ 载入失败：%s\n", dlerror()); return 1; }
    int (*set_host)(const dc_host_api *) = (int (*)(const dc_host_api *))dlsym(lib, "dvdcss_set_host");
    dvdcss_t (*open_stream)(void *, stream_cb *) = (dvdcss_t (*)(void *, stream_cb *))dlsym(lib, "dvdcss_open_stream");
    int (*close_)(dvdcss_t) = (int (*)(dvdcss_t))dlsym(lib, "dvdcss_close");
    CHECK(set_host && open_stream && close_, "导出了 dvdcss_set_host / dvdcss_open_stream");
    if (!set_host) return 1;
    dc_host_api host = {DC_HOST_API_VERSION, NULL, mock_scsi, mock_log};
    CHECK(set_host(&host) == DC_HOST_API_VERSION, "宿主接口版本 %d", DC_HOST_API_VERSION);
    setenv("DVDCSS_CACHE", "off", 1);
    stream_cb cb = {s_seek, s_read, NULL};
    static int dummy;
    dvdcss_t d = open_stream(&dummy, &cb);
    CHECK(d != NULL, "流模式打开成功（有宿主时走认证）");
    CHECK(saw_copyright > 0, "读了版权信息（READ DVD STRUCTURE 01h）");
    CHECK(saw_rpc_state > 0, "读了 RPC 状态（REPORT KEY 08h）");
    CHECK(saw_agid > 0, "申请了 AGID（REPORT KEY 00h）");
    CHECK(strstr(order, "AC") != NULL, "AGID 之后发主机 Challenge（SEND KEY 01h，16 字节，长度字段 0Eh）：顺序 %s", order);
    CHECK(saw_key1 > 0, "取了 KEY1（REPORT KEY 02h，带 AGID）");
    CHECK(n_rpc_set == 0, "从来没有发 SEND KEY 06h（改地区码）");
    CHECK(n_logs > 0, "libdvdcss 的日志转给了宿主（%d 条）", n_logs);
    if (d) close_(d);
    printf(fails ? "\n有 %d 项失败\n" : "\n全部通过\n", fails);
    return fails ? 1 : 0;
}
