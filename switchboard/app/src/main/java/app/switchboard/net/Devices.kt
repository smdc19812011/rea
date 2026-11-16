package app.switchboard.net

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Base64
import androidx.core.content.ContextCompat
import app.switchboard.data.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** BLE Client Characteristic Configuration descriptor (turns notifications on). */
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** An open connection to a device. Reads collect whatever arrives within the timeout. */
internal interface DeviceSession {
    val summary: String
    suspend fun write(data: ByteArray, target: String?)
    suspend fun read(timeoutMs: Int, maxBytes: Int, target: String?): ByteArray
    fun close()
}

/**
 * USB, Bluetooth and network I/O for the model. Always runs on the phone, because that's where the hardware is.
 * Android apps can't load kernel drivers; USB serial chips are driven from user space here (CDC-ACM, CP210x, CH34x, FTDI),
 * and anything else is reachable as raw USB bulk/interrupt transfers.
 */
class DeviceManager(private val ctx: Context, private val askPermissions: suspend (Array<String>) -> Boolean) {
    private val sessions = ConcurrentHashMap<String, DeviceSession>()
    private var nextId = 1
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val LIST = "list_devices"
        const val CONNECT = "device_connect"
        const val WRITE = "device_write"
        const val READ = "device_read"
        const val CLOSE = "device_close"
        const val HTTP = "http_request"
        const val DOWNLOAD = "download_file"
        const val ADB = "adb"
        val names = setOf(LIST, CONNECT, WRITE, READ, CLOSE, HTTP, DOWNLOAD, ADB)
        /** Read-only or cleanup actions that don't need the user's approval. */
        val noApproval = setOf(LIST, READ, CLOSE)
        private val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val USB_PERMISSION = "app.switchboard.USB_PERMISSION"

        private fun s(desc: String) = buildJsonObject { put("type", "string"); put("description", desc) }
        private fun i(desc: String) = buildJsonObject { put("type", "integer"); put("description", desc) }
        private fun obj(required: List<String>, vararg props: Pair<String, JsonObject>) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
            putJsonArray("required") { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
        private val encoding = "encoding" to s("text (UTF-8, default), hex (e.g. \"01 A0 FF\") or base64")

        fun tools(includeDownload: Boolean): List<ToolSpec> = listOfNotNull(
            ToolSpec(LIST, "Find devices the phone can talk to: USB devices plugged in (with VID/PID and whether a built-in serial driver fits), " +
                "Bluetooth (paired, plus a scan for nearby Classic and BLE devices), and the local network (phone's IP, mDNS/Bonjour services).",
                obj(emptyList(), "kind" to s("usb, bluetooth, network or all (default all)"),
                    "scan_seconds" to i("How long to scan Bluetooth/network, default 6, max 30"),
                    "service_type" to s("Extra mDNS type to look for, e.g. _esphomelib._tcp"))),
            ToolSpec(CONNECT, "Open a connection and get a handle for device_write/device_read. Types: " +
                "usb_serial (id = USB device name from list_devices; options baud, default 115200; driver auto, cdc, cp210x, ch34x or ftdi), " +
                "usb_raw (id = USB device name; options interface, default 0), bt_serial (Classic Bluetooth SPP; id = MAC address), " +
                "ble (id = MAC; returns services and characteristics), tcp (id = host:port), udp (id = host:port; broadcast allowed), " +
                "usb_hid (id = USB device name; HID keyboards, controllers, scanners, custom HID boards: read input reports, write output reports; shows the report descriptor), " +
                "bt_keyboard (this phone acts as a Bluetooth keyboard for a paired computer/TV; id = its MAC, or empty to wait for the computer to connect; device_write types the text, with {ENTER} {TAB} {BACKSPACE} {ESC} {UP} {DOWN} {LEFT} {RIGHT}).",
                obj(listOf("type", "id"), "type" to s("usb_serial, usb_raw, usb_hid, bt_serial, ble, bt_keyboard, tcp or udp"), "id" to s("Device name, MAC address or host:port"),
                    "baud" to i("usb_serial baud rate, default 115200"), "driver" to s("usb_serial: auto (default), cdc, cp210x, ch34x, ftdi"),
                    "interface" to i("usb_raw interface index, default 0"))),
            ToolSpec(WRITE, "Send bytes to an open device. For BLE, target is the characteristic UUID (or service/characteristic).",
                obj(listOf("handle", "data"), "handle" to s("Handle from device_connect"), "data" to s("What to send"), encoding,
                    "target" to s("BLE characteristic UUID"))),
            ToolSpec(READ, "Read whatever the device sends within timeout_ms. For BLE: with a target, reads that characteristic (or subscribes " +
                "and collects notifications if it only notifies); without one, returns notifications received so far.",
                obj(listOf("handle"), "handle" to s("Handle from device_connect"), "timeout_ms" to i("Default 2000, max 60000"),
                    "max_bytes" to i("Default 4096"), "target" to s("BLE characteristic UUID"))),
            ToolSpec(CLOSE, "Close a device handle.", obj(listOf("handle"), "handle" to s("Handle from device_connect"))),
            ToolSpec(HTTP, "Make an HTTP(S) request from the phone (APIs, smart devices on the local network, etc.).",
                obj(listOf("url"), "url" to s("Full URL"), "method" to s("GET (default), POST, PUT, PATCH, DELETE"),
                    "headers" to buildJsonObject { put("type", "object"); put("description", "Header name to value") },
                    "body" to s("Request body text"))),
            ToolSpec(ADB, "Built-in ADB client (works in every mode, no install needed): control another Android device over the network. " +
                "The device needs ADB over TCP enabled (port 5555: Android TV/Fire TV/emulators, rooted devices, or any phone after `adb tcpip 5555` from a computer). " +
                "The first time, the device asks to allow this phone. Actions: shell (run a command), push (copy a file from the tool machine), install (an .apk from the tool machine), disconnect.",
                obj(listOf("target", "action"), "target" to s("IP or host, optionally :port (default 5555)"), "action" to s("shell, push, install or disconnect"),
                    "command" to s("shell: the command to run on the device"), "local_path" to s("push/install: file on the tool machine"),
                    "remote_path" to s("push: destination on the device, e.g. /sdcard/Download/file.txt"))),
            if (includeDownload) ToolSpec(DOWNLOAD, "Download a file from a URL into the phone workspace (up to 500 MB). " +
                "Android doesn't let apps execute downloaded programs, so use this for data, firmware, archives and files to send_file.",
                obj(listOf("url", "path"), "url" to s("https:// URL"), "path" to s("Destination path in the workspace"))) else null,
        )
    }

