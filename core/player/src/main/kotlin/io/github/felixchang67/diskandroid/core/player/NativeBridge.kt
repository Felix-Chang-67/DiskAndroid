package io.github.felixchang67.diskandroid.core.player

import android.view.Surface
import java.nio.ByteBuffer

/**
 * 读盘接口：原生导航线程调用（阻塞），把从 [offset] 起的 [length] 字节读进 [dst]（直接缓冲区，从位置 0 写）。
 * [offset]、[length] 都是 2048 的整数倍。
 *
 * 返回读到的字节数；读不出返回 -1（原生层会逐扇区重读，仍读不出的扇区补零、记日志）；
 * 盘片被取出、光驱断开、播放器正在关闭之类没法继续时返回 [FATAL]。
 */
interface SectorReader {
    fun read(offset: Long, dst: ByteBuffer, length: Int): Int

    /**
     * 解密组件（libdvdcss / libaacs）经这里向光驱发认证命令（REPORT KEY / SEND KEY / READ DISC STRUCTURE）。
     * [dir]：0 无数据、1 光驱 → 主机（结果写进 [data]）、2 主机 → 光驱（发送 [data]）。
     * 返回 0 = GOOD；> 0 = CHECK CONDITION，sense 原文写进 [sense]，返回值是 sense 长度；< 0 = 没发成（光驱不在、被只读守卫拒绝、传输错误）。
     */
    fun scsi(cdb: ByteArray, dir: Int, data: ByteArray?, sense: ByteArray, timeoutMs: Int): Int

    companion object {
        const val FATAL = -2
        const val DIR_NONE = 0
        const val DIR_IN = 1
        const val DIR_OUT = 2
    }
}

/** 原生播放器的事件（在原生线程上回调，不要在这里做耗时的事）。 */
interface PlayerListener {
    fun onEvent(event: Int, a1: Int, a2: Int, text: String?)
}

/** JNI 入口（native/diskplayer/android/dp_android.c）。 */
object NativeBridge {
    private val loadError: Throwable? = try {
        System.loadLibrary("diskplayer")
        null
    } catch (t: Throwable) {
        t
    }

    val isAvailable: Boolean get() = loadError == null
    val loadErrorText: String get() = loadError?.toString() ?: ""

    /** 原生日志的去处（级别 0 调试、1 信息、2 警告、3 错误）。 */
    @Volatile
    var logger: ((level: Int, tag: String, message: String) -> Unit)? = null

    /** 原生代码回调。 */
    @JvmStatic
    fun log(level: Int, tag: String, msg: String) {
        logger?.invoke(level, tag, msg)
    }

    @JvmStatic external fun nativeVersion(): String

    @JvmStatic external fun nativeOpenDvd(
        reader: SectorReader,
        size: Long,
        listener: PlayerListener,
        regionMask: Int,
        menuLang: String,
        audioLang: String,
        spuLang: String,
        startTitle: Int,
        deinterlace: Boolean,
        prefetch: Int,
        errOut: Array<String?>,
    ): Long

    @JvmStatic external fun nativeStart(handle: Long)
    @JvmStatic external fun nativeCommand(handle: Long, cmd: Int, a1: Int, a2: Int)
    @JvmStatic external fun nativeSetSurface(handle: Long, surface: Surface?)
    @JvmStatic external fun nativeGetState(handle: Long, ints: IntArray, longs: LongArray): Array<String>?
    @JvmStatic external fun nativeClose(handle: Long)

    /** 检查导入的组件能否加载、有没有 [symbol]；null = 没问题，否则是原因。 */
    @JvmStatic external fun nativeCheckComponent(path: String, symbol: String): String?

    /** 启用 CSS 解密组件（libdvdcss）；[cacheDir] 是标题密钥缓存目录。null = 成功，否则是原因。 */
    @JvmStatic external fun nativeAttachCss(path: String, cacheDir: String): String?

    /** 停用 CSS 解密组件（删除组件之后）。 */
    @JvmStatic external fun nativeDetachCss()
}
