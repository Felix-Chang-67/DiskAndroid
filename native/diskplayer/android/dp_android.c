/*
 * 原生播放器的 Android 部分：JNI 接口、读盘回调（转到 Kotlin 的 USB 读扇区）、OpenGL ES 3 视频输出、AAudio 音频输出。
 * 播放核心（dp_dvd.c 等）与平台无关，在电脑上有单元测试。
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <dlfcn.h>
#include <GLES3/gl3.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "../src/dc_host.h"
#include "../src/dp_bd.h"
#include "../src/dp_core.h"
#include "../src/dp_dvd.h"
#include <libavcodec/avcodec.h>

static JavaVM *g_vm;
static jclass g_bridge_class;     /* io.github.felixchang67.diskandroid.core.player.NativeBridge */
static jmethodID g_log_mid;       /* static void log(int level, String tag, String msg) */
static pthread_key_t g_env_key;

/* 原生线程第一次回调 Java 时挂到虚拟机上，线程退出时自动摘下。 */
static void detach_thread(void *env) {
    (void)env;
    if (g_vm) (*g_vm)->DetachCurrentThread(g_vm);
}

static JNIEnv *get_env(void) {
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
    pthread_setspecific(g_env_key, env);
    return env;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    pthread_key_create(&g_env_key, detach_thread);
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return -1;
    jclass c = (*env)->FindClass(env, "io/github/felixchang67/diskandroid/core/player/NativeBridge");
    if (!c) return -1;
    g_bridge_class = (*env)->NewGlobalRef(env, c);
    g_log_mid = (*env)->GetStaticMethodID(env, c, "log", "(ILjava/lang/String;Ljava/lang/String;)V");
    return JNI_VERSION_1_6;
}

static void log_to_java(void *opaque, dp_log_level level, const char *tag, const char *msg) {
    (void)opaque;
    __android_log_print(level >= DP_LOG_ERROR ? ANDROID_LOG_ERROR : level >= DP_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG, tag, "%s", msg);
    JNIEnv *env = get_env();
    if (!env || !g_log_mid) return;
    jstring t = (*env)->NewStringUTF(env, tag);
    jstring m = (*env)->NewStringUTF(env, msg);
    if (t && m) (*env)->CallStaticVoidMethod(env, g_bridge_class, g_log_mid, (jint)level, t, m);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (t) (*env)->DeleteLocalRef(env, t);
    if (m) (*env)->DeleteLocalRef(env, m);
}

/* ---------------- 播放器实例 ---------------- */

typedef struct {
    /* Java 对象 */
    jobject reader;          /* SectorReader：int read(long offset, ByteBuffer dst, int length) */
    jmethodID read_mid;
    jobject listener;        /* PlayerListener：void onEvent(int event, int a1, int a2, String text) */
    jmethodID event_mid;

    dp_source *src;
    int kind;                /* 0 = DVD，1 = 蓝光 */
    dp_dvd *dvd;
    dp_bd *bd;

    /* 视频（只在视频线程里碰 EGL / GL） */
    pthread_mutex_t win_lock;
    pthread_cond_t win_cond;
    ANativeWindow *window;        /* 界面给的新 Surface（视频线程取走） */
    bool window_changed;
    ANativeWindow *cur_window;    /* 视频线程正在用的 */
    EGLDisplay egl_dpy;
    EGLContext egl_ctx;
    EGLSurface egl_surf;
    GLuint prog, tex[4], vbo;
    GLint u_tex[4], u_has_ov;
    int tex_w[3], tex_h[3];
    int ov_w, ov_h;
    bool ov_valid;
    uint8_t *ov_pm;               /* 叠加层的预乘 alpha 副本（线性过滤时字幕边缘不发黑） */
    size_t ov_pm_size;
    bool egl_failed;              /* 这个窗口上 EGL 建不起来：等换了窗口再试，不要每帧重试刷日志 */
    PFNEGLPRESENTATIONTIMEANDROIDPROC present_time;   /* EGL_ANDROID_presentation_time，没有为 NULL */
    float win_fps;                /* 已经告诉系统的帧率（换窗口后要重新告诉） */
    int surf_w, surf_h;
    /* 最近一帧的显示比例（给界面把触摸点换算成视频坐标） */
    volatile int disp_w, disp_h, frame_w, frame_h;

    /* 音频（aa 只在音频线程里打开 / 关闭；别的线程用它要拿 aa_lock） */
    pthread_mutex_t aa_lock;
    AAudioStream *aa;
    int aa_rate, aa_channels;
    int64_t aa_base;              /* 上次清空时已写入的帧数（之后"已播放"从这里算起） */
    bool aa_paused;
    int64_t aa_xruns_closed;      /* 已关闭的旧流上的欠载次数（输出设备变了会换流） */
} jplayer;

/* 读盘回调（在导航线程里）：调 Kotlin 的 SectorReader.read。 */
static int read_cb(void *opaque, uint64_t offset, uint8_t *buf, int len) {
    jplayer *p = opaque;
    JNIEnv *env = get_env();
    if (!env) return -2;
    jobject bb = (*env)->NewDirectByteBuffer(env, buf, len);
    if (!bb) return -1;
    jint r = (*env)->CallIntMethod(env, p->reader, p->read_mid, (jlong)offset, bb, (jint)len);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        r = -1;
    }
    (*env)->DeleteLocalRef(env, bb);
    return r;
}

static void event_cb(void *opaque, int ev, int a1, int a2, const char *text) {
    jplayer *p = opaque;
    JNIEnv *env = get_env();
    if (!env || !p->listener) return;
    jstring t = text ? (*env)->NewStringUTF(env, text) : NULL;
    (*env)->CallVoidMethod(env, p->listener, p->event_mid, (jint)ev, (jint)a1, (jint)a2, t);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (t) (*env)->DeleteLocalRef(env, t);
}

/* ---------------- 解密组件的宿主接口（dc_host.h） ---------------- */