    private val adbClients = ConcurrentHashMap<String, AdbClient>()

    /** Runs a device tool. [workspace] is where download_file saves (phone mode); [fetch] copies a file from the tool machine. */
    suspend fun execute(call: ToolCall, workspace: File, unrestricted: Boolean, fetch: suspend (String, File) -> Unit = { p, d -> File(p).copyTo(d, true) }): ToolResult {
        val a = runCatching { json.parseToJsonElement(call.arguments).jsonObject }.getOrElse { return ToolResult("Arguments were not valid JSON.", true) }
        return try {
            when (call.name) {
                LIST -> ToolResult(list(a.str("kind") ?: "all", a.int("scan_seconds", 6).coerceIn(1, 30), a.str("service_type")))
                CONNECT -> connect(a)
                WRITE -> {
                    val s = session(a); val bytes = decode(a.str("data").orEmpty(), a.str("encoding"))
                    s.write(bytes, a.str("target")); ToolResult("Sent ${bytes.size} bytes.")
                }
                READ -> {
                    val s = session(a)
                    val bytes = s.read(a.int("timeout_ms", 2000).coerceIn(10, 60_000), a.int("max_bytes", 4096).coerceIn(1, 1 shl 20), a.str("target"))
                    ToolResult(describeBytes(bytes))
                }
                CLOSE -> { sessions.remove(a.str("handle"))?.close(); ToolResult("Closed.") }
                HTTP -> http(a, unrestricted)
                DOWNLOAD -> download(a, workspace)
                ADB -> adb(a, fetch)
                else -> ToolResult("Unknown device tool ${call.name}", true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: SecurityException) { ToolResult("Android blocked this (${e.message}). The user may need to grant the permission.", true)
        } catch (e: Exception) { ToolResult(e.message ?: e.javaClass.simpleName, true) }
    }

    fun closeAll() {
        sessions.values.forEach { runCatching { it.close() } }; sessions.clear()
        adbClients.values.forEach { it.close() }; adbClients.clear()
    }

    private suspend fun adb(a: JsonObject, fetch: suspend (String, File) -> Unit): ToolResult = withContext(Dispatchers.IO) {
        val target = a.str("target")?.trim()?.ifBlank { null } ?: return@withContext ToolResult("Missing 'target' (IP or host[:port]).", true)
        val host = target.substringBeforeLast(':').takeIf { ':' in target && target.substringAfterLast(':').all(Char::isDigit) } ?: target
        val port = if (host != target) target.substringAfterLast(':').toInt() else 5555
        val key = "$host:$port"
        if (a.str("action") == "disconnect") { adbClients.remove(key)?.close(); return@withContext ToolResult("Disconnected from $key.") }
        val client = adbClients[key]?.takeIf { it.isConnected } ?: AdbClient(File(ctx.filesDir, "keys/adbkey.pk8")).also {
            it.connect(host, port) // first time: the device shows "Allow USB debugging?" and we wait for it
            adbClients[key] = it
        }
        fun local(): File {
            val path = a.str("local_path") ?: throw IllegalArgumentException("Missing 'local_path'.")
            val f = File(ctx.cacheDir, "adb/${File(path).name}")
            kotlinx.coroutines.runBlocking { fetch(path, f) }
            return f
        }
        try {
            when (a.str("action")) {
                "shell" -> ToolResult("${client.shell(a.str("command") ?: return@withContext ToolResult("Missing 'command'.", true)).take(50_000).ifBlank { "(no output)" }}")
                "push" -> {
                    val f = local(); val remote = a.str("remote_path") ?: "/sdcard/Download/${f.name}"
                    client.push(f.readBytes(), remote); f.delete()
                    ToolResult("Pushed ${f.length()} bytes to $remote on $key.")
                }
                "install" -> { val f = local(); val out = client.install(f.readBytes(), f.name.replace(Regex("[^A-Za-z0-9._-]"), "_")); f.delete(); ToolResult(out, isError = !out.contains("Success")) }
                else -> ToolResult("action must be shell, push, install or disconnect.", true)
            }
        } catch (e: Exception) {
            adbClients.remove(key)?.close() // reconnect next time
            ToolResult("ADB ${a.str("action")} failed on $key: ${e.message}", true)
        }
    }

    private fun session(a: JsonObject) = sessions[a.str("handle")] ?: throw IllegalArgumentException("No open device with handle '${a.str("handle")}'. Call device_connect first.")

    private fun register(s: DeviceSession): ToolResult {
        val h = "dev${nextId++}"; sessions[h] = s
        return ToolResult("Connected. handle=$h\n${s.summary}")
    }

    // ---------- discovery ----------

    private suspend fun list(kind: String, seconds: Int, extraType: String?): String = buildString {
        if (kind == "all" || kind == "usb") appendLine(listUsb())
        if (kind == "all" || kind == "bluetooth") appendLine(listBluetooth(seconds))
        if (kind == "all" || kind == "network") appendLine(listNetwork(seconds, extraType))
    }.trim()

    private fun usbManager() = ctx.getSystemService(UsbManager::class.java)

    private fun listUsb(): String {
        val devices = usbManager()?.deviceList?.values.orEmpty()
        if (devices.isEmpty()) return "USB: nothing attached (a USB-OTG adapter may be needed)."
        return "USB:\n" + devices.joinToString("\n") { d ->
            val name = listOfNotNull(runCatching { d.manufacturerName }.getOrNull(), runCatching { d.productName }.getOrNull()).joinToString(" ").ifBlank { "unknown" }
            "- ${d.deviceName}: $name, VID 0x%04X PID 0x%04X, interfaces %d, serial driver: %s".format(d.vendorId, d.productId, d.interfaceCount, serialDriverFor(d) ?: "none (use usb_raw)")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun listBluetooth(seconds: Int): String {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return "Bluetooth: not available on this phone."
        if (!ensureBluetooth()) return "Bluetooth: permission denied by the user."
        if (!adapter.isEnabled) return "Bluetooth: turned off. Ask the user to turn it on."
        val found = ConcurrentHashMap<String, String>()
        adapter.bondedDevices.orEmpty().forEach { found[it.address] = "${it.name ?: "?"} (paired, ${typeOf(it)})" }
        val classic = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION") val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                found.putIfAbsent(d.address, "${d.name ?: "?"} (nearby, ${typeOf(d)})")
            }
        }
        val le = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                found.putIfAbsent(r.device.address, "${r.scanRecord?.deviceName ?: r.device.name ?: "?"} (BLE, RSSI ${r.rssi})")
            }
        }
        ContextCompat.registerReceiver(ctx, classic, IntentFilter(BluetoothDevice.ACTION_FOUND), ContextCompat.RECEIVER_EXPORTED)
        try {
            adapter.startDiscovery(); adapter.bluetoothLeScanner?.startScan(le)
            delay(seconds * 1000L)
        } finally {
            runCatching { adapter.cancelDiscovery() }; runCatching { adapter.bluetoothLeScanner?.stopScan(le) }
            ctx.unregisterReceiver(classic)
        }
        if (found.isEmpty()) return "Bluetooth: no devices found."
        return "Bluetooth:\n" + found.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
    }

