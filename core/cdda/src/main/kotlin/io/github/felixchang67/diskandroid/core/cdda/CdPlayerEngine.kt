package io.github.felixchang67.diskandroid.core.cdda

import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.mmc.Msf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * 音频输出：44.1 kHz、16 位、立体声、小端 PCM。所有方法都只在播放器线程上调用（app 里用 AudioTrack 实现）。
 */
interface AudioOutput {
    /** 非阻塞写入，返回接受的字节数：缓冲区满时为 0，出错时小于 0。 */
    fun write(data: ByteArray, offset: Int, length: Int): Int

    fun play()

    fun pause()

    /** 丢弃已写入但还没播放的数据，并把 [playedFrames] 归零。 */
    fun flush()

    /** 数据已经全部写入：把缓冲区剩下的播完。 */
    fun drain()

    /** 自上次 [flush] 以来实际播放出去的帧数。 */
    val playedFrames: Long

    fun setVolume(volume: Float)

    fun release()
}

/** 播放器对外状态。[positionMs] 是当前轨内的位置。 */
data class PlayerState(
    val status: Status,
    val track: CdTrack?,
    val positionMs: Long,
    val bufferedMs: Long,
    val badSectors: Int,
    val byteOrder: String,
    val deemphasis: Boolean,
    val message: String?,
) {
    enum class Status(val label: String) {
        IDLE("未播放"),
        LOADING("正在读取"),
        PLAYING("播放中"),
        BUFFERING("缓冲中（光驱正在重读）"),
        PAUSED("已暂停"),
        ENDED("播放完毕"),
        STOPPED("已停止"),
        ERROR("已停止（出错）"),
    }

    /** 播放流程还在进行（可以暂停 / 跳转）。 */
    val active: Boolean get() = status == Status.LOADING || status == Status.PLAYING || status == Status.BUFFERING || status == Status.PAUSED
}

/**
 * CD 播放引擎：生产者协程按块读盘（[CddaReader]，经光驱会话线程）→ 字节序处理 / 去加重 → 有界通道（约 4 秒）
 * → 写入协程非阻塞地写进 [AudioOutput]。跨轨连续播放（无缝），播到最后一轨结尾停止。
 *
 * 所有状态只在 [dispatcher]（单线程）上访问；控制命令经互斥锁串行执行。
 * 跳转 / 换轨时取消旧流水线：正在进行的 SCSI 读取无法中断，它返回后结果被丢弃。
 */