/*
 * 组件（libdvdcss / libaacs）的认证命令经这里发给光驱：JNI 回调当前播放器的 SectorReader.scsi（Kotlin），
 * 由光驱会话发出（只读守卫 + 命令日志）。组件是进程级的，所以"当前播放器"也是全局的（同一时刻只放一张盘）。
 */
static pthread_mutex_t g_access_lock = PTHREAD_MUTEX_INITIALIZER;
static jobject g_access;
static jmethodID g_scsi_mid;

static void access_set(JNIEnv *env, jobject reader) {
    pthread_mutex_lock(&g_access_lock);
    if (g_access) (*env)->DeleteGlobalRef(env, g_access);
    g_access = reader ? (*env)->NewGlobalRef(env, reader) : NULL;
    if (reader) {
        jclass c = (*env)->GetObjectClass(env, reader);
        g_scsi_mid = (*env)->GetMethodID(env, c, "scsi", "([BI[B[BI)I");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            g_scsi_mid = NULL;
        }
    }
    pthread_mutex_unlock(&g_access_lock);
}

static void access_clear(JNIEnv *env, jobject reader) {
    pthread_mutex_lock(&g_access_lock);
    if (g_access && (*env)->IsSameObject(env, g_access, reader)) {
        (*env)->DeleteGlobalRef(env, g_access);
        g_access = NULL;
    }
    pthread_mutex_unlock(&g_access_lock);
}

static int host_scsi(void *opaque, const uint8_t *cdb, int cdb_len, int dir, uint8_t *buf, int buf_len, uint8_t *sense, int *sense_len,
                     int timeout_ms) {
    JNIEnv *env = get_env();
    if (!env || cdb_len <= 0 || cdb_len > 16 || buf_len < 0) return -1;
    pthread_mutex_lock(&g_access_lock);
    jobject acc = g_access ? (*env)->NewLocalRef(env, g_access) : NULL;
    jmethodID mid = g_scsi_mid;
    pthread_mutex_unlock(&g_access_lock);
    if (!acc || !mid) {
        dp_log(DP_LOG_WARN, opaque ? (const char *)opaque : "DECRYPT", "认证命令发不出去：没有正在使用的光驱");
        if (acc) (*env)->DeleteLocalRef(env, acc);
        return -1;
    }
    jbyteArray jcdb = (*env)->NewByteArray(env, cdb_len);
    (*env)->SetByteArrayRegion(env, jcdb, 0, cdb_len, (const jbyte *)cdb);
    jbyteArray jdata = NULL;
    if (dir != DC_DIR_NONE && buf_len > 0) {
        jdata = (*env)->NewByteArray(env, buf_len);
        if (dir == DC_DIR_OUT) (*env)->SetByteArrayRegion(env, jdata, 0, buf_len, (const jbyte *)buf);
    }
    jbyteArray jsense = (*env)->NewByteArray(env, 64);
    jint r = (*env)->CallIntMethod(env, acc, mid, jcdb, (jint)dir, jdata, jsense, (jint)timeout_ms);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        r = -1;
    }
    if (r == 0 && dir == DC_DIR_IN && jdata) (*env)->GetByteArrayRegion(env, jdata, 0, buf_len, (jbyte *)buf);
    if (r > 0) {
        int n = r < 64 ? r : 64;
        if (sense_len && *sense_len < n) n = *sense_len;
        if (sense && n > 0) (*env)->GetByteArrayRegion(env, jsense, 0, n, (jbyte *)sense);
        if (sense_len) *sense_len = n;
        r = 1;
    }
    (*env)->DeleteLocalRef(env, jcdb);
    if (jdata) (*env)->DeleteLocalRef(env, jdata);
    (*env)->DeleteLocalRef(env, jsense);
    (*env)->DeleteLocalRef(env, acc);
    return r;
}

static void host_log(void *opaque, int level, const char *msg) {
    dp_log(level <= DC_LOG_DEBUG ? DP_LOG_DEBUG : level == DC_LOG_INFO ? DP_LOG_INFO : level == DC_LOG_WARN ? DP_LOG_WARN : DP_LOG_ERROR,
           opaque ? (const char *)opaque : "DECRYPT", "%s", msg);
}

static dc_host_api g_css_host = {DC_HOST_API_VERSION, (void *)"CSS", host_scsi, host_log};
static void *g_css_lib;
static char g_css_path[512];

/* ---------------- OpenGL ES 3 视频输出 ---------------- */

static const char *VS =
    "#version 300 es\n"
    "layout(location=0) in vec2 pos;\n"
    "layout(location=1) in vec2 uv;\n"
    "out vec2 v_uv;\n"
    "void main(){ v_uv = uv; gl_Position = vec4(pos, 0.0, 1.0); }\n";

static const char *FS =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec2 v_uv;\n"
    "uniform sampler2D ty; uniform sampler2D tu; uniform sampler2D tv; uniform sampler2D tov;\n"
    "uniform int has_ov;\n"
    "out vec4 color;\n"
    "void main(){\n"
    "  float y = 1.16438 * (texture(ty, v_uv).r - 0.0625);\n"
    "  float u = texture(tu, v_uv).r - 0.5;\n"
    "  float v = texture(tv, v_uv).r - 0.5;\n"
    "  vec3 rgb = vec3(y + 1.59603 * v, y - 0.39176 * u - 0.81297 * v, y + 2.01723 * u);\n"
    "  if (has_ov == 1) { vec4 o = texture(tov, v_uv); rgb = rgb * (1.0 - o.a) + o.rgb; }\n"
    "  color = vec4(clamp(rgb, 0.0, 1.0), 1.0);\n"
    "}\n";

static GLuint compile(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof(log), NULL, log);
        dp_log(DP_LOG_ERROR, "VIDEO", "着色器编译失败：%s", log);
    }
    return s;
}

