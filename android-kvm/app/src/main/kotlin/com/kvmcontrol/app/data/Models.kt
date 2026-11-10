package com.kvmcontrol.app.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * USB = root-required kernel gadget (/dev/hidg0 direct)
 * USB_BRIDGE = rootless via CH9329 OTG dongle
 * SHIZUKU_USB = /dev/hidg0 via Shizuku elevated process (no permanent root)
 */
enum class ConnectionType { BLUETOOTH, USB, USB_BRIDGE, SHIZUKU_USB, NETWORK }
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
enum class TransportProtocol { VNC, SSH, CUSTOM }

@Parcelize
data class KvmDevice(
    val id: String,
    val name: String,
    val address: String,
    val type: ConnectionType,
    val port: Int = 5900,
    val protocol: TransportProtocol = TransportProtocol.VNC,
) : Parcelable

data class ConnectionConfig(
    val device: KvmDevice,
    val password: String = "",
    val sshUser: String = "",
    val sshKeyPath: String = "",
    val autoReconnect: Boolean = true,
    val reconnectDelayMs: Long = 3000L,
)

data class KvmStats(
    val cpuPercent: Float = 0f,
    val ramUsedMb: Long = 0L,
    val ramTotalMb: Long = 0L,
    val networkRxKbps: Float = 0f,
    val networkTxKbps: Float = 0f,
    val latencyMs: Long = -1L,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val deviceName: String = "",
    val uptimeSeconds: Long = 0L,
) {
    val ramPercent: Float get() = if (ramTotalMb > 0) ramUsedMb.toFloat() / ramTotalMb * 100f else 0f
}

data class KeyEvent(
    val modifier: Byte = 0,
    val keys: List<Byte> = emptyList(),
)

data class MouseEvent(
    val buttons: Byte = 0,
    val dx: Byte = 0,
    val dy: Byte = 0,
    val wheel: Byte = 0,
)