    @SuppressLint("MissingPermission")
    private fun typeOf(d: BluetoothDevice) = when (d.type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"; BluetoothDevice.DEVICE_TYPE_LE -> "BLE"; BluetoothDevice.DEVICE_TYPE_DUAL -> "Classic+BLE"; else -> "unknown"
    }

    private suspend fun listNetwork(seconds: Int, extraType: String?): String {
        val ips = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { n -> n.interfaceAddresses.filter { it.address.hostAddress?.contains(':') == false }.map { "${n.name} ${it.address.hostAddress}/${it.networkPrefixLength}" } }
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val types = listOfNotNull("_http._tcp", "_https._tcp", "_ipp._tcp", "_printer._tcp", "_googlecast._tcp", "_airplay._tcp", "_raop._tcp",
            "_ssh._tcp", "_smb._tcp", "_mqtt._tcp", "_esphomelib._tcp", "_arduino._tcp", "_hap._tcp", "_workstation._tcp", extraType?.trimEnd('.'))
            .distinct()
        val found = ConcurrentLinkedQueue<NsdServiceInfo>()
        val lock = ctx.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("switchboard")?.apply { setReferenceCounted(false); acquire() }
        val listeners = types.map { t ->
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(s: NsdServiceInfo) { found += s }
                override fun onServiceLost(s: NsdServiceInfo) {}
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
            }.also { l -> runCatching { nsd.discoverServices(t, NsdManager.PROTOCOL_DNS_SD, l) } }
        }
        try { delay(seconds * 1000L) } finally { listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } } }
        val lines = found.distinctBy { it.serviceName + it.serviceType }.take(25).map { s ->
            val r = withTimeoutOrNull(2500) { resolve(nsd, s) }
            "- ${s.serviceName} [${s.serviceType.trimEnd('.')}]" + (r?.let { " at ${it.host?.hostAddress}:${it.port}" } ?: "")
        }
        lock?.release()
        return "Network:\n- phone: ${ips.joinToString(", ").ifBlank { "no network" }}\n" +
            (if (lines.isEmpty()) "- no mDNS services found (devices without mDNS can still be reached with tcp/udp/http_request by IP)" else lines.joinToString("\n"))
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, s: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        runCatching {
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) { if (cont.isActive) cont.resumeWith(Result.success(null)) }
                override fun onServiceResolved(i: NsdServiceInfo) { if (cont.isActive) cont.resumeWith(Result.success(i)) }
            })
        }.onFailure { if (cont.isActive) cont.resumeWith(Result.success(null)) }
    }

    // ---------- connect ----------

    private suspend fun connect(a: JsonObject): ToolResult {
        val id = a.str("id") ?: return ToolResult("Missing 'id'.", true)
        val session: DeviceSession = when (a.str("type")) {
            "usb_serial" -> usbSerial(id, a.int("baud", 115200), a.str("driver") ?: "auto")
            "usb_raw" -> usbRaw(id, a.int("interface", 0))
            "usb_hid" -> usbHid(id, a["interface"]?.let { a.int("interface", 0) })
            "bt_keyboard" -> BtKeyboardSession.open(ctx, id.takeIf { it.isNotBlank() }?.let { adapterDevice(it) }, ::ensureBluetooth)
            "bt_serial" -> btSerial(id)
            "ble" -> BleSession.open(ctx, adapterDevice(id))
            "tcp" -> withContext(Dispatchers.IO) { TcpSession(hostPort(id)) }
            "udp" -> withContext(Dispatchers.IO) { UdpSession(hostPort(id)) }
            else -> return ToolResult("Unknown type '${a.str("type")}'. Use usb_serial, usb_raw, usb_hid, bt_serial, ble, bt_keyboard, tcp or udp.", true)
        }
        return register(session)
    }

    private fun hostPort(id: String): InetSocketAddress {
        val host = id.substringBeforeLast(':'); val port = id.substringAfterLast(':').toIntOrNull()
            ?: throw IllegalArgumentException("Use host:port, e.g. 192.168.1.50:23")
        return InetSocketAddress(host.trim('[', ']'), port)
    }

    // ---------- USB ----------

    private fun serialDriverFor(d: UsbDevice): String? = when {
        d.vendorId == 0x0403 -> "ftdi"
        d.vendorId == 0x10C4 -> "cp210x"
        d.vendorId == 0x1A86 -> "ch34x"
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA } -> "cdc"
        else -> null
    }

    private suspend fun openUsb(name: String): Pair<UsbDevice, UsbDeviceConnection> {
        val m = usbManager() ?: throw IllegalStateException("USB host isn't supported on this phone.")
        val d = m.deviceList[name] ?: m.deviceList.values.firstOrNull { it.deviceName.endsWith(name) || "%04X:%04X".format(it.vendorId, it.productId).equals(name, true) }
            ?: throw IllegalArgumentException("No USB device '$name'. Run list_devices.")
        if (!m.hasPermission(d) && !requestUsbPermission(m, d)) throw SecurityException("the user didn't allow access to this USB device")
        val c = m.openDevice(d) ?: throw IllegalStateException("Couldn't open the USB device.")
        return d to c
    }

    private suspend fun requestUsbPermission(m: UsbManager, d: UsbDevice): Boolean = withTimeoutOrNull(120_000) {
        suspendCancellableCoroutine { cont ->
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    runCatching { ctx.unregisterReceiver(this) }
                    if (cont.isActive) cont.resumeWith(Result.success(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)))
                }
            }
            ContextCompat.registerReceiver(ctx, r, IntentFilter(USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            m.requestPermission(d, PendingIntent.getBroadcast(ctx, 0, Intent(USB_PERMISSION).setPackage(ctx.packageName), flags))
            cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(r) } }
        }
    } ?: false

    private suspend fun usbSerial(name: String, baud: Int, driverArg: String): DeviceSession {
        val (d, c) = openUsb(name)
        val driver = if (driverArg == "auto") serialDriverFor(d) ?: run { c.close(); throw IllegalArgumentException("No built-in serial driver for VID 0x%04X; try usb_raw or driver=cdc.".format(d.vendorId)) } else driverArg
        return try { UsbSerialSession.open(d, c, driver, baud) } catch (e: Exception) { c.close(); throw e }
    }

    private suspend fun usbRaw(name: String, iface: Int): DeviceSession {
        val (d, c) = openUsb(name)
        if (iface !in 0 until d.interfaceCount) { c.close(); throw IllegalArgumentException("Interface $iface doesn't exist (device has ${d.interfaceCount}).") }
        val intf = d.getInterface(iface)
        if (!c.claimInterface(intf, true)) { c.close(); throw IllegalStateException("Couldn't claim interface $iface.") }
        val eps = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
        val out = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
        val inn = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        return UsbStreamSession(c, intf, inn, out, stripFtdi = false,
            summary = "Raw USB interface $iface (class ${intf.interfaceClass}), endpoints: " +
                eps.joinToString { "${if (it.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"} 0x%02X ${epType(it)} %dB".format(it.address, it.maxPacketSize) })
    }

    /** HID interface: input reports from the interrupt IN endpoint; output reports via interrupt OUT or SET_REPORT. */
    private suspend fun usbHid(name: String, iface: Int?): DeviceSession {
        val (d, c) = openUsb(name)
        val intf = iface?.let { d.getInterface(it) } ?: (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: run { c.close(); throw IllegalArgumentException("No HID interface on this device; try usb_raw.") }
        if (!c.claimInterface(intf, true)) { c.close(); throw IllegalStateException("Couldn't claim the HID interface (Android may own it as a keyboard/mouse).") }
        val eps = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_INT }
        val desc = ByteArray(4096)
        val n = c.controlTransfer(0x81, 0x06, 0x22 shl 8, intf.id, desc, desc.size, 1000) // GET_DESCRIPTOR (HID report)
        val report = if (n > 0) desc.copyOf(n).toHex() else "unavailable"
        return HidSession(c, intf, eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }, eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT },
            "USB HID interface ${intf.id} (VID 0x%04X PID 0x%04X). Report descriptor (${n.coerceAtLeast(0)} bytes): $report".format(d.vendorId, d.productId))
    }

    private fun epType(e: UsbEndpoint) = when (e.type) { UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"; UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"; UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"; else -> "control" }

    // ---------- Bluetooth ----------

    private suspend fun ensureBluetooth(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return perms.all { ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED } || askPermissions(perms)
    }

    private suspend fun adapterDevice(mac: String): BluetoothDevice {
        if (!ensureBluetooth()) throw SecurityException("Bluetooth permission denied")
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IllegalStateException("No Bluetooth on this phone.")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is off. Ask the user to turn it on.")
        if (!BluetoothAdapter.checkBluetoothAddress(mac.uppercase())) throw IllegalArgumentException("'$mac' isn't a Bluetooth MAC address.")
        return adapter.getRemoteDevice(mac.uppercase())
    }

    @SuppressLint("MissingPermission")
    private suspend fun btSerial(mac: String): DeviceSession {
        val d = adapterDevice(mac)
        return withContext(Dispatchers.IO) {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery()
            val sock = d.createRfcommSocketToServiceRecord(SPP)
            try { sock.connect() } catch (e: Exception) { runCatching { sock.close() }; throw IllegalStateException("Bluetooth serial connect failed: ${e.message}. Is the device paired and on?") }
            BtSerialSession(sock, "Bluetooth serial (SPP) to ${d.name ?: mac}")
        }
    }

    // ---------- HTTP + download ----------

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun checkUrl(url: String, unrestricted: Boolean) = (url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Not a valid URL: $url")).also {
        if (!it.isHttps && !unrestricted && !isLocalHost(it.host)) throw IllegalArgumentException("Plain http:// to the internet needs Unrestricted mode; local network addresses are fine.")
    }

    private suspend fun http(a: JsonObject, unrestricted: Boolean): ToolResult = withContext(Dispatchers.IO) {
        val url = checkUrl(a.str("url") ?: return@withContext ToolResult("Missing 'url'.", true), unrestricted)
        val method = (a.str("method") ?: "GET").uppercase()
        val body = a.str("body")?.toRequestBody((a["headers"] as? JsonObject)?.str("Content-Type")?.toMediaTypeOrNull())
            ?: if (method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
        val b = Request.Builder().url(url).method(method, body)
        (a["headers"] as? JsonObject)?.forEach { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { b.header(k, it) } }
        http.newCall(b.build()).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            val type = r.header("Content-Type").orEmpty()
            val text = if (looksText(bytes) || "json" in type || "text" in type || "xml" in type) bytes.decodeToString().take(50_000)
            else "(${bytes.size} bytes of binary data; first 256 as hex) " + bytes.take(256).toByteArray().toHex()
            ToolResult("HTTP ${r.code}\nContent-Type: $type\n\n$text", isError = !r.isSuccessful)
        }
    }

    private suspend fun download(a: JsonObject, workspace: File): ToolResult = withContext(Dispatchers.IO) {
        val url = checkUrl(a.str("url") ?: return@withContext ToolResult("Missing 'url'.", true), unrestricted = false)
        val rel = a.str("path")?.takeIf { it.isNotBlank() } ?: url.pathSegments.lastOrNull()?.ifBlank { null } ?: "download"
        val dest = (if (rel.startsWith("/")) File(rel) else File(workspace, rel)).normalize()
        val client = http.newBuilder().readTimeout(10, TimeUnit.MINUTES).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return@withContext ToolResult("Download failed: HTTP ${r.code}", true)
            dest.parentFile?.mkdirs()
            var total = 0L
            r.body!!.byteStream().use { input -> dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    total += n; if (total > 500L * 1024 * 1024) { out.close(); dest.delete(); return@withContext ToolResult("Stopped: larger than 500 MB.", true) }
                    out.write(buf, 0, n)
                }
            } }
            ToolResult("Downloaded $total bytes to ${dest.path}.")
        }
    }
}