/* 拆掉 EGL 表面和上下文。不调 eglTerminate：默认 display 是整个进程共用的（界面的硬件绘制也在用）。 */
static void gl_teardown(jplayer *p) {
    if (p->egl_dpy == EGL_NO_DISPLAY || !p->egl_dpy) return;
    eglMakeCurrent(p->egl_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (p->egl_surf != EGL_NO_SURFACE) eglDestroySurface(p->egl_dpy, p->egl_surf);
    if (p->egl_ctx != EGL_NO_CONTEXT) eglDestroyContext(p->egl_dpy, p->egl_ctx);
    p->egl_dpy = EGL_NO_DISPLAY;
    p->egl_ctx = EGL_NO_CONTEXT;
    p->egl_surf = EGL_NO_SURFACE;
    p->prog = 0;
    memset(p->tex_w, 0, sizeof(p->tex_w));
    p->ov_w = p->ov_h = 0;
}

/* 视频线程：界面换了 Surface（或者 Surface 要销毁了）就拆掉旧的 EGL，换上新窗口，并通知等着的界面线程。 */
static void gl_check_window(jplayer *p) {
    pthread_mutex_lock(&p->win_lock);
    if (p->window_changed) {
        p->window_changed = false;
        gl_teardown(p);
        if (p->cur_window) ANativeWindow_release(p->cur_window);
        p->cur_window = p->window;
        if (p->cur_window) ANativeWindow_acquire(p->cur_window);
        p->egl_failed = false;
        p->win_fps = 0;
        pthread_cond_broadcast(&p->win_cond);
    }
    pthread_mutex_unlock(&p->win_lock);
}

/* 视频线程：需要时建 EGL。返回 false 表示现在没有可画的 Surface。 */
static bool gl_ensure(jplayer *p) {
    gl_check_window(p);
    pthread_mutex_lock(&p->win_lock);
    ANativeWindow *win = p->cur_window;
    pthread_mutex_unlock(&p->win_lock);
    if (!win || p->egl_failed) return false;
    if (p->egl_surf != EGL_NO_SURFACE && p->egl_dpy) return true;

    p->egl_dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (p->egl_dpy == EGL_NO_DISPLAY || !eglInitialize(p->egl_dpy, NULL, NULL)) {
        dp_log(DP_LOG_ERROR, "VIDEO", "EGL 初始化失败：0x%x", eglGetError());
        p->egl_dpy = EGL_NO_DISPLAY;
        p->egl_failed = true;
        return false;
    }
    const EGLint attrs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
    EGLConfig cfg;
    EGLint n = 0;
    if (!eglChooseConfig(p->egl_dpy, attrs, &cfg, 1, &n) || n < 1) {
        dp_log(DP_LOG_ERROR, "VIDEO", "EGL 找不到可用的配置");
        p->egl_dpy = EGL_NO_DISPLAY;
        p->egl_failed = true;
        return false;
    }
    const EGLint ctx_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    p->egl_ctx = eglCreateContext(p->egl_dpy, cfg, EGL_NO_CONTEXT, ctx_attrs);
    p->egl_surf = eglCreateWindowSurface(p->egl_dpy, cfg, win, NULL);
    if (p->egl_ctx == EGL_NO_CONTEXT || p->egl_surf == EGL_NO_SURFACE || !eglMakeCurrent(p->egl_dpy, p->egl_surf, p->egl_surf, p->egl_ctx)) {
        dp_log(DP_LOG_ERROR, "VIDEO", "EGL 表面 / 上下文建不起来：0x%x", eglGetError());
        gl_teardown(p);
        p->egl_failed = true;
        return false;
    }
    eglSwapInterval(p->egl_dpy, 1);
    const char *ext = eglQueryString(p->egl_dpy, EGL_EXTENSIONS);
    p->present_time = ext && strstr(ext, "EGL_ANDROID_presentation_time")
                          ? (PFNEGLPRESENTATIONTIMEANDROIDPROC)eglGetProcAddress("eglPresentationTimeANDROID") : NULL;
    GLuint vs = compile(GL_VERTEX_SHADER, VS), fs = compile(GL_FRAGMENT_SHADER, FS);
    p->prog = glCreateProgram();
    glAttachShader(p->prog, vs);
    glAttachShader(p->prog, fs);
    glLinkProgram(p->prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint linked = 0;
    glGetProgramiv(p->prog, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[512];
        glGetProgramInfoLog(p->prog, sizeof(log), NULL, log);
        dp_log(DP_LOG_ERROR, "VIDEO", "着色器链接失败：%s", log);
        gl_teardown(p);
        p->egl_failed = true;
        return false;
    }
    glUseProgram(p->prog);
    const char *names[4] = {"ty", "tu", "tv", "tov"};
    glGenTextures(4, p->tex);
    for (int i = 0; i < 4; i++) {
        p->u_tex[i] = glGetUniformLocation(p->prog, names[i]);
        glActiveTexture(GL_TEXTURE0 + i);
        glBindTexture(GL_TEXTURE_2D, p->tex[i]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glUniform1i(p->u_tex[i], i);
    }
    p->u_has_ov = glGetUniformLocation(p->prog, "has_ov");
    glGenBuffers(1, &p->vbo);
    eglQuerySurface(p->egl_dpy, p->egl_surf, EGL_WIDTH, &p->surf_w);
    eglQuerySurface(p->egl_dpy, p->egl_surf, EGL_HEIGHT, &p->surf_h);
    dp_log(DP_LOG_INFO, "VIDEO", "视频输出就绪：%d×%d（OpenGL ES 3，%s）", p->surf_w, p->surf_h,
           p->present_time ? "按时刻提交" : "不支持按时刻提交");
    return true;
}

static void upload_plane(jplayer *p, int i, const uint8_t *data, int stride, int w, int h) {
    glActiveTexture(GL_TEXTURE0 + i);
    glBindTexture(GL_TEXTURE_2D, p->tex[i]);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, stride);
    if (p->tex_w[i] != w || p->tex_h[i] != h) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, w, h, 0, GL_RED, GL_UNSIGNED_BYTE, data);
        p->tex_w[i] = w;
        p->tex_h[i] = h;
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RED, GL_UNSIGNED_BYTE, data);
    }
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
}

/*
 * 告诉系统视频的帧率（Android 11+）：系统据此挑能整除的屏幕刷新率（29.97 帧 → 60 / 120 Hz），
 * 避免在 144 Hz 屏上每帧停 4 或 5 个刷新周期造成的顿挫，也避免播放中刷新率自己变来变去。
 */
