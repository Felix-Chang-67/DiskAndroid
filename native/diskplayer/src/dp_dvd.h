/*
 * DVD 播放器核心：libdvdnav 负责导航（首播、菜单、按钮、跳转、静止画面），这里负责解复用、解码（FFmpeg）、
 * 字幕 / 菜单叠加、音画同步。所有 dvdnav 调用都在导航线程里执行，界面的操作通过命令队列送过去。
 */
#pragma once

#include "dp_core.h"

typedef struct dp_dvd dp_dvd;

typedef struct {
    int region_mask;          /* dvdnav 的地区掩码：第 n 位 = 地区 n+1 */
    char menu_lang[3];        /* "ja" */
    char audio_lang[3];
    char spu_lang[3];
    int start_title;          /* 0 = 从光盘自己的首播开始（片头、菜单）；> 0 = 直接播放这个标题 */
    bool deinterlace;
} dp_dvd_options;

enum {
    DP_DOMAIN_STOP = 0,
    DP_DOMAIN_FIRST_PLAY,
    DP_DOMAIN_VMG_MENU,
    DP_DOMAIN_VTS_MENU,
    DP_DOMAIN_TITLE,
};

typedef struct {
    int domain;
    int title, chapter, titles, chapters;
    int64_t position_ms, duration_ms;
    int buttons, button;
    bool still, paused, ended;
    int audio_count, audio_current;      /* 逻辑音轨号，-1 = 无 */
    int audio_code;                      /* 正在解码的流（0x80 AC-3、0xA0 LPCM…），-1 = 无 */
    char audio_desc[8][24];              /* "ja AC-3 2ch" */
    int spu_count, spu_current;          /* 逻辑字幕号 */
    bool spu_shown;
    char spu_desc[32][8];                /* "ja" */
    int angles, angle;
    int video_w, video_h;
    bool wide;
    char volume[40];
    uint64_t bytes_read, read_errors, frames_shown, frames_dropped, audio_frames;
    int64_t clock_pts;
    int video_codec_id, audio_codec_id;  /* 正在用的解码器（FFmpeg AVCodecID） */
} dp_dvd_state;

enum {
    DP_CMD_UP = 1,
    DP_CMD_DOWN,
    DP_CMD_LEFT,
    DP_CMD_RIGHT,
    DP_CMD_ACTIVATE,
    DP_CMD_MOUSE_SELECT,     /* arg1 = x，arg2 = y（视频帧坐标） */
    DP_CMD_MOUSE_ACTIVATE,
    DP_CMD_MENU,             /* arg1 = DVDMenuID_t（2 标题菜单，3 根菜单…） */
    DP_CMD_TITLE_PLAY,       /* arg1 = 标题 */
    DP_CMD_PART_PLAY,        /* arg1 = 标题，arg2 = 章节 */
    DP_CMD_NEXT_CHAPTER,
    DP_CMD_PREV_CHAPTER,
    DP_CMD_SEEK_MS,          /* arg1 = 标题内的毫秒 */
    DP_CMD_AUDIO,            /* arg1 = 逻辑音轨号 */
    DP_CMD_SPU,              /* arg1 = 逻辑字幕号，-1 = 关 */
    DP_CMD_ANGLE,            /* arg1 = 角度（1 起） */
    DP_CMD_PAUSE,
    DP_CMD_RESUME,
    DP_CMD_GO_UP,            /* 返回上一级菜单 */
    DP_CMD_STILL_SKIP,
};

/* 打开（读 IFO、准备 dvdnav）；出错返回 NULL，原因写进 err。 */
dp_dvd *dp_dvd_open(dp_source *src, const dp_platform *pf, const dp_dvd_options *opt, char *err, int errlen);
/* 启动导航 / 视频 / 音频三个线程。 */
int dp_dvd_start(dp_dvd *d);
/* 发命令（任何线程都可以调用，不阻塞）。 */
void dp_dvd_command(dp_dvd *d, int cmd, int arg1, int arg2);
void dp_dvd_get_state(dp_dvd *d, dp_dvd_state *out);
/* 停止线程并释放。 */
void dp_dvd_close(dp_dvd *d);
/* 界面尺寸变化等：要求视频线程把最后一帧再画一次。 */
void dp_dvd_refresh(dp_dvd *d);
