#include "dp_spu.h"

#include <stdlib.h>
#include <string.h>

#include "dp_core.h"

void dp_spu_asm_reset(dp_spu_asm *a) {
    a->size = 0;
    a->filled = 0;
    a->pts = DP_NOPTS;
}

void dp_spu_asm_free(dp_spu_asm *a) {
    free(a->buf);
    a->buf = NULL;
    dp_spu_asm_reset(a);
}

int dp_spu_feed(dp_spu_asm *a, const uint8_t *data, int len, int64_t pts, dp_spu_image *out) {
    if (len <= 0) return 0;
    if (a->size == 0) {
        /* 新单元：前两个字节是总长 */
        if (len < 2) return -1;
        int size = (data[0] << 8) | data[1];
        if (size < 4) return -1;
        uint8_t *nb = realloc(a->buf, (size_t)size);
        if (!nb) return -1;
        a->buf = nb;
        a->size = size;
        a->filled = 0;
        a->pts = pts;
    }
    int n = len < a->size - a->filled ? len : a->size - a->filled;
    memcpy(a->buf + a->filled, data, (size_t)n);
    a->filled += n;
    if (a->filled < a->size) return 0;
    int r = dp_spu_decode(a->buf, a->size, a->pts, out);
    dp_spu_asm_reset(a);
    return r == 0 ? 1 : -1;
}

/* 半字节读取器 */
typedef struct {
    const uint8_t *p;
    int end;   /* 字节上限（不含） */
    int pos;   /* 半字节位置 */
} nib_reader;

static int nib(nib_reader *r) {
    int byte = r->pos >> 1;
    if (byte >= r->end) return 0;
    int v = (r->pos & 1) ? (r->p[byte] & 0x0F) : (r->p[byte] >> 4);
    r->pos++;
    return v;
}

/* 解一行的游程编码，写入 line（w 个像素）。 */
static void decode_line(nib_reader *r, uint8_t *line, int w) {
    int x = 0;
    while (x < w) {
        int code = nib(r);
        if (code < 0x4) {
            code = (code << 4) | nib(r);
            if (code < 0x10) {
                code = (code << 4) | nib(r);
                if (code < 0x40) code = (code << 4) | nib(r);
            }
        }
        int run = code >> 2;
        int color = code & 3;
        if (run == 0) run = w - x;  /* 到行尾 */
        if (run > w - x) run = w - x;
        memset(line + x, color, (size_t)run);
        x += run;
    }
    /* 每行结束对齐到字节 */
    if (r->pos & 1) r->pos++;
}