typedef int32_t (*set_frame_rate_fn)(ANativeWindow *, float, int8_t);
static void hint_frame_rate(jplayer *p, double fps) {
    if (fps <= 0 || !p->cur_window || (float)fps == p->win_fps) return;
    static set_frame_rate_fn fn;
    static bool looked;
    if (!looked) {
        looked = true;
        void *lib = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
        if (!lib) lib = dlopen("libandroid.so", RTLD_NOW);
        if (lib) fn = (set_frame_rate_fn)dlsym(lib, "ANativeWindow_setFrameRate");
    }
    p->win_fps = (float)fps;
    if (!fn) {
        dp_log(DP_LOG_INFO, "VIDEO", "系统不支持指定帧率（Android 11 起才有）");
        return;
    }
    int32_t r = fn(p->cur_window, (float)fps, 1 /* ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE */);
    dp_log(DP_LOG_INFO, "VIDEO", "告诉系统视频帧率 %.3f%s", fps, r == 0 ? "" : "（失败）");
}

static void video_present(void *opaque, const dp_frame *f, const uint32_t *overlay, int ov_w, int ov_h, bool changed) {
    jplayer *p = opaque;
    if (!gl_ensure(p)) return;
    hint_frame_rate(p, f->fps);
    EGLint sw = 0, sh = 0;
    eglQuerySurface(p->egl_dpy, p->egl_surf, EGL_WIDTH, &sw);
    eglQuerySurface(p->egl_dpy, p->egl_surf, EGL_HEIGHT, &sh);
    p->surf_w = sw;
    p->surf_h = sh;
    upload_plane(p, 0, f->plane[0], f->stride[0], f->width, f->height);
    upload_plane(p, 1, f->plane[1], f->stride[1], (f->width + 1) / 2, (f->height + 1) / 2);
    upload_plane(p, 2, f->plane[2], f->stride[2], (f->width + 1) / 2, (f->height + 1) / 2);
    if (overlay) {
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, p->tex[3]);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        bool resize = p->ov_w != ov_w || p->ov_h != ov_h;
        if (resize || changed || !p->ov_valid) {
            /* 预乘 alpha（内存顺序 R、G、B、A） */
            size_t n = (size_t)ov_w * (size_t)ov_h;
            if (p->ov_pm_size < n * 4) {
                free(p->ov_pm);
                p->ov_pm = malloc(n * 4);
                p->ov_pm_size = p->ov_pm ? n * 4 : 0;
            }
            if (p->ov_pm) {
                const uint8_t *src = (const uint8_t *)overlay;
                for (size_t i = 0; i < n * 4; i += 4) {
                    unsigned a = src[i + 3];
                    p->ov_pm[i] = (uint8_t)((src[i] * a + 127) / 255);
                    p->ov_pm[i + 1] = (uint8_t)((src[i + 1] * a + 127) / 255);
                    p->ov_pm[i + 2] = (uint8_t)((src[i + 2] * a + 127) / 255);
                    p->ov_pm[i + 3] = (uint8_t)a;
                }
                if (resize) {
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, ov_w, ov_h, 0, GL_RGBA, GL_UNSIGNED_BYTE, p->ov_pm);
                    p->ov_w = ov_w;
                    p->ov_h = ov_h;
                } else {
                    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, ov_w, ov_h, GL_RGBA, GL_UNSIGNED_BYTE, p->ov_pm);
                }
            }
        }
        p->ov_valid = p->ov_pm != NULL;
    } else {
        p->ov_valid = false;
    }
    /* 按显示比例（像素宽高比 × 尺寸）在 Surface 里居中 */
    double dar = (double)f->width * f->sar_num / ((double)f->height * (f->sar_den ? f->sar_den : 1));
    int vw = sw, vh = (int)(sw / dar);
    if (vh > sh) {
        vh = sh;
        vw = (int)(sh * dar);
    }
    int vx = (sw - vw) / 2, vy = (sh - vh) / 2;
    p->disp_w = vw;
    p->disp_h = vh;
    p->frame_w = f->width;
    p->frame_h = f->height;
    glViewport(0, 0, sw, sh);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glViewport(vx, vy, vw, vh);
    const float quad[] = {-1, -1, 0, 1, 1, -1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 0};
    glUseProgram(p->prog);
    glUniform1i(p->u_has_ov, overlay && p->ov_valid ? 1 : 0);
    glBindBuffer(GL_ARRAY_BUFFER, p->vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STREAM_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 16, (void *)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 16, (void *)8);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (p->present_time && f->present_ns > 0) p->present_time(p->egl_dpy, p->egl_surf, (EGLnsecsANDROID)f->present_ns);
    if (!eglSwapBuffers(p->egl_dpy, p->egl_surf)) {
        EGLint e = eglGetError();
        if (e == EGL_BAD_SURFACE || e == EGL_BAD_NATIVE_WINDOW) gl_teardown(p);
    }
}

static void video_idle(void *opaque) {
    gl_check_window(opaque);
}

/* 视频线程退出前：EGL 上下文只能在用它的线程里释放。 */
static void video_exit(void *opaque) {
    jplayer *p = opaque;
    gl_teardown(p);
    pthread_mutex_lock(&p->win_lock);
    if (p->cur_window) ANativeWindow_release(p->cur_window);
    p->cur_window = NULL;
    pthread_cond_broadcast(&p->win_cond);
    pthread_mutex_unlock(&p->win_lock);
}

/* ---------------- AAudio 音频输出 ---------------- */

