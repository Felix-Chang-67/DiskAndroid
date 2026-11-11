/*
 * 蓝光播放器核心：libbluray 负责导航（光盘程序：First Play、顶层菜单 / 弹出菜单、标题、播放列表，HDMV 虚拟机），
 * 按钮菜单（IG）和字幕（PG）由 libbluray 解码后经叠加层回调交给这里合成；这里负责 M2TS 解复用和送去解码（dp_av）。
 * BD-J（Java 写的菜单）在 Android 上没有 Java SE 运行环境，做不了：遇到时直接播放主片。
 * 所有 libbluray 调用都在导航线程里执行，界面的操作通过命令队列送过去。
 */
#pragma once

#include "dp_core.h"
#include "dp_dvd.h" /* 命令编号 DP_CMD_* 和 DVD 共用 */

typedef struct dp_bd dp_bd;

typedef struct {
    char audio_lang[4];       /* ISO 639-2："jpn" */
    char pg_lang[4];          /* 字幕："chi" */
    char menu_lang[4];
    int region;               /* 播放器地区：A = 1、B = 2、C = 4 */
    int start_playlist;       /* -1 = 用光盘菜单（导航模式）；>= 0 = 直接播放这个播放列表（xxxxx.mpls） */
    bool deinterlace;
} dp_bd_options;

enum {
    DP_BD_MODE_STOP = 0,
    DP_BD_MODE_MENU,          /* 导航模式：光盘程序在控制（菜单、标题） */
    DP_BD_MODE_PLAYLIST,      /* 直接播放某个播放列表（没有菜单） */
};

typedef struct {
    int mode;
    int title, titles;                 /* HDMV 标题号（0 = 顶层菜单，0xFFFF = First Play），标题数 */
    int playlist, playitem, chapter, chapters;
    int64_t position_ms, duration_ms;
    bool menu;                         /* 正在显示交互菜单（按钮） */
    bool popup;                        /* 有弹出菜单可用 */
    bool still, paused, ended;
    int audio_count, audio_current;    /* 当前音轨（0 起），-1 = 无 */
    int audio_codec;                   /* 正在解码的音频（FFmpeg AVCodecID），0 = 无 */
    char audio_desc[32][24];           /* "jpn LPCM 2ch" */
    int pg_count, pg_current;          /* 字幕（0 起），-1 = 无 */
    bool pg_shown;
    char pg_desc[32][8];
    int angles, angle;
    int video_w, video_h;
    int plane_w, plane_h;              /* 菜单 / 字幕图层的尺寸（一般 1920×1080） */
    bool bdj;                          /* 光盘菜单是 BD-J，没法用：在直接播放主片 */
    bool aacs;                         /* 有 AACS 加密 */
    uint64_t bytes_read, read_errors, frames_shown, frames_dropped, audio_frames;
    int video_codec_id, audio_codec_id;  /* 正在用的解码器（FFmpeg AVCodecID） */
    double video_fps;                    /* 视频实际的显示帧率，0 = 还不知道（界面据此挑屏幕刷新率） */
    char error[160];
} dp_bd_state;

/* 打开蓝光（读 index.bdmv / 播放列表，检查加密）；出错返回 NULL，原因写进 err。 */
dp_bd *dp_bd_open(dp_source *src, const dp_platform *pf, const dp_bd_options *opt, char *err, int errlen);
int dp_bd_start(dp_bd *d);
void dp_bd_command(dp_bd *d, int cmd, int arg1, int arg2);
void dp_bd_get_state(dp_bd *d, dp_bd_state *out);
void dp_bd_refresh(dp_bd *d);
void dp_bd_close(dp_bd *d);