// ---------- byte helpers ----------

internal fun decode(data: String, encoding: String?): ByteArray = when (encoding?.lowercase()) {
    null, "", "text", "utf8", "utf-8" -> data.toByteArray()
    "hex" -> data.replace(Regex("0x|[\\s,:]"), "").let { h ->
        require(h.length % 2 == 0 && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Not valid hex: $data" }
        ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    "base64" -> Base64.decode(data, Base64.DEFAULT)
    else -> throw IllegalArgumentException("Unknown encoding '$encoding'; use text, hex or base64.")
}

internal fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
internal fun looksText(b: ByteArray) = b.isNotEmpty() && b.count { it in 32..126 || it == 9.toByte() || it == 10.toByte() || it == 13.toByte() || it < 0 } >= b.size * 0.9

internal fun describeBytes(b: ByteArray): String = when {
    b.isEmpty() -> "Nothing received within the timeout."
    looksText(b) -> "Received ${b.size} bytes (text):\n${b.decodeToString()}"
    else -> "Received ${b.size} bytes (hex):\n${b.toHex()}"
}

// ---------- sessions ----------

/** USB bulk/interrupt stream. FTDI chips prefix every packet with 2 status bytes, which are removed. */
private open class UsbStreamSession(
    private val c: UsbDeviceConnection, private val intf: UsbInterface, private val inEp: UsbEndpoint?, private val outEp: UsbEndpoint?,
    private val stripFtdi: Boolean, override val summary: String,
) : DeviceSession {
    private val lock = Mutex()

    override suspend fun write(data: ByteArray, target: String?) = lock.withLock {
        val ep = outEp ?: throw IllegalStateException("This interface has no OUT endpoint.")
        withContext(Dispatchers.IO) {
            var off = 0
            while (off < data.size) {
                val n = c.bulkTransfer(ep, data, off, minOf(16 * 1024, data.size - off), 2000)
                if (n < 0) throw IllegalStateException("USB write failed after $off bytes.")
                off += n
            }
        }
    }

    override suspend fun read(timeoutMs: Int, maxBytes: Int, target: String?): ByteArray = lock.withLock {
        val ep = inEp ?: throw IllegalStateException("This interface has no IN endpoint.")
        withContext(Dispatchers.IO) {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(maxOf(ep.maxPacketSize, 64) * 16)
            val deadline = System.currentTimeMillis() + timeoutMs
            var quietSince = 0L
            while (out.size() < maxBytes && System.currentTimeMillis() < deadline) {
                val n = c.bulkTransfer(ep, buf, buf.size, 100)
                val payload = if (n > 0) (if (stripFtdi) ftdiPayload(buf, n, ep.maxPacketSize) else buf.copyOf(n)) else ByteArray(0)
                if (payload.isNotEmpty()) { out.write(payload); quietSince = System.currentTimeMillis() }
                // After data has arrived, return once the line goes quiet for 300 ms.
                else if (out.size() > 0 && System.currentTimeMillis() - quietSince > 300) break
            }
            out.toByteArray().let { if (it.size > maxBytes) it.copyOf(maxBytes) else it }
        }
    }

    private fun ftdiPayload(buf: ByteArray, n: Int, packet: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < n) { val end = minOf(i + packet, n); if (end - i > 2) out.write(buf, i + 2, end - i - 2); i = end }
        return out.toByteArray()
    }

    override fun close() { runCatching { c.releaseInterface(intf) }; c.close() }
}

/** HID: reads like a stream; writes go to the interrupt OUT endpoint, or SET_REPORT on the control pipe when there isn't one. */
private class HidSession(private val conn: UsbDeviceConnection, private val iface: UsbInterface, inEp: UsbEndpoint?, private val outEp: UsbEndpoint?, summary: String) :
    UsbStreamSession(conn, iface, inEp, outEp, stripFtdi = false, summary = summary) {
    override suspend fun write(data: ByteArray, target: String?) {
        if (outEp != null) return super.write(data, target)
        val reportId = target?.toIntOrNull() ?: 0
        val r = withContext(Dispatchers.IO) { conn.controlTransfer(0x21, 0x09, (0x02 shl 8) or reportId, iface.id, data, data.size, 2000) }
        if (r < 0) throw IllegalStateException("SET_REPORT failed.")
    }
}

/**
 * This phone as a Bluetooth keyboard (HID device profile, Android 9+): types text on a paired computer, tablet or TV.
 */
@SuppressLint("MissingPermission")
private class BtKeyboardSession private constructor(private val ctx: Context, private val hid: android.bluetooth.BluetoothHidDevice, private val host: BluetoothDevice) : DeviceSession {
    override val summary = "Typing as a Bluetooth keyboard on ${host.name ?: host.address}. device_write sends text; device_close disconnects."

    companion object {
        private val DESCRIPTOR = byteArrayOf(0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01, 0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(),
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01,
            0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00, 0xC0.toByte())

        suspend fun open(ctx: Context, target: BluetoothDevice?, ensure: suspend () -> Boolean): DeviceSession {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) throw IllegalStateException("Bluetooth keyboard mode needs Android 9 or newer.")
            if (!ensure()) throw SecurityException("Bluetooth permission denied")
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IllegalStateException("No Bluetooth on this phone.")
            val proxy = CompletableDeferred<android.bluetooth.BluetoothHidDevice>()
            val ok = adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, p: BluetoothProfile) { proxy.complete(p as android.bluetooth.BluetoothHidDevice) }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HID_DEVICE)
            if (!ok) throw IllegalStateException("This phone doesn't offer the Bluetooth keyboard (HID device) profile.")
            val hid = withTimeoutOrNull(5000) { proxy.await() } ?: throw IllegalStateException("Bluetooth HID service didn't start.")
            val registered = CompletableDeferred<Boolean>(); val connected = CompletableDeferred<BluetoothDevice>()
            val sdp = android.bluetooth.BluetoothHidDeviceAppSdpSettings("Switchboard Keyboard", "Keyboard", "Switchboard",
                android.bluetooth.BluetoothHidDevice.SUBCLASS1_KEYBOARD, DESCRIPTOR)
            hid.registerApp(sdp, null, null, java.util.concurrent.Executors.newSingleThreadExecutor(), object : android.bluetooth.BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(device: BluetoothDevice?, isRegistered: Boolean) { registered.complete(isRegistered) }
                override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) { if (state == BluetoothProfile.STATE_CONNECTED) connected.complete(device) }
            })
            if (withTimeoutOrNull(5000) { registered.await() } != true) { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid); throw IllegalStateException("Couldn't register as a keyboard.") }
            target?.let { hid.connect(it) }
            val dev = withTimeoutOrNull(if (target != null) 20_000 else 90_000) { connected.await() } ?: run {
                hid.unregisterApp(); adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
                throw IllegalStateException(if (target != null) "The computer didn't accept the keyboard connection. Pair it with this phone first." else
                    "No computer connected within 90 s. Keep this phone's Bluetooth settings open (so it's visible) and pair it from the computer, then try again.")
            }
            return BtKeyboardSession(ctx, hid, dev)
        }

        private val keys = mapOf("ENTER" to 0x28, "ESC" to 0x29, "BACKSPACE" to 0x2A, "TAB" to 0x2B, "RIGHT" to 0x4F, "LEFT" to 0x50, "DOWN" to 0x51, "UP" to 0x52, "DELETE" to 0x4C)
        private const val SHIFT = 0x02
        /** US layout: character -> (modifier, usage). */
        fun keyFor(c: Char): Pair<Int, Int>? = when (c) {
            in 'a'..'z' -> 0 to (0x04 + (c - 'a')); in 'A'..'Z' -> SHIFT to (0x04 + (c - 'A'))
            in '1'..'9' -> 0 to (0x1E + (c - '1')); '0' -> 0 to 0x27
            '\n' -> 0 to 0x28; '\t' -> 0 to 0x2B; ' ' -> 0 to 0x2C
            else -> "!@#$%^&*()".indexOf(c).takeIf { it >= 0 }?.let { SHIFT to (0x1E + it) }
                ?: "-=[]\\;'`,./".indexOf(c).takeIf { it >= 0 }?.let { 0 to intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)[it] }
                ?: "_+{}|:\"~<>?".indexOf(c).takeIf { it >= 0 }?.let { SHIFT to intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)[it] }
        }
    }

    private suspend fun press(mod: Int, usage: Int) {
        hid.sendReport(host, 1, byteArrayOf(mod.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0)); delay(12)
        hid.sendReport(host, 1, ByteArray(8)); delay(12)
    }

    override suspend fun write(data: ByteArray, target: String?) {
        val text = data.decodeToString()
        var i = 0
        while (i < text.length) {
            val m = Regex("^\\{([A-Z]+)\\}").find(text.substring(i))
            if (m != null && keys[m.groupValues[1]] != null) { press(0, keys.getValue(m.groupValues[1])); i += m.value.length; continue }
            keyFor(text[i])?.let { (mod, usage) -> press(mod, usage) }
            i++
        }
    }

    override suspend fun read(timeoutMs: Int, maxBytes: Int, target: String?) = ByteArray(0)

    override fun close() {
        runCatching { hid.disconnect(host) }; runCatching { hid.unregisterApp() }
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
    }
}