static AAudioStream *aa_create(int rate, int channels) {
    AAudioStreamBuilder *b = NULL;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return NULL;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(b, rate);
    AAudioStreamBuilder_setChannelCount(b, channels);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MOVIE);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStream *st = NULL;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &st);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) {
        dp_log(DP_LOG_ERROR, "VIDEO", "AAudio 打不开：%s", AAudio_convertResultToText(r));
        return NULL;
    }
    /* 缓冲开到最大：画面跟着声音走，延迟大一点没关系，不容易因为线程一时没排上而断音 */
    int32_t cap = AAudioStream_getBufferCapacityInFrames(st);
    if (cap > 0) AAudioStream_setBufferSizeInFrames(st, cap);
    dp_log(DP_LOG_INFO, "VIDEO", "音频输出：%d Hz %d 声道，缓冲 %d 帧（最大 %d）", AAudioStream_getSampleRate(st), AAudioStream_getChannelCount(st),
           AAudioStream_getBufferSizeInFrames(st), cap);
    return st;
}

/* 音频线程 */
static int audio_open(void *opaque, int rate, int channels) {
    jplayer *p = opaque;
    if (p->aa) return 0;
    AAudioStream *st = aa_create(rate, channels);
    if (!st) return -1;
    pthread_mutex_lock(&p->aa_lock);
    p->aa = st;
    p->aa_rate = rate;
    p->aa_channels = channels;
    p->aa_base = 0;
    if (!p->aa_paused) AAudioStream_requestStart(st);
    pthread_mutex_unlock(&p->aa_lock);
    return 0;
}

/* 音频线程：阻塞写入。输出设备断开（拔耳机、蓝牙断开）时重开一个流接着放。 */
static int audio_write(void *opaque, const int16_t *pcm, int frames) {
    jplayer *p = opaque;
    if (!p->aa) return -1;
    aaudio_result_t r = AAudioStream_write(p->aa, pcm, frames, 200 * 1000000LL);
    if (r == AAUDIO_ERROR_DISCONNECTED) {
        dp_log(DP_LOG_INFO, "VIDEO", "音频输出设备变了，重新打开");
        AAudioStream *st = aa_create(p->aa_rate, p->aa_channels);
        pthread_mutex_lock(&p->aa_lock);
        AAudioStream *old = p->aa;
        /* 旧流上已经播放的帧接着算，时钟不跳 */
        int64_t old_written = AAudioStream_getFramesWritten(old);
        int32_t old_xruns = AAudioStream_getXRunCount(old);
        if (old_xruns > 0) p->aa_xruns_closed += old_xruns;
        p->aa = st;
        p->aa_base -= old_written;
        if (st && !p->aa_paused) AAudioStream_requestStart(st);
        pthread_mutex_unlock(&p->aa_lock);
        AAudioStream_requestStop(old);
        AAudioStream_close(old);
        if (!st) return -1;
        return 0;
    }
    if (r < 0) {
        dp_log(DP_LOG_WARN, "VIDEO", "AAudio 写入失败：%s", AAudio_convertResultToText(r));
        return -1;
    }
    return r;
}

/* 已经播放出去的帧（相对上次清空）：用 AAudio 的时间戳推算"此刻扬声器里在放哪一帧"。视频线程调用。 */
static int64_t audio_played(void *opaque) {
    jplayer *p = opaque;
    pthread_mutex_lock(&p->aa_lock);
    if (!p->aa) {
        pthread_mutex_unlock(&p->aa_lock);
        return 0;
    }
    int64_t pos = 0, t = 0, played;
    if (!p->aa_paused && AAudioStream_getTimestamp(p->aa, CLOCK_MONOTONIC, &pos, &t) == AAUDIO_OK) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        int64_t now = (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
        played = pos + (now - t) * AAudioStream_getSampleRate(p->aa) / 1000000000LL;
    } else {
        played = AAudioStream_getFramesRead(p->aa);
    }
    int64_t written = AAudioStream_getFramesWritten(p->aa);
    if (played > written) played = written;
    played -= p->aa_base;
    pthread_mutex_unlock(&p->aa_lock);
    return played < 0 ? 0 : played;
}

/* 音频欠载累计次数（统计）。 */
static int64_t audio_xruns(void *opaque) {
    jplayer *p = opaque;
    pthread_mutex_lock(&p->aa_lock);
    int64_t n = p->aa_xruns_closed;
    if (p->aa) {
        int32_t x = AAudioStream_getXRunCount(p->aa);
        if (x > 0) n += x;
    }
    pthread_mutex_unlock(&p->aa_lock);
    return n;
}

/* 导航线程（用户暂停 / 继续）。 */
static void audio_pause(void *opaque, bool pause) {
    jplayer *p = opaque;
    pthread_mutex_lock(&p->aa_lock);
    p->aa_paused = pause;
    if (p->aa) {
        if (pause) AAudioStream_requestPause(p->aa); else AAudioStream_requestStart(p->aa);
    }
    pthread_mutex_unlock(&p->aa_lock);
}

/* 音频线程（跳转）：丢掉缓冲里还没放的声音。暂停中跳转的话保持暂停。 */
static void audio_flush(void *opaque) {
    jplayer *p = opaque;
    pthread_mutex_lock(&p->aa_lock);
    if (p->aa) {
        aaudio_stream_state_t st = AAUDIO_STREAM_STATE_UNKNOWN;
        AAudioStream_requestPause(p->aa);
        AAudioStream_waitForStateChange(p->aa, AAUDIO_STREAM_STATE_PAUSING, &st, 200 * 1000000LL);
        AAudioStream_requestFlush(p->aa);
        AAudioStream_waitForStateChange(p->aa, AAUDIO_STREAM_STATE_FLUSHING, &st, 200 * 1000000LL);
        p->aa_base = AAudioStream_getFramesWritten(p->aa);
        if (!p->aa_paused) AAudioStream_requestStart(p->aa);
    }
    pthread_mutex_unlock(&p->aa_lock);
}

/* 音频线程退出前。 */
static void audio_close(void *opaque) {
    jplayer *p = opaque;
    pthread_mutex_lock(&p->aa_lock);
    AAudioStream *st = p->aa;
    p->aa = NULL;
    pthread_mutex_unlock(&p->aa_lock);
    if (st) {
        AAudioStream_requestStop(st);
        AAudioStream_close(st);
    }
}

/* ---------------- JNI ---------------- */

#define JFN(name) Java_io_github_felixchang67_diskandroid_core_player_NativeBridge_##name

