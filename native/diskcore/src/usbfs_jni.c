/*
 * usbfs ioctl 辅助函数（JNI）。
 *
 * Android 的 UsbDeviceConnection 没有公开以下能力，这里直接对它的文件描述符做 ioctl：
 *   - USBDEVFS_GET_SPEED          链路速度（enum usb_device_speed）
 *   - USBDEVFS_GET_CAPABILITIES   usbfs 能力位图
 *   - USBDEVFS_GETDRIVER          某接口当前绑定的内核驱动（usb-storage / uas / usbfs）
 *   - USBDEVFS_CLEAR_HALT         清端点 STALL，并由内核复位主机侧 data toggle（BOT 错误恢复要用）
 *   - USBDEVFS_RESET              端口复位（最后手段）
 *   - USBDEVFS_BULK               批量传输，失败时返回 -errno（公开 API 的 bulkTransfer 只返回 -1，
 *                                 分不清 STALL、超时、内存不足、babble……）
 *
 * 约定：返回 int 的函数 >= 0 表示成功，< 0 表示 -errno。
 */
#include <errno.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

#define JNI_FN(name) Java_io_github_felixchang67_diskandroid_core_usb_UsbfsNative_##name

JNIEXPORT jstring JNICALL JNI_FN(nativeVersion)(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, "diskcore-usbfs/2");
}

/*
 * 一次批量传输（USBDEVFS_BULK）。endpoint 的 bit7 表示方向（IN）。
 * 用用户态临时缓冲区中转，不在阻塞的 ioctl 期间持有 Java 数组（不影响 GC）。
 * 返回实际传输的字节数；失败返回 -errno（EPIPE = STALL，ETIMEDOUT = 超时，ENODEV = 断开，ENOMEM……）。
 */
JNIEXPORT jint JNICALL JNI_FN(nativeBulk)(JNIEnv *env, jclass clazz, jint fd, jint endpoint,
                                          jbyteArray buffer, jint offset, jint length, jint timeout_ms) {
    (void) clazz;
    if (offset < 0 || length < 0 || timeout_ms < 0) return -EINVAL;
    jsize capacity = (*env)->GetArrayLength(env, buffer);
    if ((jlong) offset + (jlong) length > (jlong) capacity) return -EINVAL;
    int is_in = (endpoint & 0x80) != 0;
    void *tmp = malloc(length > 0 ? (size_t) length : 1u);
    if (tmp == NULL) return -ENOMEM;
    if (!is_in && length > 0) (*env)->GetByteArrayRegion(env, buffer, offset, length, (jbyte *) tmp);

    struct usbdevfs_bulktransfer bulk;
    memset(&bulk, 0, sizeof(bulk));
    bulk.ep = (unsigned int) endpoint;
    bulk.len = (unsigned int) length;
    bulk.timeout = (unsigned int) timeout_ms;
    bulk.data = tmp;
    int r = ioctl(fd, USBDEVFS_BULK, &bulk);
    int err = errno;
    if (r > 0 && is_in) {
        if (r > length) r = length;
        (*env)->SetByteArrayRegion(env, buffer, offset, r, (const jbyte *) tmp);
    }
    free(tmp);
    return r < 0 ? -err : r;
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
