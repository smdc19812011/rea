package com.kvmcontrol.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kvmcontrol.app.data.ConnectionState
import com.kvmcontrol.app.data.KvmStats
import com.kvmcontrol.app.ui.theme.KvmConnected
import com.kvmcontrol.app.ui.theme.KvmConnecting
import com.kvmcontrol.app.ui.theme.KvmDisconnected
import com.kvmcontrol.app.ui.theme.KvmGreen
import com.kvmcontrol.app.ui.viewmodel.MainViewModel
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun HomeScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Android KVM",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = KvmGreen,
        )

        ConnectionStatusCard(uiState.connectionState, uiState.stats)

        if (uiState.connectionState == ConnectionState.CONNECTED) {
            StatsGrid(uiState.stats)
        }

        Spacer(Modifier.height(8.dp))

        Text("Capabilities", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

        CapabilityRow(
            icon = Icons.Default.Bluetooth,
            label = "Bluetooth HID",
            available = uiState.isBluetoothAvailable,
        )
        CapabilityRow(
            icon = Icons.Default.Usb,
            label = "USB Bridge (CH9329, no root)",
            available = uiState.isUsbBridgeAvailable,
        )
        CapabilityRow(
            icon = Icons.Default.Usb,
            label = "USB HID (root required)",
            available = uiState.isUsbHidAvailable,
        )
        CapabilityRow(
            icon = Icons.Default.Computer,
            label = "Network KVM (VNC)",
            available = true,
        )
    }
}

@Composable
private fun ConnectionStatusCard(state: ConnectionState, stats: KvmStats) {
    val dotColor = when (state) {
        ConnectionState.CONNECTED -> KvmConnected
        ConnectionState.CONNECTING -> KvmConnecting
        else -> KvmDisconnected
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Column {
                Text(
                    text = when (state) {
                        ConnectionState.CONNECTED -> "Connected${if (stats.deviceName.isNotEmpty()) " to ${stats.deviceName}" else ""}"
                        ConnectionState.CONNECTING -> "Connecting…"
                        ConnectionState.ERROR -> "Connection error"
                        ConnectionState.DISCONNECTED -> "Disconnected"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state == ConnectionState.CONNECTED && stats.latencyMs >= 0) {
                    Text(
                        "Latency: ${stats.latencyMs} ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsGrid(stats: KvmStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatCard("CPU", "%.1f%%".format(stats.cpuPercent), Modifier.weight(1f))
        StatCard("RAM", "%.0f%%".format(stats.ramPercent), Modifier.weight(1f))
        StatCard("↓ Net", "%.0f KB/s".format(stats.networkRxKbps), Modifier.weight(1f))
        StatCard("↑ Net", "%.0f KB/s".format(stats.networkTxKbps), Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = KvmGreen)
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun CapabilityRow(icon: ImageVector, label: String, available: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (available) KvmGreen else KvmDisconnected,
            modifier = Modifier.size(20.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (available) MaterialTheme.colorScheme.onSurface
                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (available) "Available" else "Unavailable",
            style = MaterialTheme.typography.labelSmall,
            color = if (available) KvmGreen else KvmDisconnected,
        )
    }
}