/* 两种播放器共用：建 jplayer（Java 对象引用、读盘源、平台回调）。 */
static jplayer *player_new(JNIEnv *env, jobject reader, jlong size, jobject listener, jint prefetch, dp_platform *pf) {
    dp_log_set(log_to_java, NULL);
    jplayer *p = calloc(1, sizeof(*p));
    if (!p) return NULL;
    pthread_mutex_init(&p->win_lock, NULL);
    pthread_cond_init(&p->win_cond, NULL);
    pthread_mutex_init(&p->aa_lock, NULL);
    p->egl_dpy = EGL_NO_DISPLAY;
    p->egl_ctx = EGL_NO_CONTEXT;
    p->egl_surf = EGL_NO_SURFACE;
    p->reader = (*env)->NewGlobalRef(env, reader);
    jclass rc = (*env)->GetObjectClass(env, reader);
    p->read_mid = (*env)->GetMethodID(env, rc, "read", "(JLjava/nio/ByteBuffer;I)I");
    p->listener = (*env)->NewGlobalRef(env, listener);
    jclass lc = (*env)->GetObjectClass(env, listener);
    p->event_mid = (*env)->GetMethodID(env, lc, "onEvent", "(IIILjava/lang/String;)V");
    p->src = dp_source_new(read_cb, p, (uint64_t)size, prefetch);
    /* 解密组件认证时用这台光驱（打开盘时 libdvdcss / libaacs 就会认证） */
    access_set(env, reader);
    dp_platform tmp = {
        .opaque = p, .video_present = video_present, .video_idle = video_idle, .video_exit = video_exit,
        .audio_open = audio_open, .audio_write = audio_write,
        .audio_played = audio_played, .audio_xruns = audio_xruns, .audio_pause = audio_pause, .audio_flush = audio_flush,
        .audio_close = audio_close,
        .on_event = event_cb, .realtime = true,
    };
    *pf = tmp;
    return p;
}

/* 打开失败：释放 player_new 建的东西。 */
static void player_free_failed(JNIEnv *env, jplayer *p, jobject reader, const char *err, jobjectArray err_out) {
    if (err_out && (*env)->GetArrayLength(env, err_out) > 0) {
        jstring e = (*env)->NewStringUTF(env, err);
        (*env)->SetObjectArrayElement(env, err_out, 0, e);
    }
    access_clear(env, reader);
    dp_source_free(p->src);
    (*env)->DeleteGlobalRef(env, p->reader);
    (*env)->DeleteGlobalRef(env, p->listener);
    pthread_mutex_destroy(&p->win_lock);
    pthread_cond_destroy(&p->win_cond);
    pthread_mutex_destroy(&p->aa_lock);
    free(p);
}

JNIEXPORT jlong JNICALL JFN(nativeOpenDvd)(JNIEnv *env, jclass cls, jobject reader, jlong size, jobject listener, jint region_mask,
                                           jstring menu_lang, jstring audio_lang, jstring spu_lang, jint start_title, jboolean deinterlace,
                                           jint prefetch, jobjectArray err_out) {
    (void)cls;
    dp_platform pf;
    jplayer *p = player_new(env, reader, size, listener, prefetch, &pf);
    if (!p) return 0;
    p->kind = 0;
    dp_dvd_options opt;
    memset(&opt, 0, sizeof(opt));
    opt.region_mask = region_mask;
    opt.start_title = start_title;
    opt.deinterlace = deinterlace;
    const char *s;
    s = (*env)->GetStringUTFChars(env, menu_lang, NULL);
    strncpy(opt.menu_lang, s, 2);
    (*env)->ReleaseStringUTFChars(env, menu_lang, s);
    s = (*env)->GetStringUTFChars(env, audio_lang, NULL);
    strncpy(opt.audio_lang, s, 2);
    (*env)->ReleaseStringUTFChars(env, audio_lang, s);
    s = (*env)->GetStringUTFChars(env, spu_lang, NULL);
    strncpy(opt.spu_lang, s, 2);
    (*env)->ReleaseStringUTFChars(env, spu_lang, s);

    char err[256] = "";
    p->dvd = dp_dvd_open(p->src, &pf, &opt, err, sizeof(err));
    if (!p->dvd) {
        player_free_failed(env, p, reader, err, err_out);
        return 0;
    }
    return (jlong)(intptr_t)p;
}

static void copy_lang(JNIEnv *env, jstring js, char *dst, int n) {
    const char *s = (*env)->GetStringUTFChars(env, js, NULL);
    strncpy(dst, s, (size_t)n);
    dst[n] = 0;
    (*env)->ReleaseStringUTFChars(env, js, s);
}

/* 蓝光：[startPlaylist] < 0 用光盘菜单（导航模式），>= 0 直接播放这个播放列表。 */
JNIEXPORT jlong JNICALL JFN(nativeOpenBluray)(JNIEnv *env, jclass cls, jobject reader, jlong size, jobject listener, jstring audio_lang,
                                              jstring pg_lang, jstring menu_lang, jint region, jint start_playlist, jboolean deinterlace,
                                              jint prefetch, jobjectArray err_out) {
    (void)cls;
    dp_platform pf;
    jplayer *p = player_new(env, reader, size, listener, prefetch, &pf);
    if (!p) return 0;
    p->kind = 1;
    dp_bd_options opt;
    memset(&opt, 0, sizeof(opt));
    copy_lang(env, audio_lang, opt.audio_lang, 3);
    copy_lang(env, pg_lang, opt.pg_lang, 3);
    copy_lang(env, menu_lang, opt.menu_lang, 3);
    opt.region = region;
    opt.start_playlist = start_playlist;
    opt.deinterlace = deinterlace;
    char err[256] = "";
    p->bd = dp_bd_open(p->src, &pf, &opt, err, sizeof(err));
    if (!p->bd) {
        player_free_failed(env, p, reader, err, err_out);
        return 0;
    }
    return (jlong)(intptr_t)p;
}

