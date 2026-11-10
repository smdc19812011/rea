package com.kvmcontrol.app.service

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import com.kvmcontrol.app.data.ConnectionState
import com.kvmcontrol.app.data.KeyEvent
import com.kvmcontrol.app.data.MouseEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "UsbHidBridge"

/**
 * Rootless USB HID via a CH9329 (or compatible) USB-serial HID bridge chip.
 *
 * The phone connects to the chip as USB host (OTG), sends HID frames over
 * CDC serial (no root needed), and the chip presents as a standard USB HID
 * keyboard+mouse to the target PC.
 *
 * Compatible chips / dongles:
 *   - CH9329  (most common, used in KVM switches)
 *   - CH9328  (keyboard-only variant)
 *   - Any CDC-serial HID bridge that accepts the same frame format
 *
 * CH9329 frame format:
 *   Header: 57 AB 00
 *   Keyboard: 02 08 <modifier> 00 <k1>..<k6> <csum>   (14 bytes total)
 *   Mouse:    05 05 <buttons> <xl> <xh> <yl> <yh> <csum> (10 bytes total)
 *   Checksum: (sum of all bytes) & 0xFF
 *
 * Wiring / hardware needed:
 *   - USB OTG adapter (micro-USB or USB-C → type-A female)
 *   - CH9329 USB dongle (plug into the OTG adapter, the other end into the PC)
 *
 * No driver installation required on Android — UsbManager handles CDC class devices.
 */
class UsbHidBridgeService(private val context: Context) {

    companion object {
        // Known CH9329 / HID bridge VID:PID pairs
        private val KNOWN_BRIDGE_VIDS = setOf(
            0x1A86,  // WCH (makes CH9329)
            0x2341,  // Arduino (Leonardo acts as HID + serial)
            0x2E8A,  // Raspberry Pi (Pico with TinyUSB HID bridge)
            0x03EB,  // Atmel (ATmega32u4-based boards)
        )

        // CDC class / subclass / protocol identifiers
        private const val USB_CLASS_CDC = 0x02
        private const val USB_CLASS_CDC_DATA = 0x0A
        private const val USB_CLASS_HID = 0x03

        // CH9329 frame constants
        private val FRAME_HEADER = byteArrayOf(0x57.toByte(), 0xAB.toByte(), 0x00.toByte())
        private const val CMD_KEYBOARD: Byte = 0x02
        private const val CMD_MOUSE_ABS: Byte = 0x04
        private const val CMD_MOUSE_REL: Byte = 0x05

        // Timeout for bulk transfers (ms)
        private const val TRANSFER_TIMEOUT = 500
    }

    private val usbManager = context.getSystemService(UsbManager::class.java)

    private var connection: UsbDeviceConnection? = null
    private var bulkOut: UsbEndpoint? = null
    private var usbIface: UsbInterface? = null
    private var connectedDevice: UsbDevice? = null

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName

    /** Returns true if at least one compatible HID bridge is attached via USB OTG. */
    fun findBridgeDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { dev ->
            isCompatibleBridge(dev)
        }
    }

    fun isAvailable(): Boolean = findBridgeDevice() != null

    /**
     * Open the USB HID bridge. If [device] is null, auto-detects the first
     * compatible device. Returns true on success.
     *
     * Note: on Android 12+ the app must hold a USB permission for the device
     * (request via UsbManager.requestPermission) before calling this.
     */
    fun open(device: UsbDevice? = null): Boolean {
        val dev = device ?: findBridgeDevice() ?: run {
            Log.w(TAG, "No compatible USB HID bridge found")
            _state.value = ConnectionState.ERROR
            return false
        }

        if (!usbManager.hasPermission(dev)) {
            Log.w(TAG, "No USB permission for ${dev.deviceName} — request it first")
            _state.value = ConnectionState.ERROR
            return false
        }

        return try {
            _state.value = ConnectionState.CONNECTING
            val iface = findBulkOutInterface(dev) ?: throw IllegalStateException("No bulk-out interface")
            val endpoint = findBulkOutEndpoint(iface) ?: throw IllegalStateException("No bulk-out endpoint")
            val conn = usbManager.openDevice(dev) ?: throw IllegalStateException("openDevice returned null")

            if (!conn.claimInterface(iface, true)) throw IllegalStateException("claimInterface failed")

            connection = conn
            bulkOut = endpoint
            usbIface = iface
            connectedDevice = dev
            _deviceName.value = dev.productName ?: dev.deviceName
            _state.value = ConnectionState.CONNECTED
            Log.i(TAG, "USB HID bridge open: ${dev.productName} (${dev.vendorId.hex}:${dev.productId.hex})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "open failed: ${e.message}")
            _state.value = ConnectionState.ERROR
            false
        }
    }

    fun close() {
        usbIface?.let { connection?.releaseInterface(it) }
        connection?.close()
        connection = null
        bulkOut = null
        usbIface = null
        connectedDevice = null
        _state.value = ConnectionState.DISCONNECTED
    }

    // ── Keyboard ───────────────────────────────────────────────────────────

    fun sendKeyEvent(event: KeyEvent): Boolean {
        val keys = event.keys.take(6).toMutableList()
        while (keys.size < 6) keys.add(0)
        val payload = byteArrayOf(
            CMD_KEYBOARD, 0x08,
            event.modifier,
            0x00,
            keys[0], keys[1], keys[2], keys[3], keys[4], keys[5],
        )
        return sendFrame(payload)
    }

    fun sendKeyRelease(): Boolean {
        val payload = byteArrayOf(CMD_KEYBOARD, 0x08, 0, 0, 0, 0, 0, 0, 0, 0)
        return sendFrame(payload)
    }

    // ── Mouse (relative) ──────────────────────────────────────────────────

    fun sendMouseRelative(buttons: Byte, dx: Int, dy: Int, wheel: Int = 0): Boolean {
        // CH9329 relative mouse: xl=dx_low, xh=dx_high (signed 16-bit), same for y
        val dxClamped = dx.coerceIn(-32768, 32767)
        val dyClamped = dy.coerceIn(-32768, 32767)
        val payload = byteArrayOf(
            CMD_MOUSE_REL, 0x05,
            buttons,
            (dxClamped and 0xFF).toByte(),
            ((dxClamped shr 8) and 0xFF).toByte(),
            (dyClamped and 0xFF).toByte(),
            ((dyClamped shr 8) and 0xFF).toByte(),
        )
        return sendFrame(payload)
    }

    // ── Mouse (absolute — for VNC / remote desktop use) ───────────────────

    /**
     * Send absolute mouse position. [x] and [y] are in the range 0–4095
     * (CH9329 uses 12-bit absolute coordinates).
     */
    fun sendMouseAbsolute(buttons: Byte, x: Int, y: Int): Boolean {
        val xClamped = x.coerceIn(0, 4095)
        val yClamped = y.coerceIn(0, 4095)
        val payload = byteArrayOf(
            CMD_MOUSE_ABS, 0x07,
            buttons,
            0x00,
            (xClamped and 0xFF).toByte(),
            ((xClamped shr 8) and 0x0F).toByte(),
            (yClamped and 0xFF).toByte(),
            ((yClamped shr 8) and 0x0F).toByte(),
            0x00,
        )
        return sendFrame(payload)
    }

    fun sendMouseEvent(event: MouseEvent): Boolean =
        sendMouseRelative(event.buttons, event.dx.toInt(), event.dy.toInt(), event.wheel.toInt())

    // ── Internal ──────────────────────────────────────────────────────────

    private fun sendFrame(payload: ByteArray): Boolean {
        val ep = bulkOut ?: return false
        val conn = connection ?: return false
        val frame = buildFrame(payload)
        val sent = conn.bulkTransfer(ep, frame, frame.size, TRANSFER_TIMEOUT)
        if (sent < 0) Log.e(TAG, "bulkTransfer failed (${frame.size} bytes)")
        return sent == frame.size
    }

    /**
     * Build a CH9329 frame: HEADER + payload + checksum.
     * Checksum = (sum of all bytes in header + payload) & 0xFF
     */
    private fun buildFrame(payload: ByteArray): ByteArray {
        val frame = FRAME_HEADER + payload
        val checksum = (frame.sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
        return frame + byteArrayOf(checksum)
    }

    private fun isCompatibleBridge(device: UsbDevice): Boolean {
        if (device.vendorId in KNOWN_BRIDGE_VIDS) return true
        // Also accept any device that has a CDC or HID bulk-out interface
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_CDC ||
                iface.interfaceClass == USB_CLASS_CDC_DATA ||
                iface.interfaceClass == USB_CLASS_HID) return true
        }
        return false
    }

    private fun findBulkOutInterface(device: UsbDevice): UsbInterface? {
        // Prefer CDC data interface; fall back to any interface with a bulk-out endpoint
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_CDC_DATA && findBulkOutEndpoint(iface) != null) return iface
        }
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (findBulkOutEndpoint(iface) != null) return iface
        }
        return null
    }

    private fun findBulkOutEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_OUT) return ep
        }
        return null
    }

    private val Int.hex get() = "0x%04X".format(this)

    private operator fun ByteArray.plus(other: ByteArray) =
        ByteArray(size + other.size).also { copyInto(it); other.copyInto(it, size) }
}