int dp_spu_decode(const uint8_t *u, int size, int64_t pts, dp_spu_image *out) {
    memset(out, 0, sizeof(*out));
    out->start = pts;
    out->end = DP_NOPTS;
    if (size < 4) return -1;
    int dcsq = (u[2] << 8) | u[3];
    int top = -1, bottom = -1;
    int x1 = 0, x2 = -1, y1 = 0, y2 = -1;
    bool have_start = false;
    int guard = 0;
    while (dcsq + 4 <= size && guard++ < 64) {
        int delay = (u[dcsq] << 8) | u[dcsq + 1];
        int next = (u[dcsq + 2] << 8) | u[dcsq + 3];
        int64_t t = pts == DP_NOPTS ? DP_NOPTS : pts + (int64_t)delay * 1024;
        int p = dcsq + 4;
        bool end = false;
        while (p < size && !end) {
            switch (u[p++]) {
                case 0x00: /* 强制开始显示 */
                    out->forced = true;
                    /* fall through */
                case 0x01: /* 开始显示 */
                    if (!have_start) {
                        out->start = t;
                        have_start = true;
                    }
                    break;
                case 0x02: /* 停止显示 */
                    out->end = t;
                    break;
                case 0x03: /* 颜色：e2 e1 p b */
                    if (p + 2 > size) return -1;
                    out->color[3] = u[p] >> 4;
                    out->color[2] = u[p] & 0x0F;
                    out->color[1] = u[p + 1] >> 4;
                    out->color[0] = u[p + 1] & 0x0F;
                    p += 2;
                    break;
                case 0x04: /* 对比度 */
                    if (p + 2 > size) return -1;
                    out->alpha[3] = u[p] >> 4;
                    out->alpha[2] = u[p] & 0x0F;
                    out->alpha[1] = u[p + 1] >> 4;
                    out->alpha[0] = u[p + 1] & 0x0F;
                    p += 2;
                    break;
                case 0x05: /* 显示区域 */
                    if (p + 6 > size) return -1;
                    x1 = (u[p] << 4) | (u[p + 1] >> 4);
                    x2 = ((u[p + 1] & 0x0F) << 8) | u[p + 2];
                    y1 = (u[p + 3] << 4) | (u[p + 4] >> 4);
                    y2 = ((u[p + 4] & 0x0F) << 8) | u[p + 5];
                    p += 6;
                    break;
                case 0x06: /* 两场像素数据的起点 */
                    if (p + 4 > size) return -1;
                    top = (u[p] << 8) | u[p + 1];
                    bottom = (u[p + 2] << 8) | u[p + 3];
                    p += 4;
                    break;
                case 0x07: { /* 改变颜色 / 对比度（很少见）：跳过 */
                    if (p + 2 > size) return -1;
                    int len = (u[p] << 8) | u[p + 1];
                    p += len;
                    break;
                }
                case 0xFF:
                    end = true;
                    break;
                default:
                    end = true;  /* 不认识的命令：这个序列到此为止 */
                    break;
            }
        }
        if (next == dcsq || next < 4) break;
        dcsq = next;
    }
    if (top < 0 || bottom < 0 || x2 < x1 || y2 < y1) return -1;
    out->x = x1;
    out->y = y1;
    out->w = x2 - x1 + 1;
    out->h = y2 - y1 + 1;
    if (out->w > 1920 || out->h > 1088) return -1;
    out->idx = calloc((size_t)out->w * (size_t)out->h, 1);
    if (!out->idx) return -1;
    int data_end = (u[2] << 8) | u[3];
    if (data_end > size) data_end = size;
    nib_reader rt = {u, data_end, top * 2};
    nib_reader rb = {u, data_end, bottom * 2};
    for (int y = 0; y < out->h; y++) {
        decode_line((y & 1) ? &rb : &rt, out->idx + (size_t)y * (size_t)out->w, out->w);
    }
    return 0;
}

static uint8_t clamp8(int v) { return (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v); }

uint32_t dp_ycrcb_to_rgba(uint32_t e) {
    int y = (int)((e >> 16) & 0xFF);
    int cr = (int)((e >> 8) & 0xFF);
    int cb = (int)(e & 0xFF);
    /* BT.601 有限范围 */
    int c = y - 16, d = cb - 128, f = cr - 128;
    int r = (298 * c + 409 * f + 128) >> 8;
    int g = (298 * c - 100 * d - 208 * f + 128) >> 8;
    int b = (298 * c + 516 * d + 128) >> 8;
    /* 内存顺序 R G B A（小端读成 uint32 是 0xAABBGGRR） */
    return (uint32_t)clamp8(r) | ((uint32_t)clamp8(g) << 8) | ((uint32_t)clamp8(b) << 16) | 0xFF000000u;
}

void dp_spu_render(const dp_spu_image *img, const uint32_t clut[16], const dp_highlight *hl, uint32_t *rgba, int fw, int fh) {
    if (!img || !img->idx) return;
    bool use_hl = hl && hl->display;
    for (int y = 0; y < img->h; y++) {
        int fy = img->y + y;
        if (fy < 0 || fy >= fh) continue;
        const uint8_t *row = img->idx + (size_t)y * (size_t)img->w;
        uint32_t *dst = rgba + (size_t)fy * (size_t)fw;
        for (int x = 0; x < img->w; x++) {
            int fx = img->x + x;
            if (fx < 0 || fx >= fw) continue;
            int i = row[x];
            int ci, ai;
            if (use_hl && fx >= hl->sx && fx <= hl->ex && fy >= hl->sy && fy <= hl->ey) {
                ci = hl->color[i];
                ai = hl->alpha[i];
            } else {
                ci = img->color[i];
                ai = img->alpha[i];
            }
            if (ai == 0) continue;
            uint32_t c = clut[ci & 0x0F] & 0x00FFFFFFu;
            dst[fx] = c | ((uint32_t)(ai * 17) << 24);
        }
    }
}
