package com.kvmcontrol.app

import com.kvmcontrol.app.util.HidDescriptors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HidDescriptorsTest {

    @Test
    fun `keyboard descriptor is non-empty and starts with correct usage page`() {
        val desc = HidDescriptors.KEYBOARD
        assertNotNull(desc)
        assert(desc.isNotEmpty())
        // Usage Page (Generic Desktop) = 0x05, 0x01
        assertEquals(0x05.toByte(), desc[0])
        assertEquals(0x01.toByte(), desc[1])
        // Usage (Keyboard) = 0x09, 0x06
        assertEquals(0x09.toByte(), desc[2])
        assertEquals(0x06.toByte(), desc[3])
    }

    @Test
    fun `mouse descriptor is non-empty and starts with correct usage`() {
        val desc = HidDescriptors.MOUSE
        assertNotNull(desc)
        assert(desc.isNotEmpty())
        assertEquals(0x05.toByte(), desc[0])
        assertEquals(0x01.toByte(), desc[1])
        assertEquals(0x09.toByte(), desc[2])
        assertEquals(0x02.toByte(), desc[3]) // Usage (Mouse)
    }

    @Test
    fun `combined descriptor equals keyboard plus mouse`() {
        assertEquals(
            HidDescriptors.KEYBOARD.size + HidDescriptors.MOUSE.size,
            HidDescriptors.COMBINED.size,
        )
    }

    @Test
    fun `buildKeyboardReport produces 8-byte report`() {
        val report = HidDescriptors.buildKeyboardReport(0x02.toByte(), 0x04.toByte())
        assertEquals(8, report.size)
        assertEquals(0x02.toByte(), report[0]) // modifier
        assertEquals(0x00.toByte(), report[1]) // reserved
        assertEquals(0x04.toByte(), report[2]) // first keycode
    }

    @Test
    fun `buildKeyboardReport handles empty keycodes`() {
        val report = HidDescriptors.buildKeyboardReport(0x00.toByte())
        assertEquals(8, report.size)
        for (i in 2..7) assertEquals(0x00.toByte(), report[i])
    }

    @Test
    fun `buildMouseReport produces 4-byte report`() {
        val report = HidDescriptors.buildMouseReport(1, 10, (-5).toByte())
        assertEquals(4, report.size)
        assertEquals(1.toByte(), report[0]) // buttons
        assertEquals(10.toByte(), report[1]) // dx
        assertEquals((-5).toByte(), report[2]) // dy
    }

    @Test
    fun `buildKeyboardReport clamps to max 6 keycodes`() {
        val report = HidDescriptors.buildKeyboardReport(
            0x00.toByte(),
            0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, // 8 keys — only first 6 fit
        )
        assertEquals(8, report.size)
        assertEquals(0x04.toByte(), report[2])
        assertEquals(0x09.toByte(), report[7]) // last key in report (index 7 = key slot 6)
    }
}