JNIEXPORT void JNICALL JFN(nativeStart)(JNIEnv *env, jclass cls, jlong h) {
    (void)env;
    (void)cls;
    jplayer *p = (jplayer *)(intptr_t)h;
    if (p) {
        if (p->kind == 1) dp_bd_start(p->bd); else dp_dvd_start(p->dvd);
    }
}

JNIEXPORT void JNICALL JFN(nativeCommand)(JNIEnv *env, jclass cls, jlong h, jint cmd, jint a1, jint a2) {
    (void)env;
    (void)cls;
    jplayer *p = (jplayer *)(intptr_t)h;
    if (p) {
        if (p->kind == 1) dp_bd_command(p->bd, cmd, a1, a2); else dp_dvd_command(p->dvd, cmd, a1, a2);
    }
}

JNIEXPORT void JNICALL JFN(nativeSetSurface)(JNIEnv *env, jclass cls, jlong h, jobject surface) {
    (void)cls;
    jplayer *p = (jplayer *)(intptr_t)h;
    if (!p) return;
    ANativeWindow *w = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    pthread_mutex_lock(&p->win_lock);
    if (p->window) ANativeWindow_release(p->window);
    p->window = w;
    p->window_changed = true;
    /* Surface 要销毁了：等视频线程放手（surfaceDestroyed 返回后就不能再往上画），最多等 500 ms */
    if (!w && p->cur_window) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_nsec += 500 * 1000000L;
        if (ts.tv_nsec >= 1000000000L) {
            ts.tv_sec++;
            ts.tv_nsec -= 1000000000L;
        }
        while (p->window_changed && p->cur_window) {
            if (pthread_cond_timedwait(&p->win_cond, &p->win_lock, &ts) != 0) {
                dp_log(DP_LOG_WARN, "VIDEO", "视频线程没有及时放开 Surface");
                break;
            }
        }
    }
    pthread_mutex_unlock(&p->win_lock);
    if (p->kind == 1) dp_bd_refresh(p->bd); else dp_dvd_refresh(p->dvd);
}

/*
 * 状态（DVD 和蓝光共用一种排列，见 core:player 的 DiscPlayer.parseState）：
 * ints[0..24]：域、标题、章节、标题数、章节数、按钮数、当前按钮、静止、暂停、结束、
 *   音轨数、当前音轨、字幕数、当前字幕、字幕显示、角度数、当前角度、视频宽、视频高、16:9、显示宽、显示高、帧宽、帧高、音频流号；
 * ints[25..32]：种类（0 DVD / 1 蓝光）、蓝光模式、蓝光菜单显示中、有弹出菜单、播放列表号、BD-J、图层宽、图层高；
 * longs：位置 ms、时长 ms、读盘字节、读错扇区、显示帧数、丢帧数、音频帧数；
 * strings：音轨说明 + 字幕说明 + 视频编码名 + 音频编码名 + 错误。
 */
#define STATE_INTS 33
static jobjectArray make_strings(JNIEnv *env, int na, char (*audio)[24], int ns, char (*subs)[8], int vcodec, int acodec, const char *error) {
    jclass sc = (*env)->FindClass(env, "java/lang/String");
    jobjectArray arr = (*env)->NewObjectArray(env, na + ns + 3, sc, NULL);
    for (int i = 0; i < na; i++) {
        jstring t = (*env)->NewStringUTF(env, audio[i]);
        (*env)->SetObjectArrayElement(env, arr, i, t);
        (*env)->DeleteLocalRef(env, t);
    }
    for (int i = 0; i < ns; i++) {
        jstring t = (*env)->NewStringUTF(env, subs[i]);
        (*env)->SetObjectArrayElement(env, arr, na + i, t);
        (*env)->DeleteLocalRef(env, t);
    }
    const char *extra[3] = {vcodec ? avcodec_get_name((enum AVCodecID)vcodec) : "", acodec ? avcodec_get_name((enum AVCodecID)acodec) : "",
                            error ? error : ""};
    for (int i = 0; i < 3; i++) {
        jstring t = (*env)->NewStringUTF(env, extra[i]);
        (*env)->SetObjectArrayElement(env, arr, na + ns + i, t);
        (*env)->DeleteLocalRef(env, t);
    }
    return arr;
}

JNIEXPORT jobjectArray JNICALL JFN(nativeGetState)(JNIEnv *env, jclass cls, jlong h, jintArray ints, jlongArray longs) {
    (void)cls;
    jplayer *p = (jplayer *)(intptr_t)h;
    if (!p) return NULL;
    jint iv[STATE_INTS];
    memset(iv, 0, sizeof(iv));
    jlong lv[7];
    if (p->kind == 1) {
        static dp_bd_state s; /* 很大（字符串表），不放栈上；只在这里用，nativeGetState 由 Kotlin 串行调用 */
        dp_bd_get_state(p->bd, &s);
        int domain = s.menu ? 2 : (s.mode == DP_BD_MODE_STOP ? 0 : 4);
        jint a[STATE_INTS] = {domain, s.title == 0xFFFF ? 0 : s.title, s.chapter, s.titles, s.chapters, s.menu ? 1 : 0, 0, s.still, s.paused,
                              s.ended, s.audio_count, s.audio_current, s.pg_count, s.pg_current, s.pg_shown, s.angles, s.angle,
                              s.video_w, s.video_h, s.video_h > 0 && s.video_w * 10 / s.video_h >= 15, p->disp_w, p->disp_h, p->frame_w,
                              p->frame_h, s.audio_codec_id, 1, s.mode, s.menu, s.popup, s.playlist, s.bdj, s.plane_w, s.plane_h};
        memcpy(iv, a, sizeof(a));
        jlong l[7] = {s.position_ms, s.duration_ms, (jlong)s.bytes_read, (jlong)s.read_errors, (jlong)s.frames_shown, (jlong)s.frames_dropped,
                      (jlong)s.audio_frames};
        memcpy(lv, l, sizeof(l));
        (*env)->SetIntArrayRegion(env, ints, 0, STATE_INTS, iv);
        (*env)->SetLongArrayRegion(env, longs, 0, 7, lv);
        return make_strings(env, s.audio_count, s.audio_desc, s.pg_count, s.pg_desc, s.video_codec_id, s.audio_codec_id, s.error);
    }
    dp_dvd_state s;
    dp_dvd_get_state(p->dvd, &s);
    jint a[25] = {s.domain, s.title, s.chapter, s.titles, s.chapters, s.buttons, s.button, s.still, s.paused, s.ended,
                  s.audio_count, s.audio_current, s.spu_count, s.spu_current, s.spu_shown, s.angles, s.angle,
                  s.video_w, s.video_h, s.wide, p->disp_w, p->disp_h, p->frame_w, p->frame_h, s.audio_code};
    memcpy(iv, a, sizeof(a));
    jlong l[7] = {s.position_ms, s.duration_ms, (jlong)s.bytes_read, (jlong)s.read_errors, (jlong)s.frames_shown, (jlong)s.frames_dropped,
                  (jlong)s.audio_frames};
    memcpy(lv, l, sizeof(l));
    (*env)->SetIntArrayRegion(env, ints, 0, STATE_INTS, iv);
    (*env)->SetLongArrayRegion(env, longs, 0, 7, lv);
    return make_strings(env, s.audio_count, s.audio_desc, s.spu_count, s.spu_desc, s.video_codec_id, s.audio_codec_id, NULL);
}

