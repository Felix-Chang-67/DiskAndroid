/*
 * DVD 子图像（SPU）：字幕和菜单按钮都是它。格式见 DVD-Video 规范的 Sub-picture 一节
 * （实现参照 VLC 的 modules/codec/spudec 与 MPlayer 的 spudec.c 的做法）：
 *   单元头 2 字节总长 + 2 字节控制序列偏移；像素数据是按两场（奇偶行）交错的游程编码，每像素 2 位（0 背景、1 图案、2 强调 1、3 强调 2）；
 *   控制序列给出显示时间、颜色（指向 16 色调色板）、对比度（透明度）、显示区域、两场数据的起点。
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

typedef struct {
    uint8_t *buf;
    int size;    /* 单元总长（前两个字节） */
    int filled;
    int64_t pts;
} dp_spu_asm;

typedef struct {
    int x, y, w, h;
    uint8_t *idx;          /* w*h，每像素 0..3 */
    uint8_t color[4];      /* 调色板序号 */
    uint8_t alpha[4];      /* 0..15 */
    int64_t start, end;    /* 90 kHz；end = DP_NOPTS 表示显示到下一张 */
    bool forced;           /* 强制显示（菜单按钮、强制字幕） */
} dp_spu_image;

/* 菜单按钮高亮（dvdnav 的 HIGHLIGHT 事件）。 */
typedef struct {
    bool display;
    int sx, sy, ex, ey;
    uint8_t color[4];
    uint8_t alpha[4];
    int button;
} dp_highlight;

void dp_spu_asm_reset(dp_spu_asm *a);
void dp_spu_asm_free(dp_spu_asm *a);
/*
 * 喂一个 PES 包的 SPU 数据（已去掉子流号）。拼成一个完整单元并解码成功时返回 1（结果写入 out，
 * out->idx 由调用者 free）；还没拼完返回 0；数据有错返回 -1（丢弃当前单元）。
 */
int dp_spu_feed(dp_spu_asm *a, const uint8_t *data, int len, int64_t pts, dp_spu_image *out);
int dp_spu_decode(const uint8_t *unit, int size, int64_t pts, dp_spu_image *out);

/* 调色板：DVD 的 16 色（0x00YYCrCb）→ RGBA（不透明）。 */
uint32_t dp_ycrcb_to_rgba(uint32_t ycrcb);

/*
 * 把子图像画进整帧大小的 RGBA 叠加层（frame_w × frame_h，已清零）。
 * hl 不为 NULL 且 display 时，高亮矩形里的像素用高亮的颜色 / 透明度（菜单当前选中的按钮）。
 */
void dp_spu_render(const dp_spu_image *img, const uint32_t clut[16], const dp_highlight *hl, uint32_t *rgba, int frame_w, int frame_h);
