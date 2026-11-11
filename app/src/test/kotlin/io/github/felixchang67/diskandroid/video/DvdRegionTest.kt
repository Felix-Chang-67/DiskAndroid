package io.github.felixchang67.diskandroid.video

import io.github.felixchang67.diskandroid.core.mmc.DvdCopyright
import io.github.felixchang67.diskandroid.core.mmc.RpcState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 地区码：只读不改；光驱设了地区就用光驱的，不匹配要明确提示。掩码里某位为 1 = 该地区不能放。 */
class DvdRegionTest {
    private fun drive(region: Int?, type: Int = 1, scheme: Int = 1) =
        RpcState(type, 4, 5, if (region == null) 0xFF else 0xFF and (1 shl (region - 1)).inv(), scheme)

    private fun disc(vararg regions: Int) = DvdCopyright(0, regions.fold(0xFF) { m, r -> m and (1 shl (r - 1)).inv() })

    @Test
    fun driveRegionMatchesDisc() {
        val d = DvdRegion.decide(drive(2), disc(2))
        assertEquals(2, d.playerRegion)
        assertEquals(0b10, d.playerMask)
        assertNull(d.warning)
    }

    @Test
    fun mismatchIsReportedAndThePlayerKeepsTheDriveRegion() {
        val d = DvdRegion.decide(drive(2), disc(1))
        assertEquals(2, d.playerRegion)
        val w = assertNotNull(d.warning)
        assertTrue("盘片地区 1" in w && "光驱地区 2" in w && "不会修改光驱的地区码" in w, w)
        assertTrue("不匹配" in d.explanation)
    }

    @Test
    fun regionFreeDiscPlaysAnywhere() {
        val d = DvdRegion.decide(drive(2), DvdCopyright(0, 0x00))
        assertEquals(2, d.playerRegion)
        assertNull(d.warning)
        assertTrue("盘片全区" in d.explanation)
    }

    @Test
    fun driveWithoutRegionUsesTheDiscsFirstRegion() {
        val d = DvdRegion.decide(drive(null, type = 0), disc(2, 4))
        assertEquals(2, d.playerRegion)
        assertEquals(0b10, d.playerMask)
        assertNull(d.warning)
        assertTrue("光驱还没设置地区" in d.explanation)
    }

    @Test
    fun rpc1DriveDoesNotRestrict() {
        val d = DvdRegion.decide(drive(1, scheme = 0), disc(3))
        assertEquals(3, d.playerRegion)
        assertNull(d.warning)
        assertTrue("RPC-I" in d.explanation)
    }

    @Test
    fun unknownEverythingFallsBackToRegion1() {
        val d = DvdRegion.decide(null, null)
        assertEquals(1, d.playerRegion)
        assertEquals(1, d.playerMask)
        assertNull(d.warning)
    }
}