JNIEXPORT void JNICALL JFN(nativeClose)(JNIEnv *env, jclass cls, jlong h) {
    (void)cls;
    jplayer *p = (jplayer *)(intptr_t)h;
    if (!p) return;
    /* 先停线程：视频线程退出前自己拆 EGL（video_exit），音频线程自己关 AAudio */
    if (p->kind == 1) dp_bd_close(p->bd); else dp_dvd_close(p->dvd);
    pthread_mutex_lock(&p->win_lock);
    if (p->window) ANativeWindow_release(p->window);
    if (p->cur_window) ANativeWindow_release(p->cur_window);
    p->window = p->cur_window = NULL;
    pthread_mutex_unlock(&p->win_lock);
    audio_close(p);
    dp_source_free(p->src);
    access_clear(env, p->reader);
    (*env)->DeleteGlobalRef(env, p->reader);
    (*env)->DeleteGlobalRef(env, p->listener);
    pthread_mutex_destroy(&p->win_lock);
    pthread_cond_destroy(&p->win_cond);
    pthread_mutex_destroy(&p->aa_lock);
    free(p->ov_pm);
    free(p);
}

/*
 * 检查一个导入的组件：能不能加载、有没有 [symbol]（打过 DiskAndroid 补丁的 libdvdcss 有 dvdcss_set_host）。
 * 返回 null 表示没问题，否则是原因。
 */
JNIEXPORT jstring JNICALL JFN(nativeCheckComponent)(JNIEnv *env, jclass cls, jstring jpath, jstring jsymbol) {
    (void)cls;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *symbol = (*env)->GetStringUTFChars(env, jsymbol, NULL);
    char msg[512] = "";
    void *h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        const char *e = dlerror();
        snprintf(msg, sizeof(msg), "加载失败：%s", e ? e : "未知原因");
    } else {
        if (!dlsym(h, symbol)) snprintf(msg, sizeof(msg), "缺少 %s（不是给 DiskAndroid 编的版本）", symbol);
        dlclose(h);
    }
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    (*env)->ReleaseStringUTFChars(env, jsymbol, symbol);
    return msg[0] ? (*env)->NewStringUTF(env, msg) : NULL;
}

/*
 * 启用 CSS 解密组件：加载、把宿主接口交给它，并让 libdvdread 用它（环境变量 DISKANDROID_DVDCSS）。
 * [cache] 是标题密钥缓存目录（libdvdcss 的 DVDCSS_CACHE）。返回 null 表示成功，否则是原因。
 */
JNIEXPORT jstring JNICALL JFN(nativeAttachCss)(JNIEnv *env, jclass cls, jstring jpath, jstring jcache) {
    (void)cls;
    dp_log_set(log_to_java, NULL);
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *cache = (*env)->GetStringUTFChars(env, jcache, NULL);
    char msg[512] = "";
    if (!g_css_lib || strcmp(g_css_path, path) != 0) {
        void *h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
        if (!h) {
            const char *e = dlerror();
            snprintf(msg, sizeof(msg), "加载失败：%s", e ? e : "未知原因");
        } else {
            g_css_lib = h;
            snprintf(g_css_path, sizeof(g_css_path), "%s", path);
        }
    }
    if (!msg[0]) {
        int (*set_host)(const dc_host_api *) = (int (*)(const dc_host_api *))dlsym(g_css_lib, "dvdcss_set_host");
        if (!set_host) {
            snprintf(msg, sizeof(msg), "缺少 dvdcss_set_host（不是给 DiskAndroid 编的版本）");
        } else {
            int v = set_host(&g_css_host);
            setenv("DISKANDROID_DVDCSS", path, 1);
            setenv("DVDCSS_CACHE", cache, 1);
            dp_log(DP_LOG_INFO, "CSS", "已启用 DVD 解密组件（接口版本 %d）：%s", v, path);
        }
    }
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    (*env)->ReleaseStringUTFChars(env, jcache, cache);
    return msg[0] ? (*env)->NewStringUTF(env, msg) : NULL;
}

/* 停用 CSS 解密组件（删除组件后）：libdvdread 下次打开盘时不再加载它。 */
JNIEXPORT void JNICALL JFN(nativeDetachCss)(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    unsetenv("DISKANDROID_DVDCSS");
    if (g_css_lib) {
        int (*set_host)(const dc_host_api *) = (int (*)(const dc_host_api *))dlsym(g_css_lib, "dvdcss_set_host");
        if (set_host) set_host(NULL);
    }
}

JNIEXPORT jstring JNICALL JFN(nativeVersion)(JNIEnv *env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, "diskplayer/2 (libdvdnav 7.0.0, libdvdread 7.1.1, libbluray 1.5.0, FFmpeg 9.0.2)");
}
