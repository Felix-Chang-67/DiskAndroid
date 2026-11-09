package io.github.felixchang67.diskandroid.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.felixchang67.diskandroid.core.log.DiskLog
import io.github.felixchang67.diskandroid.core.log.Hex
import io.github.felixchang67.diskandroid.core.log.LogLevel
import io.github.felixchang67.diskandroid.core.log.LogTags
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

sealed interface UsbEvent {
    /** @property reconnectGapMs 同一 VID:PID 上次断开到这次插入的间隔（没有记录则为 null）。 */
    data class Attached(val snapshot: UsbDeviceSnapshot, val source: String, val reconnectGapMs: Long?) : UsbEvent

    data class Detached(val deviceName: String, val label: String, val connectedMs: Long?) : UsbEvent

    data class PermissionResult(val deviceName: String, val granted: Boolean) : UsbEvent
}

/**
 * 监听 USB 热插拔与权限结果，维护当前设备列表。
 *
 * 插入事件可能同时来自广播和 Activity 的 USB_DEVICE_ATTACHED 意图，这里按设备路径去重。
 * 同一设备断开后很快又出现（[QUICK_RECONNECT_MS] 内）会记一条警告：如果不是手动插拔，多半是供电不足或光驱复位。
 */
class UsbDeviceMonitor(private val context: Context, private val log: DiskLog) {
    companion object {
        const val ACTION_USB_PERMISSION = "io.github.felixchang67.diskandroid.action.USB_PERMISSION"
        const val QUICK_RECONNECT_MS = 5_000L
        private const val TAG = LogTags.USB
    }

    val usbManager: UsbManager = context.getSystemService(UsbManager::class.java)

    private val _devices = MutableStateFlow<List<UsbDeviceSnapshot>>(emptyList())
    val devices: StateFlow<List<UsbDeviceSnapshot>> = _devices.asStateFlow()

    private val _events = MutableSharedFlow<UsbEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<UsbEvent> = _events.asSharedFlow()

    /** 设备路径 → 插入时刻（elapsedRealtime）。 */
    private val attachedAt = ConcurrentHashMap<String, Long>()

    /** 设备路径 → VID:PID（断开广播里设备信息可能不全，提前记下）。 */
    private val labels = ConcurrentHashMap<String, String>()

    /** VID:PID → 最近一次断开时刻。 */
    private val lastDetachAt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttached(device, "系统广播")
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached(device)
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    onPermissionResult(device, granted)
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        // 权限结果来自我们自己的 PendingIntent，插拔是系统广播；两者都能被 NOT_EXPORTED 接收器收到。
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val now = SystemClock.elapsedRealtime()
        val current = usbManager.deviceList.values.toList()
        current.forEach {
            attachedAt.putIfAbsent(it.deviceName, now)
            labels[it.deviceName] = vidPid(it)
        }
        refresh("启动")
        if (current.isEmpty()) {
            log.i(TAG, "启动时没有 USB 设备")
        } else {
            _devices.value.forEach { s -> log.i(TAG, "启动时已连接的设备：${s.displayName}", "VID:PID" to s.vidPid, "路径" to s.deviceName, "权限" to s.hasPermission) }
        }
    }

    fun refresh(reason: String) {
        val list = runCatching { usbManager.deviceList.values.map { UsbDeviceSnapshot.from(it, usbManager) } }
            .onFailure { log.w(TAG, "读取 USB 设备列表失败", "原因" to reason, throwable = it) }
            .getOrDefault(emptyList())
            .sortedBy { it.deviceName }
        _devices.value = list
        log.v(TAG, "刷新设备列表", "原因" to reason, "数量" to list.size)
    }

    /** Activity 被 USB_DEVICE_ATTACHED 意图启动/唤起时调用。 */
    fun onAttachedIntent(device: UsbDevice?) = onAttached(device, "Activity 启动意图")

    fun findDevice(deviceName: String): UsbDevice? = usbManager.deviceList[deviceName]

    fun requestPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        // 系统要往 Intent 里填 EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED，所以必须是 MUTABLE；
        // Android 14 起 MUTABLE 的 PendingIntent 必须是显式 Intent（上面已 setPackage）。
        val pending = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        log.i(TAG, "申请 USB 权限", "设备" to device.deviceName, "VID:PID" to vidPid(device))
        runCatching { usbManager.requestPermission(device, pending) }
            .onFailure { log.e(TAG, "requestPermission 调用失败", throwable = it) }
    }

    private fun onAttached(device: UsbDevice?, source: String) {
        if (device == null) {
            log.w(TAG, "收到插入事件但没有设备信息", "来源" to source)
            refresh("插入事件（无设备）")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val name = device.deviceName
        val label = vidPid(device)
        labels[name] = label
        val isNew = attachedAt.putIfAbsent(name, now) == null
        refresh("插入：$source")
        if (!isNew) {
            log.d(TAG, "重复的插入通知（已记录过）", "来源" to source, "设备" to name)
            return
        }
        val gap = lastDetachAt[label]?.let { now - it }
        val snapshot = _devices.value.firstOrNull { it.deviceName == name } ?: UsbDeviceSnapshot.from(device, usbManager)
        log.i(
            TAG, "USB 设备插入：${snapshot.displayName}",
            "来源" to source, "VID:PID" to label, "路径" to name, "权限" to snapshot.hasPermission,
            "距上次断开ms" to (gap ?: "—"),
        )
        if (gap != null && gap < QUICK_RECONNECT_MS) {
            log.w(TAG, "设备断开后 $gap ms 又重新连接。如果你没有手动插拔，可能是供电不足或光驱自行复位——请检查带供电的扩展坞 / Boost 线，以及光驱的两个 USB 头是否都已插好")
        }
        _events.tryEmit(UsbEvent.Attached(snapshot, source, gap))
    }

    private fun onDetached(device: UsbDevice?) {
        val now = SystemClock.elapsedRealtime()
        val name = device?.deviceName
        if (name == null) {
            log.w(TAG, "收到断开事件但没有设备信息")
            refresh("断开事件（无设备）")
            return
        }
        val label = labels[name] ?: vidPid(device)
        val since = attachedAt.remove(name)
        val connectedMs = since?.let { now - it }
        lastDetachAt[label] = now
        refresh("断开")
        log.log(
            LogLevel.INFO, TAG, "USB 设备断开",
            listOf("VID:PID" to label, "路径" to name, "连接时长ms" to (connectedMs ?: "未知")),
        )
        _events.tryEmit(UsbEvent.Detached(name, label, connectedMs))
    }

    private fun onPermissionResult(device: UsbDevice?, granted: Boolean) {
        val name = device?.deviceName ?: ""
        if (granted) {
            log.i(TAG, "USB 权限已授予", "设备" to name)
        } else {
            log.w(TAG, "USB 权限被拒绝", "设备" to name)
        }
        refresh("权限结果")
        _events.tryEmit(UsbEvent.PermissionResult(name, granted))
    }

    private fun vidPid(device: UsbDevice) = "${Hex.word(device.vendorId)}:${Hex.word(device.productId)}"
}
