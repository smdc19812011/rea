package com.kvmcontrol.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kvmcontrol.app.data.ConnectionState
import com.kvmcontrol.app.data.KeyEvent
import com.kvmcontrol.app.data.MouseEvent
import com.kvmcontrol.app.ui.theme.KvmGreen
import com.kvmcontrol.app.ui.viewmodel.MainViewModel
import kotlin.math.roundToInt

// HID keyboard modifiers
private const val MOD_CTRL: Byte = 0x01
private const val MOD_SHIFT: Byte = 0x02
private const val MOD_ALT: Byte = 0x04
private const val MOD_GUI: Byte = 0x08

// HID keycodes
private const val KEY_A: Byte = 0x04
private const val KEY_C: Byte = 0x06
private const val KEY_V: Byte = 0x19
private const val KEY_X: Byte = 0x1B
private const val KEY_Z: Byte = 0x1D
private const val KEY_ENTER: Byte = 0x28
private const val KEY_ESC: Byte = 0x29
private const val KEY_TAB: Byte = 0x2B
private const val KEY_DELETE: Byte = 0x4C
private const val KEY_F4: Byte = 0x3D

@Composable
fun ControlScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val isConnected = uiState.connectionState == ConnectionState.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!isConnected) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Connect to a device first",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            return@Column
        }

        Text("Control", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // Touchpad
        TouchpadArea(
            onMove = { dx, dy ->
                viewModel.sendMouse(MouseEvent(dx = clamp(dx), dy = clamp(dy)))
            },
            onLeftClick = { viewModel.sendMouse(MouseEvent(buttons = 1)) },
            onRightClick = { viewModel.sendMouse(MouseEvent(buttons = 2)) },
        )

        // Keyboard shortcuts
        QuickActionsRow(onKey = viewModel::sendKey)

        // Text injection
        TextInjectionBar(onSend = { text ->
            for (char in text) {
                val code = charToHidCode(char) ?: continue
                viewModel.sendKey(KeyEvent(modifier = code.second, keys = listOf(code.first)))
            }
        })
    }
}

@Composable
private fun TouchpadArea(
    onMove: (dx: Float, dy: Float) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
) {
    var accX by remember { mutableFloatStateOf(0f) }
    var accY by remember { mutableFloatStateOf(0f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        accX += dragAmount.x * 0.5f
                        accY += dragAmount.y * 0.5f
                        if (accX != 0f || accY != 0f) {
                            onMove(accX, accY)
                            accX = 0f
                            accY = 0f
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onLeftClick() })
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Mouse, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(32.dp))
                Text("Touchpad — drag to move, tap to click",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onLeftClick,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = KvmGreen),
        ) { Text("Left Click") }
        Button(
            onClick = onRightClick,
            modifier = Modifier.weight(1f),
        ) { Text("Right Click") }
    }
}

@Composable
private fun QuickActionsRow(onKey: (KeyEvent) -> Unit) {
    Text("Shortcuts", style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            "Ctrl+C" to KeyEvent(modifier = MOD_CTRL, keys = listOf(KEY_C)),
            "Ctrl+V" to KeyEvent(modifier = MOD_CTRL, keys = listOf(KEY_V)),
            "Ctrl+Z" to KeyEvent(modifier = MOD_CTRL, keys = listOf(KEY_Z)),
            "Ctrl+Alt+Del" to KeyEvent(modifier = (MOD_CTRL.toInt() or MOD_ALT.toInt()).toByte(), keys = listOf(KEY_DELETE)),
            "Esc" to KeyEvent(keys = listOf(KEY_ESC)),
            "Tab" to KeyEvent(keys = listOf(KEY_TAB)),
            "Enter" to KeyEvent(keys = listOf(KEY_ENTER)),
        ).forEach { (label, event) ->
            Button(
                onClick = { onKey(event) },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            ) { Text(label, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextInjectionBar(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Type & send text") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        Button(
            onClick = { onSend(text); text = "" },
            enabled = text.isNotBlank(),
        ) { Text("Send") }
    }
}

private fun clamp(v: Float): Byte = v.roundToInt().coerceIn(-127, 127).toByte()

private fun charToHidCode(char: Char): Pair<Byte, Byte>? {
    // Basic ASCII → HID keycode mapping (a-z, 0-9, common punctuation)
    return when (char.lowercaseChar()) {
        in 'a'..'z' -> Pair((0x04 + (char.lowercaseChar() - 'a')).toByte(),
            if (char.isUpperCase()) MOD_SHIFT else 0)
        in '0'..'9' -> Pair(
            (if (char == '0') 0x27 else 0x1E + (char - '1')).toByte(), 0)
        ' ' -> Pair(0x2C.toByte(), 0)
        '\n' -> Pair(KEY_ENTER, 0)
        '\t' -> Pair(KEY_TAB, 0)
        '.' -> Pair(0x37.toByte(), 0)
        ',' -> Pair(0x36.toByte(), 0)
        '-' -> Pair(0x2D.toByte(), 0)
        '=' -> Pair(0x2E.toByte(), 0)
        '/' -> Pair(0x38.toByte(), 0)
        else -> null
    }
}