/** User-space USB serial drivers for the common chip families. */
private object UsbSerialSession {
    fun open(d: UsbDevice, c: UsbDeviceConnection, driver: String, baud: Int): DeviceSession {
        fun ctrl(type: Int, req: Int, value: Int, index: Int, data: ByteArray? = null) {
            val r = c.controlTransfer(type, req, value, index, data, data?.size ?: 0, 1000)
            if (r < 0) throw IllegalStateException("$driver setup failed (request 0x%02X). Wrong driver or baud?".format(req))
        }
        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

        val dataIntf: UsbInterface = when (driver) {
            "cdc" -> (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
                ?: d.getInterface(d.interfaceCount - 1)
            else -> d.getInterface(0)
        }
        if (!c.claimInterface(dataIntf, true)) throw IllegalStateException("Couldn't claim the USB interface.")
        val eps = (0 until dataIntf.endpointCount).map { dataIntf.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val inEp = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        val outEp = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
        when (driver) {
            "cdc" -> {
                val control = (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
                control?.let { c.claimInterface(it, true) }
                val idx = control?.id ?: 0
                ctrl(0x21, 0x20, 0, idx, le32(baud) + byteArrayOf(0, 0, 8)) // SET_LINE_CODING 8N1
                ctrl(0x21, 0x22, 0x03, idx)                                   // SET_CONTROL_LINE_STATE: DTR + RTS
            }
            "cp210x" -> {
                ctrl(0x41, 0x00, 0x0001, 0)        // IFC_ENABLE
                ctrl(0x41, 0x1E, 0, 0, le32(baud))  // SET_BAUDRATE
                ctrl(0x41, 0x03, 0x0800, 0)        // SET_LINE_CTL 8N1
                ctrl(0x41, 0x07, 0x0303, 0)        // SET_MHS: DTR + RTS
            }
            "ch34x" -> {
                c.controlTransfer(0xC0, 0x5F, 0, 0, ByteArray(8), 8, 1000) // read version (wakes the chip)
                ctrl(0x40, 0xA1, 0, 0)                                     // init
                var factor = (1532620800L / baud); var divisor = 3
                while (factor > 0xFFF0 && divisor > 0) { factor = factor shr 3; divisor-- }
                if (factor > 0xFFF0) throw IllegalArgumentException("Baud $baud isn't supported by CH34x.")
                factor = 0x10000 - factor
                ctrl(0x40, 0x9A, 0x1312, ((factor and 0xFF00) or divisor.toLong()).toInt())
                ctrl(0x40, 0x9A, 0x0F2C, (factor and 0xFF).toInt())
                ctrl(0x40, 0x9A, 0x2518, 0x00C3) // LCR: enable RX/TX, 8N1
                ctrl(0x40, 0xA4, 0xFF and (0x20 or 0x40).inv(), 0) // modem control: DTR + RTS on (active low)
            }
            "ftdi" -> {
                ctrl(0x40, 0, 0, 0)          // reset
                ctrl(0x40, 1, 0x0303, 0)     // DTR + RTS
                val div8 = ((3_000_000L * 8 + baud / 2) / baud).toInt()
                val code = intArrayOf(0, 3, 2, 4, 1, 5, 6, 7)[div8 and 7]
                val value = (div8 shr 3) or ((code and 3) shl 14)
                ctrl(0x40, 3, value and 0xFFFF, code shr 2) // baud
                ctrl(0x40, 4, 0x0008, 0)     // 8N1
            }
            else -> throw IllegalArgumentException("Unknown driver '$driver'.")
        }
        return UsbStreamSession(c, dataIntf, inEp, outEp, stripFtdi = driver == "ftdi",
            summary = "USB serial via $driver driver at $baud baud, 8N1 (VID 0x%04X PID 0x%04X)".format(d.vendorId, d.productId))
    }
}

private class BtSerialSession(private val sock: BluetoothSocket, override val summary: String) : DeviceSession {
    override suspend fun write(data: ByteArray, target: String?) = withContext(Dispatchers.IO) { sock.outputStream.write(data); sock.outputStream.flush() }
    override suspend fun read(timeoutMs: Int, maxBytes: Int, target: String?) = withContext(Dispatchers.IO) { readAvailable(sock.inputStream, timeoutMs, maxBytes) }
    override fun close() { runCatching { sock.close() } }
}

internal class TcpSession(addr: InetSocketAddress) : DeviceSession {
    private val sock = Socket().apply { connect(addr, 8000) }
    override val summary = "TCP connected to $addr"
    override suspend fun write(data: ByteArray, target: String?) = withContext(Dispatchers.IO) { sock.getOutputStream().write(data); sock.getOutputStream().flush() }
    override suspend fun read(timeoutMs: Int, maxBytes: Int, target: String?) = withContext(Dispatchers.IO) { readAvailable(sock.getInputStream(), timeoutMs, maxBytes) }
    override fun close() { runCatching { sock.close() } }
}

internal class UdpSession(private val addr: InetSocketAddress) : DeviceSession {
    private val sock = DatagramSocket().apply { broadcast = true }
    override val summary = "UDP socket for $addr (local port ${sock.localPort})"
    override suspend fun write(data: ByteArray, target: String?) = withContext(Dispatchers.IO) { sock.send(DatagramPacket(data, data.size, addr)) }
    override suspend fun read(timeoutMs: Int, maxBytes: Int, target: String?) = withContext(Dispatchers.IO) {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(65_507)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (out.size() < maxBytes) {
            val left = deadline - System.currentTimeMillis(); if (left <= 0) break
            sock.soTimeout = left.toInt().coerceAtLeast(1)
            try { val p = DatagramPacket(buf, buf.size); sock.receive(p); out.write(p.data, 0, p.length) } catch (_: SocketTimeoutException) { break }
        }
        out.toByteArray().let { if (it.size > maxBytes) it.copyOf(maxBytes) else it }
    }
    override fun close() { sock.close() }
}

/** Collects bytes until [timeoutMs] passes, or 300 ms of quiet after data started arriving. */
private fun readAvailable(input: java.io.InputStream, timeoutMs: Int, maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
    val deadline = System.currentTimeMillis() + timeoutMs
    var last = 0L
    while (out.size() < maxBytes && System.currentTimeMillis() < deadline) {
        val avail = input.available()
        if (avail > 0) { val n = input.read(buf, 0, minOf(buf.size, avail, maxBytes - out.size())); if (n < 0) break; out.write(buf, 0, n); last = System.currentTimeMillis() }
        else if (out.size() > 0 && System.currentTimeMillis() - last > 300) break
        else Thread.sleep(20)
    }
    return out.toByteArray()
}

/** BLE GATT client: one operation at a time, notifications buffered until read. */
@SuppressLint("MissingPermission")
private class BleSession private constructor(private val gatt: BluetoothGatt, private val cb: Callback, override val summary: String) : DeviceSession {
    private val lock = Mutex()

    class Callback : BluetoothGattCallback() {
        val connected = CompletableDeferred<Boolean>()
        val discovered = CompletableDeferred<Boolean>()
        @Volatile var op: CompletableDeferred<ByteArray?>? = null
        val notifications = ConcurrentLinkedQueue<ByteArray>()
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) connected.complete(true) else { connected.complete(false); op?.complete(null) }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { discovered.complete(status == BluetoothGatt.GATT_SUCCESS) }
        @Deprecated("API < 33") override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") op?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            op?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { op?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { op?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null) }
        @Deprecated("API < 33") override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") c.value?.let { notifications += it }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { notifications += value }
    }

    companion object {
        suspend fun open(ctx: Context, d: BluetoothDevice): DeviceSession {
            val cb = Callback()
            val g = d.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            try {
                if (withTimeoutOrNull(15_000) { cb.connected.await() } != true) throw IllegalStateException("BLE connection to ${d.address} failed.")
                g.discoverServices()
                if (withTimeoutOrNull(15_000) { cb.discovered.await() } != true) throw IllegalStateException("BLE service discovery failed.")
            } catch (e: Exception) { g.close(); throw e }
            val services = g.services.joinToString("\n") { s ->
                "service ${s.uuid}\n" + s.characteristics.joinToString("\n") { c -> "  char ${c.uuid} [${props(c.properties)}]" }
            }
            return BleSession(g, cb, "BLE connected to ${d.name ?: d.address}\n$services")
        }

        private fun props(p: Int) = listOfNotNull(
            "read".takeIf { p and BluetoothGattCharacteristic.PROPERTY_READ != 0 },
            "write".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 },
            "write-no-response".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 },
            "notify".takeIf { p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 },
            "indicate".takeIf { p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 },
        ).joinToString(", ")
    }

