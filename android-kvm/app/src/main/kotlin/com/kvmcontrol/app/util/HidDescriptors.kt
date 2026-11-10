package com.kvmcontrol.app.util

object HidDescriptors {

    val KEYBOARD: ByteArray = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),  // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(),  // Usage (Keyboard)
        0xa1.toByte(), 0x01.toByte(),  // Collection (Application)
        0x05.toByte(), 0x07.toByte(),  //   Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0xe0.toByte(),  //   Usage Minimum (Left Control)
        0x29.toByte(), 0xe7.toByte(),  //   Usage Maximum (Right GUI)
        0x15.toByte(), 0x00.toByte(),  //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),  //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),  //   Report Size (1)
        0x95.toByte(), 0x08.toByte(),  //   Report Count (8) — modifier keys
        0x81.toByte(), 0x02.toByte(),  //   Input (Data, Variable, Absolute)
        0x95.toByte(), 0x01.toByte(),  //   Report Count (1)
        0x75.toByte(), 0x08.toByte(),  //   Report Size (8) — reserved
        0x81.toByte(), 0x03.toByte(),  //   Input (Const)
        0x95.toByte(), 0x06.toByte(),  //   Report Count (6)
        0x75.toByte(), 0x08.toByte(),  //   Report Size (8) — keycodes
        0x15.toByte(), 0x00.toByte(),  //   Logical Minimum (0)
        0x25.toByte(), 0x73.toByte(),  //   Logical Maximum (115)
        0x05.toByte(), 0x07.toByte(),  //   Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0x00.toByte(),  //   Usage Minimum (0)
        0x29.toByte(), 0x73.toByte(),  //   Usage Maximum (115)
        0x81.toByte(), 0x00.toByte(),  //   Input (Data, Array)
        0xc0.toByte(),                 // End Collection
    )

    val MOUSE: ByteArray = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),  // Usage Page (Generic Desktop)
        0x09.toByte(), 0x02.toByte(),  // Usage (Mouse)
        0xa1.toByte(), 0x01.toByte(),  // Collection (Application)
        0x09.toByte(), 0x01.toByte(),  //   Usage (Pointer)
        0xa1.toByte(), 0x00.toByte(),  //   Collection (Physical)
        0x05.toByte(), 0x09.toByte(),  //     Usage Page (Buttons)
        0x19.toByte(), 0x01.toByte(),  //     Usage Minimum (1)
        0x29.toByte(), 0x03.toByte(),  //     Usage Maximum (3)
        0x15.toByte(), 0x00.toByte(),  //     Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),  //     Logical Maximum (1)
        0x95.toByte(), 0x03.toByte(),  //     Report Count (3)
        0x75.toByte(), 0x01.toByte(),  //     Report Size (1)
        0x81.toByte(), 0x02.toByte(),  //     Input (Data, Variable, Absolute) — buttons
        0x95.toByte(), 0x01.toByte(),  //     Report Count (1)
        0x75.toByte(), 0x05.toByte(),  //     Report Size (5) — padding
        0x81.toByte(), 0x03.toByte(),  //     Input (Const)
        0x05.toByte(), 0x01.toByte(),  //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(),  //     Usage (X)
        0x09.toByte(), 0x31.toByte(),  //     Usage (Y)
        0x09.toByte(), 0x38.toByte(),  //     Usage (Wheel)
        0x15.toByte(), 0x81.toByte(),  //     Logical Minimum (-127)
        0x25.toByte(), 0x7f.toByte(),  //     Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(),  //     Report Size (8)
        0x95.toByte(), 0x03.toByte(),  //     Report Count (3)
        0x81.toByte(), 0x06.toByte(),  //     Input (Data, Variable, Relative)
        0xc0.toByte(),                 //   End Collection
        0xc0.toByte(),                 // End Collection
    )

    val COMBINED: ByteArray = KEYBOARD + MOUSE

    fun buildKeyboardReport(modifier: Byte, vararg keyCodes: Byte): ByteArray {
        val report = ByteArray(8)
        report[0] = modifier
        report[1] = 0 // reserved
        keyCodes.take(6).forEachIndexed { i, code -> report[2 + i] = code }
        return report
    }

    fun buildMouseReport(buttons: Byte, dx: Byte, dy: Byte, wheel: Byte = 0): ByteArray =
        byteArrayOf(buttons, dx, dy, wheel)
}
