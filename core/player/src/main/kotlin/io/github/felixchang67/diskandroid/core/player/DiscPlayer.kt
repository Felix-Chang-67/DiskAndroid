package io.github.felixchang67.diskandroid.core.player

import android.view.Surface
import kotlin.concurrent.read
import kotlin.concurrent.write
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * 原生光盘播放器：DVD（libdvdnav 导航）和蓝光（libbluray 导航），FFmpeg 解码，OpenGL ES 画面，AAudio 声音。
 * 支持光盘自己的首播、菜单（DVD 按钮高亮、蓝光顶层 / 弹出菜单；方向键、点按）、标题 / 章节跳转、音轨 / 字幕 / 角度切换。
 * 蓝光的 BD-J（Java）菜单在 Android 上做不了，这类盘直接播放主片。
 *
 * 线程：命令不阻塞（排队给原生导航线程）；[close] 会等原生线程结束（可能要等一次读盘完成），不要在主线程调用。
 */
class DiscPlayer private constructor(private var handle: Long) : AutoCloseable {

    /** 打开参数。语言是两个字母的 ISO 639-1 代码。 */
    data class Options(
        /** 播放器地区（libdvdnav 的 SPRM20：第 n 位 = 地区 n+1），0 = 用默认值。只影响光盘程序里的地区判断，不改光驱。 */
        val regionMask: Int = 0,
        val menuLanguage: String = "ja",
        val audioLanguage: String = "ja",
        val subtitleLanguage: String = "zh",
        /** 0 = 从光盘自己的首播开始（片头、菜单）；> 0 = 直接播放这个标题。 */
        val startTitle: Int = 0,
        val deinterlace: Boolean = true,
        /** 每次向光驱要多少字节（光驱每条命令开销大，一次多读一些）。 */
        val prefetchBytes: Int = 256 * 1024,
    )

    /** 蓝光的打开参数。语言是三个字母的 ISO 639-2 代码。 */
    data class BlurayOptions(
        val audioLanguage: String = "jpn",
        val subtitleLanguage: String = "chi",
        val menuLanguage: String = "jpn",
        /** 播放器地区：A = 1（日本、北美、东亚除中国大陆）、B = 2、C = 4。 */
        val region: Int = 1,
        /** < 0 = 用光盘菜单；>= 0 = 直接播放这个播放列表（xxxxx.mpls）。 */
        val startPlaylist: Int = -1,
        val deinterlace: Boolean = true,
        val prefetchBytes: Int = 512 * 1024,
    )

    enum class Kind { DVD, BLURAY }

    enum class Domain(val label: String) {
        STOP("停止"),
        FIRST_PLAY("首播"),
        VMG_MENU("主菜单"),
        VTS_MENU("标题菜单"),
        TITLE("正片"),
    }

    data class State(
        val domain: Domain = Domain.STOP,
        val title: Int = 0,
        val chapter: Int = 0,
        val titles: Int = 0,
        val chapters: Int = 0,
        val buttons: Int = 0,
        val button: Int = 0,
        val still: Boolean = false,
        val paused: Boolean = false,
        val ended: Boolean = false,
        /** 音轨说明（"ja AC-3 2ch"），按逻辑序号。 */
        val audio: List<String> = emptyList(),
        /** 当前音轨的逻辑序号，-1 = 无。 */
        val audioCurrent: Int = -1,
        /** 正在解码的流（0x80 AC-3、0x88 DTS、0xA0 LPCM、0xC0 MPEG），-1 = 无。 */
        val audioStream: Int = -1,
        /** 字幕语言（"ja"），按逻辑序号。 */
        val subtitles: List<String> = emptyList(),
        val subtitleCurrent: Int = -1,
        val subtitleShown: Boolean = false,
        val angles: Int = 1,
        val angle: Int = 1,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val wide: Boolean = false,
        /** 视频在 Surface 里实际显示的区域大小（像素，居中），和解码帧的尺寸：把触摸点换算成菜单坐标用。 */
        val displayWidth: Int = 0,
        val displayHeight: Int = 0,
        val frameWidth: Int = 0,
        val frameHeight: Int = 0,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val bytesRead: Long = 0,
        val readErrors: Long = 0,
        val framesShown: Long = 0,
        val framesDropped: Long = 0,
        val audioFrames: Long = 0,
        val kind: Kind = Kind.DVD,
        /** 蓝光：1 = 光盘菜单导航，2 = 直接播放播放列表。 */
        val bdMode: Int = 0,
        /** 蓝光：有弹出菜单可用。 */
        val popup: Boolean = false,
        /** 蓝光：当前播放列表（xxxxx.mpls）。 */
        val playlist: Int = -1,
        /** 蓝光：光盘菜单是 BD-J，没法用，在直接播放主片。 */
        val bdj: Boolean = false,
        val planeWidth: Int = 0,
        val planeHeight: Int = 0,
        /** 正在用的解码器名（FFmpeg 的名字："h264"、"ac3"…）。 */
        val videoCodec: String = "",
        val audioCodec: String = "",
        val error: String = "",
        /** 视频实际的显示帧率（去隔行后；软下拉的电影是 23.976），0 = 还不知道。界面据此挑屏幕刷新率。 */
        val videoFps: Double = 0.0,
    ) {
        /** 正在菜单里（有可以选的按钮 / 蓝光的交互菜单显示中）。 */
        val inMenu: Boolean get() = buttons > 0
        val isBluray: Boolean get() = kind == Kind.BLURAY
    }

