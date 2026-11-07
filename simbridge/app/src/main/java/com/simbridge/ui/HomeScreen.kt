package com.simbridge.ui

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simbridge.network.AppTransport
import com.simbridge.network.BluetoothHelper
import com.simbridge.network.BluetoothTransport
import com.simbridge.network.ConnectionManager
import com.simbridge.network.DEFAULT_PORT
import com.simbridge.network.NsdHelper
import android.net.wifi.p2p.WifiP2pDevice
import com.simbridge.network.WifiDirectHelper

@Composable
fun HomeScreen(
    onHostReady: () -> Unit,
    onClientConnected: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text("SimBridge", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            "Use one phone's SIM on another device — no cloud required",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 28.dp)
        )

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 },
                text = { Text("HOST (has SIM)") })
            Tab(selected = tab == 1, onClick = { tab = 1 },
                text = { Text("CLIENT (remote)") })
        }

        Spacer(Modifier.height(24.dp))

        if (tab == 0) {
            HostPanel(context = context, onReady = onHostReady)
        } else {
            ClientPanel(context = context, onConnected = onClientConnected)
        }
    }
}

// ─────────────────────────── HOST ────────────────────────────────────────────

@Composable
private fun HostPanel(context: Context, onReady: () -> Unit) {
    val localIp = remember { ConnectionManager.localIpAddress() }
    val btHelper = remember { BluetoothHelper(context) }
    var started by remember { mutableStateOf(false) }
    var wdHelper by remember { mutableStateOf<WifiDirectHelper?>(null) }
    var wdGroupCreated by remember { mutableStateOf(false) }
    var wdStatus by remember { mutableStateOf("") }

    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* user responded to discoverability dialog */ }

    Text(
        "The HOST phone owns the physical SIM.  It opens TCP, WiFi Direct and Bluetooth " +
        "servers simultaneously — the CLIENT can connect via any method.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(20.dp))

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Wifi, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text("WiFi IP address", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(localIp, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("Port: $DEFAULT_PORT", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "⚠ You will be prompted to set SimBridge as the default Phone app — required for call interception.",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.error
    )

    Spacer(Modifier.height(20.dp))

    if (!started) {
        Button(
            onClick = {
                started = true
                context.getSharedPreferences("simbridge", Context.MODE_PRIVATE)
                    .edit().putBoolean("is_host", true).apply()
                onReady()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Start HOST mode") }
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text("TCP + Bluetooth servers running. Waiting for CLIENT…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // ---- WiFi Direct group (optional) ----
        Text("WiFi Direct", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        if (!wdGroupCreated) {
            OutlinedButton(
                onClick = {
                    if (wdHelper == null) {
                        wdHelper = WifiDirectHelper(context).also { it.register() }
                    }
                    wdHelper?.createGroup(
                        onSuccess = { wdGroupCreated = true; wdStatus = "Group active — CLIENT connects to 192.168.49.1" },
                        onFailure = { wdStatus = "Failed to create group (code $it)" }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Router, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Create WiFi Direct Group")
            }
        } else {
            Surface(shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()) {
                Text("WiFi Direct active  —  IP: 192.168.49.1",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        if (wdStatus.isNotEmpty()) {
            Text(wdStatus, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(12.dp))

        // ---- Bluetooth discoverability ----
        Text("Bluetooth", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        if (btHelper.isAvailable()) {
            Text("Device name: ${btHelper.deviceName()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { discoverableLauncher.launch(btHelper.makeDiscoverableIntent(300)) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Make Discoverable (5 min)")
            }
        } else {
            Text("Bluetooth not available on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─────────────────────────── CLIENT ──────────────────────────────────────────

@Composable
private fun ClientPanel(context: Context, onConnected: () -> Unit) {
    var connectTab by remember { mutableIntStateOf(0) }

    TabRow(selectedTabIndex = connectTab, modifier = Modifier.fillMaxWidth()) {
        Tab(selected = connectTab == 0, onClick = { connectTab = 0 },
            text = { Text("WiFi") }, icon = { Icon(Icons.Default.Wifi, null, Modifier.size(16.dp)) })
        Tab(selected = connectTab == 1, onClick = { connectTab = 1 },
            text = { Text("Direct") }, icon = { Icon(Icons.Default.Router, null, Modifier.size(16.dp)) })
        Tab(selected = connectTab == 2, onClick = { connectTab = 2 },
            text = { Text("Bluetooth") }, icon = { Icon(Icons.Default.Bluetooth, null, Modifier.size(16.dp)) })
    }

    Spacer(Modifier.height(16.dp))

    when (connectTab) {
        0 -> AutoDiscoverPanel(context = context, onConnected = onConnected)
        1 -> WifiDirectPanel(context = context, onConnected = onConnected)
        2 -> BluetoothPanel(context = context, onConnected = onConnected)
    }
}

// ---- Tab 0: NSD auto-discover ------------------------------------------------

@Composable
private fun AutoDiscoverPanel(context: Context, onConnected: () -> Unit) {
    var discovered by remember { mutableStateOf<List<NsdHelper.HostInfo>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }
    var nsdHelper by remember { mutableStateOf<NsdHelper?>(null) }

    DisposableEffect(Unit) {
        onDispose { nsdHelper?.stopDiscovery() }
    }

    Text(
        "Both phones must be on the same WiFi router.  Tap Scan to find the HOST automatically.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    Button(
        onClick = {
            scanning = true
            statusMsg = "Scanning…"
            discovered = emptyList()
            val helper = NsdHelper(context).also { nsdHelper = it }
            helper.discoverHosts(
                onFound = { info ->
                    discovered = (discovered + info).distinctBy { it.host }
                    statusMsg = "Found ${discovered.size} host(s)"
                },
                onLost = { name -> discovered = discovered.filterNot { it.name == name } }
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Scan for HOST devices")
    }

    if (scanning && discovered.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(statusMsg, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (discovered.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("Tap a device to connect:", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(discovered) { host ->
                DeviceRow(icon = Icons.Default.PhoneAndroid, name = host.name,
                    detail = "${host.host}:${host.port}") {
                    nsdHelper?.stopDiscovery()
                    statusMsg = "Connecting…"
                    connectViaTcp(context, host.host, host.port, onConnected) { err ->
                        statusMsg = "Failed: $err"
                        scanning = false
                    }
                }
            }
        }
    }

    if (statusMsg.startsWith("Failed")) {
        Spacer(Modifier.height(8.dp))
        Text(statusMsg, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
}

// ---- Tab 1: WiFi Direct ------------------------------------------------------

@Composable
private fun WifiDirectPanel(context: Context, onConnected: () -> Unit) {
    var peers by remember { mutableStateOf<List<WifiP2pDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }
    var manualIp by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    val helper = remember { WifiDirectHelper(context) }

    DisposableEffect(Unit) {
        helper.register()
        helper.onPeersChanged = { list -> peers = list }
        helper.onConnectionInfoAvailable = { info ->
            val ip = if (info.isGroupOwner) "192.168.49.1"
                     else info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
            statusMsg = "WiFi Direct connected — joining $ip…"
            connecting = true
            connectViaTcp(context, ip, DEFAULT_PORT, onConnected) { err ->
                connecting = false
                statusMsg = "Failed: $err"
            }
        }
        onDispose { helper.unregister() }
    }

    Text(
        "Scan for nearby WiFi Direct devices. No WiFi router needed — the phones " +
        "create a direct link between themselves.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    Button(
        onClick = {
            scanning = true
            statusMsg = "Scanning for WiFi Direct peers…"
            peers = emptyList()
            helper.discoverPeers(
                onSuccess = { statusMsg = "Discovery started…" },
                onFailure = { scanning = false; statusMsg = "Discovery failed (code $it)" }
            )
        },
        enabled = !connecting,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Scan for WiFi Direct devices")
    }

    if (scanning && peers.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(statusMsg, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (peers.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("Tap a device to connect:", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(peers) { device ->
                @Suppress("MissingPermission")
                DeviceRow(icon = Icons.Default.Router,
                    name = device.deviceName.ifBlank { "Unknown device" },
                    detail = device.deviceAddress) {
                    statusMsg = "Connecting via WiFi Direct…"
                    helper.connectToPeer(device,
                        onSuccess = { statusMsg = "Connected — waiting for IP assignment…" },
                        onFailure = { statusMsg = "Connect failed (code $it)" }
                    )
                }
            }
        }
    }

    if (statusMsg.isNotEmpty() && !connecting) {
        Spacer(Modifier.height(8.dp))
        Text(statusMsg,
            color = if (statusMsg.startsWith("Failed")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }

    if (connecting) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(statusMsg, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))

    TextButton(onClick = { showManual = !showManual }, modifier = Modifier.fillMaxWidth()) {
        Text(if (showManual) "Hide manual IP entry" else "Enter IP manually instead")
    }

    if (showManual) {
        OutlinedTextField(
            value = manualIp,
            onValueChange = { manualIp = it },
            label = { Text("HOST IP (e.g. 192.168.49.1)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (manualIp.isNotBlank()) {
                    connecting = true
                    connectViaTcp(context, manualIp.trim(), DEFAULT_PORT, onConnected) { err ->
                        connecting = false
                        statusMsg = "Failed: $err"
                    }
                }
            },
            enabled = !connecting,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Connect") }
    }
}

// ---- Tab 2: Bluetooth --------------------------------------------------------

@Composable
private fun BluetoothPanel(context: Context, onConnected: () -> Unit) {
    val helper = remember { BluetoothHelper(context) }
    var bonded by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var discovered by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }
    var discoveryReceiver by remember { mutableStateOf<BroadcastReceiver?>(null) }

    LaunchedEffect(Unit) {
        bonded = helper.getBondedDevices()
    }

    DisposableEffect(Unit) {
        onDispose {
            discoveryReceiver?.let { helper.stopDiscovery(it) }
        }
    }

    if (!helper.isAvailable()) {
        Text("Bluetooth is not available on this device.",
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center)
        return
    }

    Text(
        "Connect to the HOST phone over Bluetooth Classic. The HOST must be " +
        "discoverable (tap 'Make Discoverable' on the HOST tab). Audio is streamed " +
        "directly over the Bluetooth link — no WiFi needed.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    if (bonded.isNotEmpty()) {
        Text("Paired devices (tap to connect):", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
            items(bonded) { device ->
                @Suppress("MissingPermission")
                DeviceRow(icon = Icons.Default.Bluetooth,
                    name = device.name ?: "Unknown",
                    detail = device.address) {
                    if (!connecting) {
                        connecting = true
                        statusMsg = "Connecting via Bluetooth…"
                        context.getSharedPreferences("simbridge", Context.MODE_PRIVATE)
                            .edit().putBoolean("is_host", false).apply()
                        AppTransport.use(BluetoothTransport)
                        BluetoothTransport.connect(device,
                            onConnected = { onConnected() },
                            onError = { err ->
                                connecting = false
                                statusMsg = "Failed: $err"
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    Button(
        onClick = {
            scanning = true
            statusMsg = "Scanning for nearby Bluetooth devices…"
            discovered = emptyList()
            val rx = helper.startDiscovery { device ->
                discovered = (discovered + device).distinctBy { it.address }
            }
            discoveryReceiver = rx
        },
        enabled = !connecting,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Scan for new devices")
    }

    if (scanning && discovered.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(statusMsg, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (discovered.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Nearby devices:", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
            items(discovered) { device ->
                @Suppress("MissingPermission")
                DeviceRow(icon = Icons.Default.Bluetooth,
                    name = device.name ?: "Unknown",
                    detail = device.address) {
                    if (!connecting) {
                        connecting = true
                        discoveryReceiver?.let { helper.stopDiscovery(it) }
                        statusMsg = "Connecting via Bluetooth…"
                        context.getSharedPreferences("simbridge", Context.MODE_PRIVATE)
                            .edit().putBoolean("is_host", false).apply()
                        AppTransport.use(BluetoothTransport)
                        BluetoothTransport.connect(device,
                            onConnected = { onConnected() },
                            onError = { err ->
                                connecting = false
                                statusMsg = "Failed: $err"
                            }
                        )
                    }
                }
            }
        }
    }

    if (statusMsg.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(statusMsg,
            color = if (statusMsg.startsWith("Failed")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }

    if (connecting) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

// ─────────────────────────── shared helpers ───────────────────────────────────

@Composable
private fun DeviceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    detail: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Connect via TCP transport and register it as the active transport. */
private fun connectViaTcp(
    context: Context,
    host: String,
    port: Int,
    onConnected: () -> Unit,
    onError: (String) -> Unit
) {
    context.getSharedPreferences("simbridge", Context.MODE_PRIVATE)
        .edit().putBoolean("is_host", false).apply()
    AppTransport.use(ConnectionManager)
    ConnectionManager.connectToServer(host = host, port = port,
        onConnected = onConnected, onError = onError)
}
