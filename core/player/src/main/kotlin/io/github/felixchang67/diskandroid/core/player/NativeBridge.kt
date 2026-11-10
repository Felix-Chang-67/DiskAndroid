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

    companion object {
        const val FATAL = -2
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
}
