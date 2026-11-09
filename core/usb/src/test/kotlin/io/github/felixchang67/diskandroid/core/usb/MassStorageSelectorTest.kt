package io.github.felixchang67.diskandroid.core.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MassStorageSelectorTest {
    private fun bulkIn(addr: Int = 0x81, size: Int = 512) = UsbEndpointModel(addr, 2, size)
    private fun bulkOut(addr: Int = 0x02, size: Int = 512) = UsbEndpointModel(addr, 2, size)
    private fun iface(id: Int, alt: Int, cls: Int, sub: Int, proto: Int, vararg eps: UsbEndpointModel) =
        UsbInterfaceModel(id, alt, cls, sub, proto, eps.toList())

    @Test
    fun selectsPlainBotInterface() {
        val sel = MassStorageInterfaceSelector.select(listOf(iface(0, 0, 0x08, 0x06, 0x50, bulkIn(), bulkOut())))
        assertIs<MassStorageSelection.Selected>(sel)
        assertEquals(0x81, sel.bulkIn.address)
        assertEquals(0x02, sel.bulkOut.address)
        assertFalse(sel.needsSetInterface)
        assertTrue(sel.notes.isEmpty())
    }

    @Test
    fun prefersBotOverUasAndRequestsSetInterface() {
        val sel = MassStorageInterfaceSelector.select(
            listOf(
                iface(0, 0, 0x08, 0x06, 0x50, bulkIn(size = 1024), bulkOut(size = 1024)),
                iface(0, 1, 0x08, 0x06, 0x62, bulkIn(0x81), bulkOut(0x02), bulkIn(0x83), bulkOut(0x04)),
            ),
        )
        assertIs<MassStorageSelection.Selected>(sel)
        assertEquals(0, sel.iface.alternateSetting)
        assertTrue(sel.needsSetInterface)
        assertTrue(sel.notes.any { it.contains("UAS") })
    }

    @Test
    fun parsedDescriptorsFeedTheSelector() {
        val models = MassStorageInterfaceSelector.modelsFrom(UsbDescriptorParser.parse(Desc.usb3BotPlusUas()))
        val sel = MassStorageInterfaceSelector.select(models)
        assertIs<MassStorageSelection.Selected>(sel)
        assertEquals(1024, sel.bulkIn.maxPacketSize)
        assertTrue(sel.needsSetInterface)
    }

    @Test
    fun uasOnlyIsUnsupported() {
        val sel = MassStorageInterfaceSelector.select(listOf(iface(0, 0, 0x08, 0x06, 0x62, bulkIn(), bulkOut())))
        assertIs<MassStorageSelection.Unsupported>(sel)
        assertTrue(sel.reason.contains("UAS"))
    }

    @Test
    fun cbiIsUnsupported() {
        val sel = MassStorageInterfaceSelector.select(listOf(iface(0, 0, 0x08, 0x04, 0x00, bulkIn(), bulkOut())))
        assertIs<MassStorageSelection.Unsupported>(sel)
    }

    @Test
    fun nonStorageDeviceIsIgnored() {
        val sel = MassStorageInterfaceSelector.select(listOf(iface(0, 0, 0x03, 0x01, 0x02, UsbEndpointModel(0x81, 3, 8))))
        assertEquals(MassStorageSelection.NotMassStorage, sel)
    }

    @Test
    fun atapiSubclassIsAcceptedWithNote() {
        val sel = MassStorageInterfaceSelector.select(listOf(iface(0, 0, 0x08, 0x02, 0x50, bulkIn(), bulkOut())))
        assertIs<MassStorageSelection.Selected>(sel)
        assertTrue(sel.notes.any { it.contains("02") })
    }

    @Test
    fun missingBulkEndpointsIsUnsupported() {
        val sel = MassStorageInterfaceSelector.select(listOf(iface(0, 0, 0x08, 0x06, 0x50, bulkIn())))
        assertIs<MassStorageSelection.Unsupported>(sel)
        assertTrue(sel.notes.any { it.contains("缺少") })
    }

    @Test
    fun compositeDevicePicksStorageInterface() {
        val sel = MassStorageInterfaceSelector.select(
            listOf(
                iface(0, 0, 0x03, 0x00, 0x00, UsbEndpointModel(0x81, 3, 8)),
                iface(1, 0, 0x08, 0x06, 0x50, bulkIn(0x82), bulkOut(0x03)),
            ),
        )
        assertIs<MassStorageSelection.Selected>(sel)
        assertEquals(1, sel.iface.id)
        assertEquals(0x82, sel.bulkIn.address)
    }
}
