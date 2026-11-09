/*
 * usbfs ioctl 辅助函数（JNI）。
 *
 * Android 的 UsbDeviceConnection 没有公开以下能力，这里直接对它的文件描述符做 ioctl：
 *   - USBDEVFS_GET_SPEED          链路速度（enum usb_device_speed）
 *   - USBDEVFS_GET_CAPABILITIES   usbfs 能力位图
 *   - USBDEVFS_GETDRIVER          某接口当前绑定的内核驱动（usb-storage / uas / usbfs）
 *   - USBDEVFS_CLEAR_HALT         清端点 STALL，并由内核复位主机侧 data toggle（BOT 错误恢复要用）
 *   - USBDEVFS_RESET              端口复位（最后手段）
 *
 * 约定：返回 int 的函数 >= 0 表示成功，< 0 表示 -errno。
 */
#include <errno.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

#define JNI_FN(name) Java_io_github_felixchang67_diskandroid_core_usb_UsbfsNative_##name

JNIEXPORT jstring JNICALL JNI_FN(nativeVersion)(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, "diskcore-usbfs/1");
}

JNIEXPORT jint JNICALL JNI_FN(nativeGetSpeed)(JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
#ifdef USBDEVFS_GET_SPEED
    int r = ioctl(fd, USBDEVFS_GET_SPEED);
    return r < 0 ? -errno : r;
#else
    (void) fd;
    return -ENOTTY;
#endif
}

JNIEXPORT jint JNICALL JNI_FN(nativeGetCapabilities)(JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    __u32 caps = 0;
    if (ioctl(fd, USBDEVFS_GET_CAPABILITIES, &caps) < 0) {
        return -errno;
    }
    return (jint) (caps & 0x7FFFFFFFu);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetDriver)(JNIEnv *env, jclass clazz, jint fd, jint iface) {
    (void) clazz;
    struct usbdevfs_getdriver gd;
    memset(&gd, 0, sizeof(gd));
    gd.interface = (unsigned int) iface;
    if (ioctl(fd, USBDEVFS_GETDRIVER, &gd) < 0) {
        char buf[32];
        snprintf(buf, sizeof(buf), "errno:%d", errno);
        return (*env)->NewStringUTF(env, buf);
    }
    gd.driver[sizeof(gd.driver) - 1] = '\0';
    return (*env)->NewStringUTF(env, gd.driver);
}

JNIEXPORT jint JNICALL JNI_FN(nativeClearHalt)(JNIEnv *env, jclass clazz, jint fd, jint endpoint) {
    (void) env;
    (void) clazz;
    unsigned int ep = (unsigned int) endpoint;
    return ioctl(fd, USBDEVFS_CLEAR_HALT, &ep) < 0 ? -errno : 0;
}

JNIEXPORT jint JNICALL JNI_FN(nativeResetDevice)(JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    return ioctl(fd, USBDEVFS_RESET, 0) < 0 ? -errno : 0;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeStrerror)(JNIEnv *env, jclass clazz, jint err) {
    (void) clazz;
    char buf[128];
    if (strerror_r(err, buf, sizeof(buf)) != 0) {
        snprintf(buf, sizeof(buf), "未知错误 %d", err);
    }
    return (*env)->NewStringUTF(env, buf);
}
