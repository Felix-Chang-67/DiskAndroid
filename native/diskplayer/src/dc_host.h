/*
 * DiskAndroid 解密组件的宿主接口（ABI，C）。
 *
 * 解密组件（打过补丁的 libdvdcss / libaacs）不随 App 分发，由用户导入。光驱接在 USB 上，没有内核驱动，
 * 组件不能自己打开设备发认证命令，所以由 App 提供"发一条 SCSI 命令"的回调：命令经 App 的光驱会话发出，
 * 照样经过只读守卫（只放行认证需要的 REPORT KEY / SEND KEY / READ DISC STRUCTURE 格式，设置地区码永远发不出去）
 * 和命令日志（CDB、结果、sense 原文；密钥类载荷不写进日志）。
 *
 * 组件导出 `dvdcss_set_host`（libdvdcss）或 `aacs_set_host`（libaacs）接收这个结构，返回它支持的版本号。
 * 这个文件在 App 和组件的编译里各有一份，改动时两边同时改并升版本号。
 */
#ifndef DC_HOST_H
#define DC_HOST_H

#include <stdint.h>

#define DC_HOST_API_VERSION 1

enum {
    DC_DIR_NONE = 0,   /* 没有数据阶段 */
    DC_DIR_IN = 1,     /* 光驱 → 主机 */
    DC_DIR_OUT = 2,    /* 主机 → 光驱 */
};

enum {
    DC_LOG_DEBUG = 0,
    DC_LOG_INFO = 1,
    DC_LOG_WARN = 2,
    DC_LOG_ERROR = 3,
};

typedef struct dc_host_api {
    int version;   /* DC_HOST_API_VERSION */
    void *opaque;
    /*
     * 发一条 SCSI 命令（阻塞）。
     * 返回 0 = GOOD；> 0 = CHECK CONDITION（sense 写进 sense，*sense_len = 长度）；
     * < 0 = 没发成：光驱已断开、传输错误、被只读守卫拒绝。
     */
    int (*scsi)(void *opaque, const uint8_t *cdb, int cdb_len, int dir, uint8_t *buf, int buf_len,
                uint8_t *sense, int *sense_len, int timeout_ms);
    /* 写日志（UTF-8，一行）。 */
    void (*log)(void *opaque, int level, const char *msg);
} dc_host_api;

#endif
