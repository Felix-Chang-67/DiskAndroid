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
        /** 手动点歌、跳转、换轨时的淡入毫秒数（防止爆音；测试里设 0 以便逐字节比对）。 */
        val shortFadeMs: Int = SHORT_FADE_MS,
        /**
         * 播放中手动切歌 / 跳转的接缝（毫秒）：旧的声音一直播到新位置的数据就绪，然后直接接上（gapless，不插静音）。
         * 接缝处只做这么短的平滑，防止波形突变"啪"一声，听起来就是直接切过去。0 表示不做无缝切换（先停下、再从新位置开始）。
         */
        val crossfadeMs: Int = SWITCH_SPLICE_MS,
        /** 预读下一首开头多少扇区（按"下一首"时直接用，不等光驱寻道）。192 扇区约 2.6 秒。 */
        val prefetchSectors: Int = 192,
    )

    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + dispatcher + CoroutineName("cd-player"))
    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        PlayerState(PlayerState.Status.IDLE, null, 0, 0, 0, byteOrderLabel(), options.deemphasis, null),
    )
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private class Chunk(val lba: Long, val track: CdTrack, val data: ByteArray)

    private class Marker(val frame: Long, val lba: Long, val track: CdTrack)

    /** 等待衔接的新位置：新生产者把数据读进 [channel]，写入协程拿到第一块后直接接上。 */
    private class Switch(val gen: Int, val channel: Channel<Chunk>, val track: CdTrack, val lba: Long)

    /** 预读好的"下一首开头"（已经做过字节序 / 去加重处理；[deemph] 是接着往下处理用的滤波器状态）。 */
    private class Prefetch(val track: CdTrack, val chunks: List<Chunk>, val endLba: Long, val deemph: Deemphasis)

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
    private var pendingSwitch: Switch? = null
    private var prefetched: Prefetch? = null
    /** 从按下切歌到新歌真正响起之间，界面显示新位置（避免进度在新旧两首之间来回跳）。 */
    private var switchDisplay: Pair<CdTrack, Long>? = null
    /** 交叉淡化那一段在输出流里的起点（帧）；播放头过了这里就改按实际位置显示。 */
    private var switchAudibleFrame = -1L
    private var badSectors = 0
    private var bigEndian: Boolean? = when (options.byteOrder) {
        ByteOrderMode.AUTO -> null
        ByteOrderMode.LITTLE -> false
        ByteOrderMode.BIG -> true
    }
    private var speedApplied = false

    // ---------------- 控制 ----------------

    /**
     * 从第 [trackNumber] 轨的 [positionMs] 处开始播放（默认当前轨 / 第一轨）。
     * [fadeInMs]：开头的淡入时长（自动播放用 1 秒；手动点歌、跳转用很短的淡入防止爆音）。
     */
    fun play(trackNumber: Int? = null, positionMs: Long = 0, fadeInMs: Int = options.shortFadeMs) = post {
        val t = trackNumber?.let { album.track(it) }
            ?: currentTrack()?.takeIf { status != PlayerState.Status.ENDED }
            ?: album.first
        paused = false
        switchOrStart(t, lbaFor(t, positionMs), fadeInMs)
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
        album.next(t)?.let { switchOrStart(it, it.startLba) }
    }

    /** 当前轨已播放超过 3 秒：回到本轨开头；否则上一轨。 */
    fun previous() = post {
        val t = currentTrack() ?: return@post
        val p = album.previous(t)
        if (positionInTrack() > 3_000 || p == null) switchOrStart(t, t.startLba) else switchOrStart(p, p.startLba)
    }

    fun seekTo(positionMs: Long) = post {
        val t = currentTrack() ?: return@post
        switchOrStart(t, lbaFor(t, positionMs))
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

    /**
     * 正在出声时手动切歌 / 跳转：无缝切换——停掉旧的生产者，但已经缓冲的旧音频继续播；新生产者从新位置读，
     * 写入协程拿到新数据后用 [Options.crossfadeMs] 的交叉淡化接上，中间没有静音。
     * 暂停中、还没出声、或关闭了交叉淡化时，退回普通的"停下再开始"。
     */
    private suspend fun switchOrStart(track: CdTrack, lba: Long, fadeInMs: Int = options.shortFadeMs) {
        val audible = !paused && writer?.isActive == true && framesWritten > 0 &&
            (status == PlayerState.Status.PLAYING || status == PlayerState.Status.BUFFERING)
        if (options.crossfadeMs <= 0 || !audible) return startAt(track, lba, fadeInMs)
        producer?.cancel()
        pendingSwitch?.channel?.cancel()
        val gen = ++generation
        val channel = Channel<Chunk>(options.bufferChunks)
        pendingSwitch = Switch(gen, channel, track, lba)
        switchDisplay = track to lba
        switchAudibleFrame = -1
        val pre = takePrefetch(track, lba)
        pre?.chunks?.forEach { channel.trySend(it) }
        producer = scope.launch { produce(gen, track, pre?.endLba ?: lba, channel, 0, pre?.deemph) }
        log.i(
            TAG, "无缝切换到第 ${track.number} 轨「${track.title}」", "位置" to formatMs((lba - track.startLba) * 1000 / SECTORS_PER_SECOND), "LBA" to lba,
            "预读" to (pre?.let { "已有开头 ${(it.endLba - lba) * 1000 / SECTORS_PER_SECOND} ms，直接接上" } ?: "无，等光驱读到"),
        )
        lastLoggedTrack = track
        publish()
    }

    private suspend fun startAt(track: CdTrack, lba: Long, fadeInMs: Int = options.shortFadeMs) {
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
        // 暂停中按"下一首"等：有预读就直接用（这时不做起播淡入，预读的数据已经处理好）
        val pre = if (fadeInMs <= options.shortFadeMs) takePrefetch(track, lba) else null
        pre?.chunks?.forEach { channel.trySend(it) }
        producer = scope.launch { produce(gen, track, pre?.endLba ?: lba, channel, if (pre != null) 0 else fadeInMs * 44_100 / 1000, pre?.deemph) }
        writer = scope.launch { consume(gen, channel) }
        if (!paused) output.play()
        if (ticker == null) ticker = scope.launch { tick() }
        publish()
    }

    private suspend fun cancelPipeline() {
        generation++ // 旧流水线晚到的通知一律作废
        producer?.cancel()
        producer = null
        pendingSwitch?.channel?.cancel()
        pendingSwitch = null
        switchDisplay = null
        switchAudibleFrame = -1
        writer?.cancelAndJoin()
        writer = null
        output.pause()
        output.flush()
        markers.clear()
        framesWritten = 0
    }

    private suspend fun stopInternal(newStatus: PlayerState.Status, reason: String?) {
        cancelPipeline()
        prefetched = null
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

    private suspend fun produce(gen: Int, startTrack: CdTrack, startLba: Long, ch: SendChannel<Chunk>, fadeFrames: Int, initialDeemph: Deemphasis? = null) {
        var track: CdTrack? = startTrack
        var lba = startLba
        val deemph = initialDeemph ?: Deemphasis()
        var faded = 0
        // 接着预读的数据往下读时沿用预读时的滤波器状态
        var lastEmphasis: Boolean? = if (initialDeemph != null) startTrack.preEmphasis else null
        try {
            while (track != null) {
                val t: CdTrack = track
                // 连续两首处理方式相同时滤波器状态不清零：曲目交界处保持连续（Live 专辑首尾相连不会"咔"一声）
                if (t.preEmphasis != lastEmphasis) {
                    deemph.reset()
                    lastEmphasis = t.preEmphasis
                }
                while (lba < t.endLba) {
                    val count = minOf(options.chunkSectors.toLong(), t.endLba - lba).toInt()
                    val buf = ByteArray(count * SECTOR_BYTES)
                    val r = reader.read(lba, count, buf)
                    coroutineContext.ensureActive()
                    var silenced: List<Long> = emptyList()
                    when (r) {
                        CddaReader.Result.Ok -> Unit
                        is CddaReader.Result.Partial -> {
                            badSectors += r.bad.size
                            silenced = r.bad
                            log.w(TAG, "第 ${t.number} 轨 ${formatMs((r.bad.first() - t.startLba) * 1000 / SECTORS_PER_SECOND)} 附近 ${r.bad.size} 个扇区读不出来，已静音", "范围" to CddaReader.ranges(r.bad))
                        }
                        is CddaReader.Result.Stop -> {
                            val reason = "${r.reason.message}（${r.detail}）"
                            post { if (gen == generation) stopInternal(PlayerState.Status.ERROR, reason) }
                            return
                        }
                    }
                    process(buf, t, deemph)
                    if (silenced.isNotEmpty()) PcmGain.fadeAroundSilence(buf, lba, silenced)
                    if (faded < fadeFrames) {
                        // 起播淡入：增益从 faded/fadeFrames 线性升到 1
                        val frames = buf.size / BYTES_PER_FRAME
                        val n = minOf(frames, fadeFrames - faded)
                        PcmGain.ramp(buf, 0, n, faded.toDouble() / fadeFrames, (faded + n).toDouble() / fadeFrames)
                        faded += n
                    }
                    val chunk = Chunk(lba, t, buf)
                    if (ch.trySend(chunk).isFailure) {
                        // 缓冲已满（约 10 秒）：趁光驱空闲预读一块"下一首"的开头，然后再等通道空位
                        prefetchStep(gen)
                        ch.send(chunk)
                    }
                    lba += count
                }
                track = album.next(t)
                lba = track?.startLba ?: break
            }
        } finally {
            ch.close()
        }
    }

    /**
     * 预读一块"正在播放的这一首的下一首"的开头（每次只读一块，读够 [Options.prefetchSectors] 为止）。
     * 预读失败不影响播放（真正读到那里时由生产者照常处理错误）。
     */
    private suspend fun prefetchStep(gen: Int) {
        if (gen != generation || options.prefetchSectors <= 0) return
        val playing = position()?.first ?: return
        val next = album.next(playing) ?: return
        val have = prefetched?.takeIf { it.track === next }
        val target = minOf(next.startLba + options.prefetchSectors, next.endLba)
        val from = have?.endLba ?: next.startLba
        if (from >= target) return
        val count = minOf(options.chunkSectors.toLong(), target - from).toInt()
        val buf = ByteArray(count * SECTOR_BYTES)
        when (val r = reader.read(from, count, buf)) {
            CddaReader.Result.Ok -> Unit
            is CddaReader.Result.Partial -> PcmGain.fadeAroundSilence(buf, from, r.bad)
            is CddaReader.Result.Stop -> return
        }
        if (gen != generation) return
        val deemph = have?.deemph ?: Deemphasis()
        process(buf, next, deemph)
        prefetched = Prefetch(next, have?.chunks.orEmpty() + Chunk(from, next, buf), from + count, deemph)
        if (from + count >= target) log.v(TAG, "已预读第 ${next.number} 轨开头 ${(target - next.startLba) * 1000 / SECTORS_PER_SECOND} ms")
    }

    /** 要从 [lba] 播 [track]，而且正好预读过这一首的开头：取出来用（用过即清）。 */
    private fun takePrefetch(track: CdTrack, lba: Long): Prefetch? {
        val p = prefetched ?: return null
        prefetched = null
        return p.takeIf { it.track === track && lba == track.startLba && it.chunks.isNotEmpty() }
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

    /**
     * 写入协程：把块非阻塞地写进输出。自然播到下一首时数据本来就是连续的（gapless，不插静音）；
     * 手动切歌时继续写旧数据，直到新位置的第一块到达，再直接接上（接缝做 10 ms 平滑）。
     */
    private suspend fun consume(gen0: Int, ch0: ReceiveChannel<Chunk>) {
        var gen = gen0
        var ch = ch0
        var cur: Chunk? = null
        var off = 0
        var draining = false
        var tailMs = 0L
        var waited = 0L
        while (true) {
            val sw = pendingSwitch
            if (sw != null && !paused) {
                val first = sw.channel.tryReceive().getOrNull()
                if (first != null) {
                    val mixed = crossfade(cur, off, ch, first)
                    ch.cancel()
                    ch = sw.channel
                    gen = sw.gen
                    pendingSwitch = null
                    draining = false
                    cur = Chunk(first.lba, first.track, mixed)
                    off = 0
                    switchAudibleFrame = framesWritten
                    markers.addLast(Marker(framesWritten, first.lba, first.track))
                    continue
                }
            }
            if (cur == null) {
                val r = ch.tryReceive()
                val next = r.getOrNull()
                when {
                    next != null -> {
                        cur = next
                        off = 0
                        markers.addLast(Marker(framesWritten, next.lba, next.track))
                    }
                    r.isClosed && pendingSwitch == null -> {
                        // 数据全部写完：把剩下的播完（不计暂停时间，最多再等"剩余时长 + 3 秒"，防止输出端卡住时永远等下去）；期间仍可切歌
                        if (!draining) {
                            draining = true
                            output.drain()
                            tailMs = (framesWritten - output.playedFrames).coerceAtLeast(0) * 1000 / 44_100
                            waited = 0
                        }
                        if (output.playedFrames >= framesWritten || waited >= tailMs + 3_000) {
                            val endedGen = gen
                            post {
                                if (endedGen == generation && status != PlayerState.Status.ERROR) {
                                    ticker?.cancel()
                                    ticker = null
                                    status = PlayerState.Status.ENDED
                                    log.i(TAG, "专辑播放完毕")
                                    logStats()
                                    restoreSpeed()
                                    publish()
                                }
                            }
                            return
                        }
                        delay(END_POLL_MS)
                        if (!paused) waited += END_POLL_MS
                    }
                    else -> delay(WRITE_POLL_MS) // 暂时没有数据：欠载，或者旧数据已经播完、正在等新位置的数据
                }
                continue
            }
            if (paused) {
                delay(PAUSED_POLL_MS)
                continue
            }
            val n = output.write(cur.data, off, cur.data.size - off)
            when {
                n < 0 -> {
                    val errGen = gen
                    post { if (errGen == generation) stopInternal(PlayerState.Status.ERROR, "音频输出出错（$n）") }
                    return
                }
                n == 0 -> delay(WRITE_POLL_MS)
                else -> {
                    off += n
                    framesWritten += n / BYTES_PER_FRAME
                    if (off >= cur.data.size) cur = null
                }
            }
        }
    }

    /**
     * 接缝：取"旧音频接下来还没写出的一小段"（[Options.crossfadeMs]，默认 10 ms），与新位置开头逐帧混合，
     * 旧的增益从 1 线性降到 0、新的从 0 升到 1，只为消除波形突变的"啪"声；之后完全是新位置的数据（gapless，不插静音）。
     * 旧数据不够长时，缺的部分当作静音。
     */
    private fun crossfade(cur: Chunk?, off: Int, oldChannel: ReceiveChannel<Chunk>, first: Chunk): ByteArray {
        val xfade = minOf(options.crossfadeMs * 44_100 / 1000, first.data.size / BYTES_PER_FRAME)
        val old = java.io.ByteArrayOutputStream(xfade * BYTES_PER_FRAME)
        if (cur != null && off < cur.data.size) old.write(cur.data, off, minOf(cur.data.size - off, xfade * BYTES_PER_FRAME))
        while (old.size() < xfade * BYTES_PER_FRAME) {
            val c = oldChannel.tryReceive().getOrNull() ?: break
            old.write(c.data, 0, minOf(c.data.size, xfade * BYTES_PER_FRAME - old.size()))
        }
        val o = old.toByteArray()
        val oldFrames = o.size / BYTES_PER_FRAME
        val out = first.data.copyOf()
        for (f in 0 until xfade) {
            val g = f.toDouble() / xfade
            for (c in 0 until 2) {
                val i = f * BYTES_PER_FRAME + c * 2
                val vNew = (out[i + 1].toInt() shl 8) or (out[i].toInt() and 0xFF)
                val vOld = if (f < oldFrames) (o[i + 1].toInt() shl 8) or (o[i].toInt() and 0xFF) else 0
                val v = Math.round(vOld * (1 - g) + vNew * g).toInt().coerceIn(-32768, 32767)
                out[i] = v.toByte()
                out[i + 1] = (v shr 8).toByte()
            }
        }
        log.v(TAG, "接上第 ${first.track.number} 轨（接缝平滑 ${xfade * 1000 / 44_100} ms）")
        return out
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
        switchDisplay?.let { shown ->
            if (switchAudibleFrame < 0 || played < switchAudibleFrame) return shown
            switchDisplay = null
        }
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

        /** 手动点歌、跳转、换轨时的淡入（防止爆音）。 */
        const val SHORT_FADE_MS = 30

        /** 自动播放的淡入。 */
        const val AUTOPLAY_FADE_MS = 1_000

        /** 播放中手动切歌 / 跳转时接缝处的平滑长度（只为防止"啪"声）。 */
        const val SWITCH_SPLICE_MS = 10
    }
}
