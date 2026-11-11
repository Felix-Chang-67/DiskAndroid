/*
 * 视频帧时间轴（dp_av.c 的 v_timeline / v_update_fps）的测试，用模拟的解码帧：
 *   1. 软 3:2 下拉的电影（序列 29.97 帧/秒，帧交替多显示一场），一个 GOP（12 帧）只有一个时间戳：
 *      显示间隔应当均匀（约 41.7 ms），不能出现"播快了再停住"的长间隔；和真实时刻的差不超过半场多一点；
 *      告诉系统的帧率是 23.976
 *   2. 隔行的 29.97 帧/秒视频（去隔行后每场一帧）：间隔 33.4 ms，帧率 59.94
 *   3. 时间戳不连续（跳转、剪辑点）：以流里的时间戳为准
 * 直接包含 dp_av.c，测它的静态函数。
 */
#include "../src/dp_av.c"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("✗ "); } else { printf("✓ "); } printf(__VA_ARGS__); printf("\n"); } while (0)

int main(void) {
    vstate v;
    memset(&v, 0, sizeof(v));
    v.ctx = avcodec_alloc_context3(NULL);
    v.ctx->framerate = (AVRational){30000, 1001};
    v_timeline_reset(&v);
    AVFrame *f = av_frame_alloc();

    /* ---- 1：软下拉电影 ---- */
    double fields = 0;
    const int64_t start = 90000;
    int64_t prev = -1, max_gap = 0, min_gap = INT64_MAX, max_err = 0, old_max_gap = 0;
    int64_t old_prev = -1, old_last = AV_NOPTS_VALUE;
    for (int k = 0; k < 240; k++) {
        int rp = (k % 2 == 0) ? 1 : 0;
        int64_t truth = start + llround(fields * 1501.5);
        f->repeat_pict = rp;
        f->flags = 0;
        f->pts = (k % 12 == 0) ? truth : AV_NOPTS_VALUE;
        f->best_effort_timestamp = f->pts;
        /* 以前的做法：没有时间戳的帧 = 上一帧 + 1/29.97 秒 */
        int64_t old = f->pts != AV_NOPTS_VALUE ? f->pts : old_last + 3003;
        if (old_prev >= 0 && old - old_prev > old_max_gap) old_max_gap = old - old_prev;
        old_prev = old_last = old;
        double avg = 0;
        int64_t p = v_timeline(&v, f, &avg);
        v_update_fps(&v, avg, false);
        if (k >= 8) {
            int64_t gap = p - prev;
            if (gap > max_gap) max_gap = gap;
            if (gap < min_gap) min_gap = gap;
            int64_t err = llabs(p - truth);
            if (err > max_err) max_err = err;
        }
        prev = p;
        fields += 2 + rp;
    }
    printf("  （以前的做法：最长间隔 %.1f ms）\n", old_max_gap / 90.0);
    CHECK(max_gap <= 3790 && min_gap >= 3720, "电影的显示间隔均匀：%.1f–%.1f ms（以前最长 %.1f ms）", min_gap / 90.0, max_gap / 90.0,
          old_max_gap / 90.0);
    CHECK(max_err <= 1600, "和真实时刻最多差 %.1f ms（不到一场 16.7 ms，对口型看不出来）", max_err / 90.0);
    CHECK(fabs(v.out_fps - 23.976) < 0.001, "告诉系统的帧率 %.3f", v.out_fps);

    /* ---- 2：隔行视频（每帧都有时间戳） ---- */
    v_timeline_reset(&v);
    v.out_fps = 0;
    prev = -1;
    max_gap = 0;
    min_gap = INT64_MAX;
    for (int k = 0; k < 120; k++) {
        f->repeat_pict = 0;
        f->flags = AV_FRAME_FLAG_INTERLACED;
        f->pts = 500000 + (int64_t)k * 3003;
        f->best_effort_timestamp = f->pts;
        double avg = 0;
        int64_t p = v_timeline(&v, f, &avg);
        v_update_fps(&v, avg, true);
        if (k > 0) {
            if (p - prev > max_gap) max_gap = p - prev;
            if (p - prev < min_gap) min_gap = p - prev;
        }
        prev = p;
    }
    CHECK(min_gap == 3003 && max_gap == 3003, "隔行视频按原来的时间戳：间隔 %.1f ms", max_gap / 90.0);
    CHECK(fabs(v.out_fps - 59.94) < 0.001, "去隔行后的帧率 %.2f", v.out_fps);

    /* 电影 → 隔行（菜单之类）：帧率要连续 24 帧都变了才换 */
    int switched_at = -1;
    v_timeline_reset(&v);
    v.out_fps = 23.976;
    for (int k = 0; k < 60; k++) {
        f->repeat_pict = 0;
        f->flags = AV_FRAME_FLAG_INTERLACED;
        f->pts = 900000 + (int64_t)k * 3003;
        double avg = 0;
        v_timeline(&v, f, &avg);
        v_update_fps(&v, avg, true);
        if (switched_at < 0 && fabs(v.out_fps - 59.94) < 0.001) switched_at = k;
    }
    CHECK(switched_at >= 24 && switched_at <= 40, "帧率稳定一阵才换（第 %d 帧换成 59.94）", switched_at);

    /* ---- 3：时间戳不连续 ---- */
    v_timeline_reset(&v);
    for (int k = 0; k < 10; k++) {
        f->repeat_pict = 0;
        f->flags = 0;
        f->pts = 100000 + (int64_t)k * 3003;
        double avg = 0;
        v_timeline(&v, f, &avg);
    }
    f->pts = 5000000;
    double avg = 0;
    int64_t p = v_timeline(&v, f, &avg);
    CHECK(p == 5000000, "时间戳跳了（%lld）：以流里的为准", (long long)p);
    f->pts = AV_NOPTS_VALUE;
    p = v_timeline(&v, f, &avg);
    CHECK(p == 5000000 + 3003, "下一帧接着它（%lld）", (long long)p);

    av_frame_free(&f);
    avcodec_free_context(&v.ctx);
    printf(fails ? "\n有 %d 项失败\n" : "\n全部通过\n", fails);
    return fails ? 1 : 0;
}
