package com.simbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simbridge.client.ClientViewModel
import com.simbridge.data.CallState

@Composable
fun InCallScreen(
    isHost: Boolean,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val clientVm: ClientViewModel = viewModel()

    LaunchedEffect(Unit) {
        if (!isHost) clientVm.startListening(context)
    }

    val callState by clientVm.callState.collectAsState()
    val remoteNumber by clientVm.remoteNumber.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))
            StatusBadge(isHost)
            Spacer(Modifier.height(32.dp))

            if (isHost) {
                HostReadyPanel(onDisconnect = onDisconnect)
            } else {
                when (callState) {
                    CallState.IDLE -> {
                        DialerPanel(onDial = { number -> clientVm.makeCall(number) })
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDisconnect) { Text("Disconnect session") }
                    }
                    CallState.RINGING_INCOMING -> IncomingCallPanel(
                        number = remoteNumber,
                        onAnswer = { clientVm.answerIncoming() },
                        onReject = { clientVm.rejectIncoming() }
                    )
                    CallState.RINGING_OUTGOING -> CallingPanel(number = remoteNumber)
                    CallState.ACTIVE -> ActiveCallPanel(
                        number = remoteNumber,
                        isMuted = clientVm.muted,
                        onMuteToggle = { clientVm.toggleMute() },
                        onHangUp = { clientVm.hangUp() }
                    )
                    CallState.ENDED -> Text("Call ended", fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(isHost: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (isHost) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = if (isHost) "HOST — SIM active" else "CLIENT — remote SIM",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (isHost) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun HostReadyPanel(onDisconnect: () -> Unit) {
    Icon(Icons.Default.PhoneInTalk, contentDescription = null,
        modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(16.dp))
    Text("Listening for calls", fontSize = 20.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Text(
        "Incoming calls to this SIM are relayed to the CLIENT.\n" +
        "The CLIENT can also dial out through this SIM.",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.weight(1f))
    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
        Text("Stop HOST mode")
    }
}

@Composable
private fun DialerPanel(onDial: (String) -> Unit) {
    var digits by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = digits.ifBlank { "Enter number" },
            fontSize = 32.sp, fontWeight = FontWeight.Light,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        listOf("1 2 3", "4 5 6", "7 8 9", "* 0 #").forEach { row ->
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                row.split(" ").forEach { key ->
                    DialKey(key) { digits += key }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { if (digits.isNotEmpty()) digits = digits.dropLast(1) }) {
                Icon(Icons.Default.Backspace, contentDescription = "Delete")
            }
            IconButton(
                onClick = { if (digits.isNotEmpty()) onDial(digits) },
                modifier = Modifier.background(Color(0xFF4CAF50), CircleShape).size(56.dp)
            ) {
                Icon(Icons.Default.Phone, contentDescription = "Call", tint = Color.White)
            }
        }
    }
}

@Composable
private fun DialKey(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, shape = CircleShape,
        modifier = Modifier.size(72.dp), contentPadding = PaddingValues(0.dp)
    ) { Text(label, fontSize = 22.sp) }
}

@Composable
private fun IncomingCallPanel(number: String, onAnswer: () -> Unit, onReject: () -> Unit) {
    Spacer(Modifier.height(32.dp))
    Icon(Icons.Default.Phone, contentDescription = null,
        modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(16.dp))
    Text("Incoming call", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(number.ifBlank { "Unknown" }, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(48.dp))
    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onReject,
            modifier = Modifier.background(Color(0xFFF44336), CircleShape).size(72.dp)) {
            Icon(Icons.Default.CallEnd, contentDescription = "Reject",
                tint = Color.White, modifier = Modifier.size(32.dp))
        }
        IconButton(onClick = onAnswer,
            modifier = Modifier.background(Color(0xFF4CAF50), CircleShape).size(72.dp)) {
            Icon(Icons.Default.Phone, contentDescription = "Answer",
                tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun CallingPanel(number: String) {
    Spacer(Modifier.height(32.dp))
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Text("Calling via HOST SIM…", style = MaterialTheme.typography.bodyLarge)
    Text(number, fontSize = 24.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun ActiveCallPanel(number: String, isMuted: Boolean, onMuteToggle: () -> Unit, onHangUp: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(number.ifBlank { "Unknown" }, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Text("Call active", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(48.dp))
    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
        IconButton(
            onClick = onMuteToggle,
            modifier = Modifier.background(
                if (isMuted) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.surfaceVariant, CircleShape
            ).size(64.dp)
        ) {
            Icon(
                if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = if (isMuted) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onHangUp,
            modifier = Modifier.background(Color(0xFFF44336), CircleShape).size(72.dp)) {
            Icon(Icons.Default.CallEnd, contentDescription = "Hang up",
                tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}
