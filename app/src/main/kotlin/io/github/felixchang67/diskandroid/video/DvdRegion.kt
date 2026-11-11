package io.github.felixchang67.diskandroid.video

import io.github.felixchang67.diskandroid.core.mmc.DvdCopyright
import io.github.felixchang67.diskandroid.core.mmc.RpcState

/**
 * DVD 地区码：只读取、只提示，不修改光驱的地区设置（不发任何改地区的命令）。
 *
 * - 光驱（RPC-II）已设置地区：播放器（libdvdnav 的 SPRM20）就用光驱的地区；盘片不允许这个地区时明确提示。
 *   没加密的盘光驱不拦，但盘里的程序可能自己检查地区并显示"地区不符"的画面；CSS 加密盘光驱会拒绝认证。
 * - 光驱没设置地区（或 RPC-I、读不到）：播放器用盘片允许的第一个地区，让盘里的地区检查通过（这不涉及光驱）。
 */
object DvdRegion {
    class Decision(
        /** 给 libdvdnav 的地区掩码：第 n 位 = 地区 n+1。 */
        val playerMask: Int,
        val playerRegion: Int,
        /** 要明确提示给用户的话（地区不匹配），没有就是 null。 */
        val warning: String?,
        /** 写进日志的说明。 */
        val explanation: String,
    )

    fun decide(rpc: RpcState?, disc: DvdCopyright?): Decision {
        val discRegions = disc?.regions.orEmpty()
        val discFree = disc == null || discRegions.size == 8 || discRegions.isEmpty()
        val driveRegions = rpc?.regions.orEmpty()
        val driveSet = rpc != null && rpc.rpcScheme == 1 && rpc.typeCode != 0 && driveRegions.size in 1..7
        val discText = when {
            disc == null -> "盘片地区未知"
            discFree -> "盘片全区"
            else -> "盘片地区 " + discRegions.joinToString("、")
        }
        val driveText = when {
            rpc == null -> "光驱地区读不到"
            rpc.rpcScheme != 1 -> "光驱是 RPC-I（不限地区）"
            rpc.typeCode == 0 -> "光驱还没设置地区"
            else -> "光驱地区 " + driveRegions.joinToString("、")
        }
        if (driveSet) {
            val region = driveRegions.first()
            val mismatch = !discFree && region !in discRegions
            val warning = if (mismatch) {
                "地区不匹配：$discText，$driveText。没有加密的盘光驱不会拦，但盘里的程序可能拒绝播放；" +
                    "CSS 加密的盘光驱会拒绝认证。App 不会修改光驱的地区码。"
            } else {
                null
            }
            return Decision(1 shl (region - 1), region, warning, "$discText，$driveText：播放器用地区 $region" + if (mismatch) "（不匹配）" else "")
        }
        val region = if (discFree) 1 else discRegions.first()
        return Decision(1 shl (region - 1), region, null, "$discText，$driveText：播放器用地区 $region")
    }
}