    companion object {
        // 命令（与 dp_dvd.h 的 DP_CMD_* 一致）
        const val CMD_UP = 1
        const val CMD_DOWN = 2
        const val CMD_LEFT = 3
        const val CMD_RIGHT = 4
        const val CMD_ACTIVATE = 5
        const val CMD_MOUSE_SELECT = 6
        const val CMD_MOUSE_ACTIVATE = 7
        const val CMD_MENU = 8
        const val CMD_TITLE_PLAY = 9
        const val CMD_PART_PLAY = 10
        const val CMD_NEXT_CHAPTER = 11
        const val CMD_PREV_CHAPTER = 12
        const val CMD_SEEK_MS = 13
        const val CMD_AUDIO = 14
        const val CMD_SUBTITLE = 15
        const val CMD_ANGLE = 16
        const val CMD_PAUSE = 17
        const val CMD_RESUME = 18
        const val CMD_GO_UP = 19
        const val CMD_STILL_SKIP = 20

        // 菜单（libdvdnav 的 DVDMenuID_t）
        const val MENU_TITLE = 2
        const val MENU_ROOT = 3
        const val MENU_SUBTITLE = 4
        const val MENU_AUDIO = 5
        const val MENU_ANGLE = 6
        const val MENU_CHAPTER = 7

        // 蓝光的 CMD_MENU 参数：顶层菜单 / 弹出菜单（开关）
        const val BD_MENU_TOP = 0
        const val BD_MENU_POPUP = 1

        // 蓝光的 State.bdMode（与 dp_bd.h 的 DP_BD_MODE_* 一致）
        const val BD_MODE_STOP = 0
        const val BD_MODE_MENU = 1
        const val BD_MODE_PLAYLIST = 2

        // 事件（与 dp_core.h 的 DP_EV_* 一致）
        const val EV_STATE = 1
        const val EV_STILL = 2
        const val EV_MENU = 3
        const val EV_END = 4
        const val EV_ERROR = 5
        const val EV_FIRST_FRAME = 6

        private const val INTS = 34
        private const val LONGS = 7

        /**
         * 打开一张 DVD-Video（读 IFO、准备 libdvdnav）。还不开始播放，先 [setSurface] 再 [start]。
         * @param sizeBytes 盘的大小（READ CAPACITY × 2048）
         * @throws PlayerException 原生库不可用、不是 DVD-Video、IFO 读不出
         */
        fun open(reader: SectorReader, sizeBytes: Long, listener: PlayerListener, options: Options): DiscPlayer {
            if (!NativeBridge.isAvailable) throw PlayerException("播放器原生库加载失败：${NativeBridge.loadErrorText}")
            val err = arrayOfNulls<String>(1)
            val h = NativeBridge.nativeOpenDvd(
                reader, sizeBytes, listener, options.regionMask,
                options.menuLanguage, options.audioLanguage, options.subtitleLanguage,
                options.startTitle, options.deinterlace, options.prefetchBytes, err,
            )
            if (h == 0L) throw PlayerException(err[0] ?: "打不开这张 DVD")
            return DiscPlayer(h)
        }

        /**
         * 打开一张蓝光（读 index.bdmv / 播放列表，检查加密）。还不开始播放，先 [setSurface] 再 [start]。
         * @throws PlayerException 原生库不可用、不是蓝光、需要解密、播放列表打不开
         */
        fun openBluray(reader: SectorReader, sizeBytes: Long, listener: PlayerListener, options: BlurayOptions): DiscPlayer {
            if (!NativeBridge.isAvailable) throw PlayerException("播放器原生库加载失败：${NativeBridge.loadErrorText}")
            val err = arrayOfNulls<String>(1)
            val h = NativeBridge.nativeOpenBluray(
                reader, sizeBytes, listener, options.audioLanguage, options.subtitleLanguage, options.menuLanguage,
                options.region, options.startPlaylist, options.deinterlace, options.prefetchBytes, err,
            )
            if (h == 0L) throw PlayerException(err[0] ?: "打不开这张蓝光")
            return DiscPlayer(h)
        }

        /** 原生代码给的状态数组 → [State]。 */
        internal fun parseState(ints: IntArray, longs: LongArray, strings: Array<String>?): State {
            val audioCount = ints[10]
            val spuCount = ints[12]
            val s = strings ?: emptyArray()
            val extra = s.drop(audioCount + spuCount)
            return State(
                domain = Domain.entries.getOrElse(ints[0]) { Domain.STOP },
                title = ints[1],
                chapter = ints[2],
                titles = ints[3],
                chapters = ints[4],
                buttons = ints[5],
                button = ints[6],
                still = ints[7] != 0,
                paused = ints[8] != 0,
                ended = ints[9] != 0,
                audio = s.take(audioCount),
                audioCurrent = ints[11],
                subtitles = s.drop(audioCount).take(spuCount),
                subtitleCurrent = ints[13],
                subtitleShown = ints[14] != 0,
                angles = ints[15].coerceAtLeast(1),
                angle = ints[16].coerceAtLeast(1),
                videoWidth = ints[17],
                videoHeight = ints[18],
                wide = ints[19] != 0,
                displayWidth = ints[20],
                displayHeight = ints[21],
                frameWidth = ints[22],
                frameHeight = ints[23],
                audioStream = ints[24],
                positionMs = longs[0],
                durationMs = longs[1],
                bytesRead = longs[2],
                readErrors = longs[3],
                framesShown = longs[4],
                framesDropped = longs[5],
                audioFrames = longs[6],
                kind = if (ints[25] == 1) Kind.BLURAY else Kind.DVD,
                bdMode = ints[26],
                popup = ints[28] != 0,
                playlist = if (ints[25] == 1) ints[29] else -1,
                bdj = ints[30] != 0,
                planeWidth = ints[31],
                planeHeight = ints[32],
                videoFps = ints.getOrElse(33) { 0 } / 1000.0,
                videoCodec = extra.getOrElse(0) { "" },
                audioCodec = extra.getOrElse(1) { "" },
                error = extra.getOrElse(2) { "" },
            )
        }

        /** 原生库版本（库加载失败时为 null）。 */
        fun version(): String? = if (NativeBridge.isAvailable) runCatching { NativeBridge.nativeVersion() }.getOrNull() else null
    }