class CdPlayerEngine(
    val album: CdAlbum,
    private val reader: CddaReader,
    private val output: AudioOutput,
    private val log: DiskLog,
    parentScope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val options: Options = Options(),
) {
    class Options(
        /** 每条 READ CD 读多少扇区：24 × 2352 = 56448 字节，0.32 秒音频。 */
        val chunkSectors: Int = 24,
        /** 通道里最多缓存几块（32 块约 10 秒，够撑过一次 BOT 复位的 6 秒等待），再加上输出缓冲。 */
        val bufferChunks: Int = 32,
        val byteOrder: ByteOrderMode = ByteOrderMode.AUTO,
        val deemphasis: Boolean = true,
        /** 播放时的读取速度（kB/s），null 表示不调整。 */
        val speedKBps: Int? = 706,
    )

    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + dispatcher + CoroutineName("cd-player"))
    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        PlayerState(PlayerState.Status.IDLE, null, 0, 0, 0, byteOrderLabel(), options.deemphasis, null),
    )
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private class Chunk(val lba: Long, val track: CdTrack, val data: ByteArray)

    private class Marker(val frame: Long, val lba: Long, val track: CdTrack)

    private var generation = 0
    private var producer: Job? = null
    private var writer: Job? = null
    private var ticker: Job? = null
    private val markers = ArrayDeque<Marker>()
    private var framesWritten = 0L
    private var paused = false
    private var status = PlayerState.Status.IDLE
    private var message: String? = null
    /** 还没有数据写出时显示的位置（刚开始播放 / 刚跳转）。 */
    private var pendingTrack: CdTrack? = null
    private var pendingLba = 0L
    private var lastLoggedTrack: CdTrack? = null
    private var badSectors = 0
    private var bigEndian: Boolean? = when (options.byteOrder) {
        ByteOrderMode.AUTO -> null
        ByteOrderMode.LITTLE -> false
        ByteOrderMode.BIG -> true
    }
    private var speedApplied = false

    // ---------------- 控制 ----------------

    /** 从第 [trackNumber] 轨的 [positionMs] 处开始播放（默认当前轨 / 第一轨）。 */
    fun play(trackNumber: Int? = null, positionMs: Long = 0) = post {
        val t = trackNumber?.let { album.track(it) }
            ?: currentTrack()?.takeIf { status != PlayerState.Status.ENDED }
            ?: album.first
        paused = false
        startAt(t, lbaFor(t, positionMs))
    }

    fun pause() = post {
        if (_state.value.active && !paused) {
            paused = true
            output.pause()
            publish()
        }
    }

    fun resume() = post {
        when {
            paused -> {
                paused = false
                output.play()
                publish()
            }
            !_state.value.active -> {
                val t = currentTrack()?.takeIf { status != PlayerState.Status.ENDED } ?: album.first
                startAt(t, t.startLba)
            }
        }
    }

    fun togglePause() = post {
        if (_state.value.active && !paused) {
            paused = true
            output.pause()
            publish()
        } else if (paused) {
            paused = false
            output.play()
            publish()
        } else {
            val t = currentTrack()?.takeIf { status != PlayerState.Status.ENDED } ?: album.first
            startAt(t, t.startLba)
        }
    }

    fun next() = post {
        val t = currentTrack() ?: return@post
        album.next(t)?.let { startAt(it, it.startLba) }
    }

    /** 当前轨已播放超过 3 秒：回到本轨开头；否则上一轨。 */
    fun previous() = post {
        val t = currentTrack() ?: return@post
        val p = album.previous(t)
        if (positionInTrack() > 3_000 || p == null) startAt(t, t.startLba) else startAt(p, p.startLba)
    }

    fun seekTo(positionMs: Long) = post {
        val t = currentTrack() ?: return@post
        startAt(t, lbaFor(t, positionMs))
    }

    fun stop() = post { stopInternal(PlayerState.Status.STOPPED, null) }

    /** 音频焦点被临时占用（导航播报等）时压低音量。 */
    fun setDucked(ducked: Boolean) = post { output.setVolume(if (ducked) 0.2f else 1f) }

    /** 释放：停止播放、恢复光驱读取速度、释放音频输出。之后引擎不可再用。 */
    fun release() {
        scope.launch {
            mutex.withLock {
                if (_state.value.active) stopInternal(PlayerState.Status.STOPPED, null) else restoreSpeed()
                output.release()
            }
            scope.cancel()
        }
    }

    /** 外部原因（盘片被换掉、光驱断开）导致停止。 */
    fun abort(reason: String) = post { if (_state.value.active) stopInternal(PlayerState.Status.ERROR, reason) }

    private fun post(block: suspend () -> Unit) {
        scope.launch { mutex.withLock { block() } }
    }

    // ---------------- 流水线 ----------------

    private suspend fun startAt(track: CdTrack, lba: Long) {
        cancelPipeline()
        if (!speedApplied && options.speedKBps != null) {
            speedApplied = true
            reader.setSpeed(options.speedKBps)
        }
        val gen = ++generation
        status = PlayerState.Status.LOADING
        message = null
        pendingTrack = track
        pendingLba = lba
        log.i(TAG, "开始播放第 ${track.number} 轨「${track.title}」", "位置" to formatMs((lba - track.startLba) * 1000 / SECTORS_PER_SECOND), "LBA" to lba)
        lastLoggedTrack = track
        val channel = Channel<Chunk>(options.bufferChunks)
        producer = scope.launch { produce(gen, track, lba, channel) }
        writer = scope.launch { consume(gen, channel) }
        if (!paused) output.play()
        if (ticker == null) ticker = scope.launch { tick() }
        publish()
    }

    private suspend fun cancelPipeline() {
        generation++ // 旧流水线晚到的通知一律作废
        producer?.cancel()
        producer = null
        writer?.cancelAndJoin()
        writer = null
        output.pause()
        output.flush()
        markers.clear()
        framesWritten = 0
    }

    private suspend fun stopInternal(newStatus: PlayerState.Status, reason: String?) {
        cancelPipeline()
        ticker?.cancel()
        ticker = null
        paused = false
        status = newStatus
        message = reason
        if (reason != null) log.w(TAG, "播放停止：$reason") else log.i(TAG, "播放${newStatus.label}")
        logStats()
        restoreSpeed()
        publish()
    }

    /** 不播放时把光驱读取速度恢复为最大值（不影响之后的其他读取）。 */
    private suspend fun restoreSpeed() {
        if (!speedApplied) return
        speedApplied = false
        runCatching { reader.setSpeed(0xFFFF) }
    }

    private suspend fun produce(gen: Int, startTrack: CdTrack, startLba: Long, ch: SendChannel<Chunk>) {
        var track: CdTrack? = startTrack
        var lba = startLba
        val deemph = Deemphasis()
        try {
            while (track != null) {
                val t: CdTrack = track
                deemph.reset()
                while (lba < t.endLba) {
                    val count = minOf(options.chunkSectors.toLong(), t.endLba - lba).toInt()
                    val buf = ByteArray(count * SECTOR_BYTES)
                    val r = reader.read(lba, count, buf)
                    coroutineContext.ensureActive()
                    when (r) {
                        CddaReader.Result.Ok -> Unit
                        is CddaReader.Result.Partial -> {
                            badSectors += r.bad.size
                            log.w(TAG, "第 ${t.number} 轨 ${formatMs((r.bad.first() - t.startLba) * 1000 / SECTORS_PER_SECOND)} 附近 ${r.bad.size} 个扇区读不出来，已静音", "范围" to CddaReader.ranges(r.bad))
                        }
                        is CddaReader.Result.Stop -> {
                            val reason = "${r.reason.message}（${r.detail}）"
                            post { if (gen == generation) stopInternal(PlayerState.Status.ERROR, reason) }
                            return
                        }
                    }
                    process(buf, t, deemph)
                    ch.send(Chunk(lba, t, buf))
                    lba += count
                }
                track = album.next(t)
                lba = track?.startLba ?: break
            }
        } finally {
            ch.close()
        }
    }

    private fun process(buf: ByteArray, track: CdTrack, deemph: Deemphasis) {
        if (bigEndian == null) {
            ByteOrderDetector.detect(buf)?.let { be ->
                bigEndian = be
                log.i(TAG, "CD-DA 字节序检测结果：${if (be) "大端（需要交换字节）" else "小端"}", "LBA" to Msf.format(pendingLba))
            }
        }
        if (bigEndian == true) ByteOrderDetector.swap(buf)
        if (track.preEmphasis && options.deemphasis) deemph.process(buf)
    }

    private suspend fun consume(gen: Int, ch: ReceiveChannel<Chunk>) {
        for (chunk in ch) {
            markers.addLast(Marker(framesWritten, chunk.lba, chunk.track))
            var off = 0
            while (off < chunk.data.size) {
                if (paused) {
                    delay(PAUSED_POLL_MS)
                    continue
                }
                val n = output.write(chunk.data, off, chunk.data.size - off)
                when {
                    n < 0 -> {
                        post { if (gen == generation) stopInternal(PlayerState.Status.ERROR, "音频输出出错（$n）") }
                        return
                    }
                    n == 0 -> delay(WRITE_POLL_MS)
                    else -> off += n
                }
            }
            framesWritten += chunk.data.size / BYTES_PER_FRAME
        }
        // 生产者已结束：数据全部写入，把剩下的播完（不计暂停时间，最多再等"剩余时长 + 3 秒"，防止输出端卡住时永远等下去）
        output.drain()
        val tailMs = (framesWritten - output.playedFrames).coerceAtLeast(0) * 1000 / 44_100
        var waited = 0L
        while (output.playedFrames < framesWritten && waited < tailMs + 3_000) {
            delay(END_POLL_MS)
            if (!paused) waited += END_POLL_MS
        }
        post {
            if (gen == generation && status != PlayerState.Status.ERROR) {
                ticker?.cancel()
                ticker = null
                status = PlayerState.Status.ENDED
                log.i(TAG, "专辑播放完毕")
                logStats()
                restoreSpeed()
                publish()
            }
        }
    }

    private suspend fun tick() {
        while (true) {
            publish()
            delay(TICK_MS)
        }
    }

    // ---------------- 位置与状态 ----------------

    private fun position(): Pair<CdTrack, Long>? {
        val played = output.playedFrames
        while (markers.size > 1 && markers[1].frame <= played) markers.removeFirst()
        val m = markers.firstOrNull() ?: return pendingTrack?.let { it to pendingLba }
        val lba = m.lba + (played - m.frame).coerceAtLeast(0) / FRAMES_PER_SECTOR
        return m.track to minOf(lba, m.track.endLba - 1)
    }

    private fun currentTrack(): CdTrack? = position()?.first

    private fun positionInTrack(): Long = position()?.let { (t, lba) -> (lba - t.startLba) * 1000 / SECTORS_PER_SECOND } ?: 0

    private fun lbaFor(t: CdTrack, positionMs: Long): Long =
        t.startLba + (positionMs.coerceIn(0, maxOf(0, t.durationMs - 1)) * SECTORS_PER_SECOND / 1000)

    private fun publish() {
        val pos = position()
        val played = output.playedFrames
        val active = status == PlayerState.Status.LOADING || status == PlayerState.Status.PLAYING || status == PlayerState.Status.BUFFERING
        if (active) {
            status = when {
                markers.isEmpty() && framesWritten == 0L -> PlayerState.Status.LOADING
                played >= framesWritten && producer?.isActive == true -> PlayerState.Status.BUFFERING
                else -> PlayerState.Status.PLAYING
            }
        }
        val shown = if (paused && _state.value.active) PlayerState.Status.PAUSED else status
        pos?.first?.let { t ->
            if (t !== lastLoggedTrack && shown == PlayerState.Status.PLAYING) {
                lastLoggedTrack = t
                log.i(TAG, "进入第 ${t.number} 轨「${t.title}」")
            }
        }
        _state.value = PlayerState(
            status = shown,
            track = pos?.first,
            positionMs = pos?.let { (t, lba) -> (lba - t.startLba) * 1000 / SECTORS_PER_SECOND } ?: 0,
            bufferedMs = (framesWritten - played).coerceAtLeast(0) * 1000 / 44_100,
            badSectors = badSectors,
            byteOrder = byteOrderLabel(),
            deemphasis = options.deemphasis,
            message = message,
        )
    }

    private fun byteOrderLabel(): String = when (options.byteOrder) {
        ByteOrderMode.AUTO -> when (bigEndian) {
            null -> "自动（尚未检测）"
            true -> "大端（自动检测）"
            false -> "小端（自动检测）"
        }
        ByteOrderMode.LITTLE -> "小端（手动）"
        ByteOrderMode.BIG -> "大端（手动）"
    }

    private fun logStats() {
        log.i(TAG, "读取统计", "READ CD 命令" to reader.commands, "重试" to reader.retries, "静音扇区" to badSectors, "字节序" to byteOrderLabel())
    }

    companion object {
        const val TAG = "PLAYER"
        private const val WRITE_POLL_MS = 10L
        private const val PAUSED_POLL_MS = 50L
        private const val END_POLL_MS = 50L
        private const val TICK_MS = 250L
    }
}
