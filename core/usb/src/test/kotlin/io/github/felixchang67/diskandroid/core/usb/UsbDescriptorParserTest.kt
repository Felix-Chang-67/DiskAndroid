package io.github.felixchang67.diskandroid.core.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 用字节构造描述符的小工具（多字节字段为小端）。 */
internal object Desc {
    fun device(bcdUsb: Int, vid: Int, pid: Int, ep0: Int, cls: Int = 0) = bytes(
        18, 0x01, bcdUsb and 0xFF, bcdUsb shr 8, cls, 0, 0, ep0,
        vid and 0xFF, vid shr 8, pid and 0xFF, pid shr 8, 0x00, 0x01, 1, 2, 3, 1,
    )

    fun config(totalLength: Int, numInterfaces: Int, attributes: Int, maxPower: Int) = bytes(
        9, 0x02, totalLength and 0xFF, totalLength shr 8, numInterfaces, 1, 0, attributes, maxPower,
    )

    fun iface(number: Int, alt: Int, numEp: Int, cls: Int, sub: Int, proto: Int) = bytes(9, 0x04, number, alt, numEp, cls, sub, proto, 0)

    fun endpoint(address: Int, attributes: Int, maxPacket: Int) = bytes(7, 0x05, address, attributes, maxPacket and 0xFF, maxPacket shr 8, 0)

    fun ssCompanion(maxBurst: Int, attributes: Int) = bytes(6, 0x30, maxBurst, attributes, 0, 0)

    /** UAS Pipe Usage 描述符（类专用，CS_INTERFACE 24h）。 */
    fun pipeUsage(pipeId: Int) = bytes(4, 0x24, pipeId, 0)

    fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** USB3 光驱：接口 0 备用 0 = BOT，备用 1 = UAS（4 个端点 + Pipe Usage）。 */
    fun usb3BotPlusUas(): ByteArray {
        val bot = iface(0, 0, 2, 0x08, 0x06, 0x50) +
            endpoint(0x81, 0x02, 1024) + ssCompanion(15, 0) +
            endpoint(0x02, 0x02, 1024) + ssCompanion(15, 0)
        val uas = iface(0, 1, 4, 0x08, 0x06, 0x62) +
            endpoint(0x81, 0x02, 1024) + ssCompanion(15, 5) + pipeUsage(3) +
            endpoint(0x02, 0x02, 1024) + ssCompanion(15, 5) + pipeUsage(4) +
            endpoint(0x83, 0x02, 1024) + ssCompanion(15, 5) + pipeUsage(2) +
            endpoint(0x04, 0x02, 1024) + ssCompanion(0, 0) + pipeUsage(1)
        val body = bot + uas
        return device(0x0320, 0x0411, 0x0123, 9) + config(9 + body.size, 1, 0x80, 0x70) + body
    }

    /** 同一光驱在 USB2 口上：只有 BOT，bulk 512，没有伴随描述符，bcdUSB 2.10。 */
    fun usb2Bot(): ByteArray {
        val body = iface(0, 0, 2, 0x08, 0x06, 0x50) + endpoint(0x81, 0x02, 512) + endpoint(0x02, 0x02, 512)
        return device(0x0210, 0x0411, 0x0123, 64) + config(9 + body.size, 1, 0x80, 0xFA) + body
    }
}

class UsbDescriptorParserTest {
    @Test
    fun parsesUsb3DeviceWithBotAndUasAlternates() {
        val parsed = UsbDescriptorParser.parse(Desc.usb3BotPlusUas())
        assertTrue(parsed.warnings.isEmpty(), parsed.warnings.toString())

        val dev = assertNotNull(parsed.device)
        assertEquals(0x0320, dev.bcdUsb)
        assertEquals(0x0411, dev.vendorId)
        assertEquals(0x0123, dev.productId)
        assertEquals(512, dev.maxPacketSize0) // USB3：2^9

        val cfg = parsed.configurations.single()
        assertEquals(2, cfg.interfaces.size)
        assertFalse(cfg.selfPowered)
        assertEquals(896, cfg.maxPowerMilliAmps(superSpeed = true)) // 0x70 × 8 mA

        val bot = cfg.interfaces[0]
        assertEquals(0x50, bot.interfaceProtocol)
        assertEquals(listOf(0x81, 0x02), bot.endpoints.map { it.address })
        assertEquals(1024, bot.endpoints[0].maxPacketSize)
        assertEquals(15, bot.endpoints[0].companion?.maxBurst)

        val uas = cfg.interfaces[1]
        assertEquals(1, uas.alternateSetting)
        assertEquals(4, uas.endpoints.size)
        assertEquals(32, uas.endpoints[0].companion?.maxStreams) // 2^5
        assertEquals(4, uas.extras.size) // 4 个 Pipe Usage 描述符挂在接口下

        assertEquals(UsbSpeed.SUPER, parsed.inferSpeed())
        assertTrue(parsed.describe().any { it.contains("896 mA") })
    }

    @Test
    fun parsesUsb2DeviceAndInfersHighSpeed() {
        val parsed = UsbDescriptorParser.parse(Desc.usb2Bot())
        assertTrue(parsed.warnings.isEmpty(), parsed.warnings.toString())
        assertEquals(64, parsed.device?.maxPacketSize0)
        assertEquals(UsbSpeed.HIGH, parsed.inferSpeed())
        assertEquals(500, parsed.configurations.single().maxPowerMilliAmps(superSpeed = false)) // 0xFA × 2 mA
        assertNull(parsed.configurations.single().interfaces.single().endpoints[0].companion)
    }

    @Test
    fun truncatedDescriptorProducesWarningInsteadOfCrash() {
        val full = Desc.usb2Bot()
        val parsed = UsbDescriptorParser.parse(full.copyOf(full.size - 3))
        assertTrue(parsed.warnings.any { it.contains("截断") }, parsed.warnings.toString())
        assertNotNull(parsed.device)
    }

    @Test
    fun zeroLengthDescriptorStopsParsing() {
        val bytes = Desc.usb2Bot().copyOf(18) + Desc.bytes(0, 0x02, 0, 0)
        val parsed = UsbDescriptorParser.parse(bytes)
        assertTrue(parsed.warnings.any { it.contains("bLength=0") }, parsed.warnings.toString())
        assertTrue(parsed.configurations.isEmpty())
    }

    @Test
    fun endpointCountMismatchIsReported() {
        val body = Desc.iface(0, 0, 3, 0x08, 0x06, 0x50) + Desc.endpoint(0x81, 0x02, 512)
        val bytes = Desc.device(0x0200, 1, 2, 64) + Desc.config(9 + body.size, 1, 0xC0, 50) + body
        val parsed = UsbDescriptorParser.parse(bytes)
        assertTrue(parsed.configurations.single().selfPowered)
        assertTrue(parsed.warnings.any { it.contains("声明 3 个端点") }, parsed.warnings.toString())
    }
}