    /** 关闭要等所有进行中的原生调用结束（写锁），之后句柄作废。 */
    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var closing = false

    val isClosed: Boolean get() = closing

    /** 启动导航、视频、音频线程。 */
    fun start() = withHandle { NativeBridge.nativeStart(it) }

    fun command(cmd: Int, a1: Int = 0, a2: Int = 0) = withHandle { NativeBridge.nativeCommand(it, cmd, a1, a2) }

    /** 设置 / 去掉画面输出。去掉（null）时会等视频线程放开旧 Surface（最多 0.5 秒），在 surfaceDestroyed 里调用。 */
    fun setSurface(surface: Surface?) = withHandle { NativeBridge.nativeSetSurface(it, surface) }

    fun state(): State {
        val ints = IntArray(INTS)
        val longs = LongArray(LONGS)
        var strings: Array<String>? = null
        val ok = withHandle { strings = NativeBridge.nativeGetState(it, ints, longs) }
        return if (ok) parseState(ints, longs, strings) else State(ended = true)
    }

    private inline fun withHandle(block: (Long) -> Unit): Boolean {
        if (closing) return false
        lock.read {
            if (handle == 0L) return false
            block(handle)
        }
        return true
    }

    /** 停止并释放（会等原生线程结束，可能要等一次读盘完成：不要在主线程调用）。 */
    override fun close() {
        closing = true
        lock.write {
            val h = handle
            handle = 0
            if (h != 0L) NativeBridge.nativeClose(h)
        }
    }
}

class PlayerException(message: String) : Exception(message)
