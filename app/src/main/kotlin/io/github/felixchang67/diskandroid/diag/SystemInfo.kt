package io.github.felixchang67.diskandroid.diag

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import android.system.OsConstants
import io.github.felixchang67.diskandroid.BuildConfig
import io.github.felixchang67.diskandroid.core.log.LogFormat
import java.util.concurrent.TimeUnit

/** 手机/平板与 App 的基本信息（写进启动日志和诊断信息）。 */
object SystemInfo {
    fun appVersion(): String =
        "${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}，git ${BuildConfig.GIT_SHA}，" +
            "构建于 ${LogFormat.isoTime(BuildConfig.BUILD_TIME)}，测试节点 ${BuildConfig.TEST_NODE}）"

    @Volatile
    private var cached: List<String>? = null

    /** 系统信息不会在运行中变化，第一次计算后缓存（getprop 要起子进程）。 */
    fun lines(context: Context): List<String> = cached ?: compute(context).also { cached = it }

    private fun compute(context: Context): List<String> {
        val out = mutableListOf<String>()
        out += "App：${appVersion()}"
        out += "设备：${Build.MANUFACTURER} ${Build.MODEL}（${Build.DEVICE} / ${Build.PRODUCT}）" +
            (getprop("ro.product.marketname")?.let { "，商品名 $it" } ?: "")
        out += "系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}），安全补丁 ${Build.VERSION.SECURITY_PATCH}"
        out += "ROM：${Build.DISPLAY}" +
            (getprop("ro.mi.os.version.name")?.let { "，HyperOS $it" } ?: "") +
            (getprop("ro.mi.os.version.incremental")?.let { "（$it）" } ?: "")
        if (Build.VERSION.SDK_INT >= 31) out += "SoC：${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        out += "内核：${System.getProperty("os.version")}"
        out += "页大小：${runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(-1)} 字节；ABI：${Build.SUPPORTED_ABIS.joinToString()}"
        out += "USB Host 特性：${context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)}"
        return out
    }

    /** 通过 getprop 读取系统属性（失败返回 null，不影响功能）。 */
    private fun getprop(name: String): String? = runCatching {
        val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor(500, TimeUnit.MILLISECONDS)
        text.ifEmpty { null }
    }.getOrNull()
}