    private fun find(target: String?): BluetoothGattCharacteristic {
        val t = target?.substringAfterLast('/')?.lowercase() ?: throw IllegalArgumentException("BLE needs 'target' = characteristic UUID.")
        return gatt.services.flatMap { it.characteristics }.firstOrNull { it.uuid.toString().lowercase() == t || it.uuid.toString().lowercase().substring(4, 8) == t }
            ?: throw IllegalArgumentException("Characteristic $t not found on this device.")
    }

    private suspend fun <T> op(start: () -> Boolean, what: String): ByteArray {
        val d = CompletableDeferred<ByteArray?>(); cb.op = d
        if (!start()) throw IllegalStateException("BLE $what couldn't start.")
        return withTimeout(10_000) { d.await() } ?: throw IllegalStateException("BLE $what failed.")
    }

    @Suppress("DEPRECATION")
    override suspend fun write(data: ByteArray, target: String?) = lock.withLock {
        val c = find(target)
        val noResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 && c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        c.writeType = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = data
        if (noResponse) { if (!gatt.writeCharacteristic(c)) throw IllegalStateException("BLE write failed.") }
        else op<Unit>({ gatt.writeCharacteristic(c) }, "write")
        Unit
    }

    @Suppress("DEPRECATION")
    override suspend fun read(timeoutMs: Int, maxBytes: Int, target: String?): ByteArray = lock.withLock {
        if (target != null) {
            val c = find(target)
            if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) return@withLock op<Unit>({ gatt.readCharacteristic(c) }, "read").take(maxBytes).toByteArray()
            // Notify/indicate only: subscribe, then collect.
            gatt.setCharacteristicNotification(c, true)
            c.getDescriptor(CCCD)?.let { d ->
                d.value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                op<Unit>({ gatt.writeDescriptor(d) }, "subscribe")
            }
        }
        val out = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (out.size() < maxBytes && System.currentTimeMillis() < deadline) {
            val n = cb.notifications.poll()
            if (n != null) out.write(n) else delay(30)
        }
        out.toByteArray()
    }

    override fun close() { runCatching { gatt.disconnect() }; gatt.close() }
}
