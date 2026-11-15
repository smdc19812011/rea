package app.switchboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.switchboard.data.ChatMessage
import app.switchboard.data.Role
import app.switchboard.data.ToolCall
import app.switchboard.net.Runner
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.remember
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onMenu: () -> Unit, onSettings: () -> Unit, onPickModel: () -> Unit) {
    val provider = vm.activeProvider
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    var askedNotif by rememberSaveable { mutableStateOf(false) }
    val send: (String) -> Unit = { text ->
        if (android.os.Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askedNotif = true; notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.send(text)
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Chats") } },
                title = {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable(enabled = provider != null, onClick = onPickModel)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .semantics { contentDescription = "Switch provider or model" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 220.dp)) {
                            Text(provider?.model?.ifBlank { "No model set" } ?: "Switchboard",
                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (provider != null) Text(provider.name + if (vm.settings.unrestricted) " · Unrestricted" else "",
                                style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                color = if (vm.settings.unrestricted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (provider != null) Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                    }
                },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            val msgs = vm.current.messages
            Box(Modifier.weight(1f)) {
                when {
                    provider == null -> EmptyState(
                        "Connect a model to start",
                        "Add Anthropic, OpenRouter, OpenAI, a local Ollama server, or any OpenAI-compatible endpoint.",
                        "Add a provider", onSettings,
                    )
                    msgs.isEmpty() -> EmptyState("What's on your mind?", "Replies come from ${provider.name}. Tap the model name above to switch at any time.", null, null)
                    else -> MessageList(msgs, vm.streaming, vm.pendingApproval, onDecide = vm::decide, onRegenerate = vm::regenerate)
                }
            }
            vm.toolStatus?.let {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Composer(enabled = provider != null, streaming = vm.streaming, onSend = send, onStop = vm::stop)
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, action: String?, onAction: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(action); Spacer(Modifier.size(6.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun MessageList(
    msgs: List<ChatMessage>, streaming: Boolean, pending: ToolCall?,
    onDecide: (ChatViewModel.Approval) -> Unit, onRegenerate: () -> Unit,
) {
    val state = rememberLazyListState()
    val visible = msgs.filter { it.role != Role.TOOL } // tool output is shown inside the call's card
    val results = msgs.filter { it.role == Role.TOOL }.associateBy { it.toolCallId }
    val lastLen = msgs.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(msgs.size, lastLen, pending) { if (visible.isNotEmpty()) state.scrollToItem(visible.lastIndex, Int.MAX_VALUE) }
    LazyColumn(state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(visible, key = { it.id }) { m ->
            val isLast = m.id == visible.last().id
            if (m.role == Role.USER) UserBubble(m)
            else AssistantBlock(m, isLast, streaming && isLast, streaming, results, pending, onDecide, onRegenerate)
        }
    }
}

@Composable
private fun UserBubble(m: ChatMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.widthIn(max = 320.dp)) {
            SelectionContainer { Text(m.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun AssistantBlock(
    m: ChatMessage, isLast: Boolean, live: Boolean, busy: Boolean,
    results: Map<String?, ChatMessage>, pending: ToolCall?,
    onDecide: (ChatViewModel.Approval) -> Unit, onRegenerate: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (m.text.isEmpty() && m.toolCalls.isEmpty() && live) TypingDots()
        else if (m.text.isNotEmpty()) MarkdownText(m.text, if (m.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        m.toolCalls.forEach { c ->
            ToolCard(c, results[c.id], awaiting = pending?.id == c.id, running = busy && results[c.id] == null && pending?.id != c.id, onDecide)
        }
        if (!live && m.toolCalls.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            m.source?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(m.text)) }) { Icon(Icons.Default.ContentCopy, "Copy reply", Modifier.size(18.dp)) }
            if (isLast) IconButton(onClick = onRegenerate) { Icon(Icons.Default.Refresh, "Regenerate with current model", Modifier.size(20.dp)) }
        }
    }
}

private fun toolTitle(name: String) = when (name) {
    "run_command" -> "Run command"
    "write_file" -> "Write file"
    "read_file" -> "Read file"
    Runner.INSTALL_APK -> "Install APK on this phone"
    Runner.SEND_FILE -> "Send file"
    else -> name
}

@Composable
private fun ToolCard(call: ToolCall, result: ChatMessage?, awaiting: Boolean, running: Boolean, onDecide: (ChatViewModel.Approval) -> Unit) {
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    val outline = if (awaiting) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, outline, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).animateContentSize()
            .clickable(enabled = result != null) { expanded = !expanded }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Build, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            Text(toolTitle(call.name), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            when {
                running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                result != null -> Text(if (result.isError) "failed" else "done", style = MaterialTheme.typography.labelSmall,
                    color = if (result.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }
        Text(Runner.describe(call), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            maxLines = if (expanded || awaiting) 12 else 2, overflow = TextOverflow.Ellipsis)
        if (awaiting) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onDecide(ChatViewModel.Approval.ONCE) }) { Text("Approve") }
            OutlinedButton(onClick = { onDecide(ChatViewModel.Approval.ALWAYS) }) { Text("Always") }
            TextButton(onClick = { onDecide(ChatViewModel.Approval.DENY) }) { Text("Deny") }
        }
        result?.attachments?.forEach { AttachmentCard(it) }
        if (result != null && expanded) SelectionContainer {
            Text(result.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp))
        }
        if (result != null && !expanded) Text("Tap to show output", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TypingDots() {
    Row(Modifier.padding(vertical = 8.dp).semantics { contentDescription = "Waiting for reply" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.secondary, CircleShape)) }
    }
}

@Composable
private fun Composer(enabled: Boolean, streaming: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            TextField(
                value = text, onValueChange = { text = it }, enabled = enabled,
                placeholder = { Text("Message") }, maxLines = 6,
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            if (streaming) {
                FilledIconButton(onClick = onStop, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Stop, "Stop generating") }
            } else {
                FilledIconButton(
                    onClick = { onSend(text); text = "" }, enabled = enabled && text.isNotBlank(), modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Icon(Icons.Default.ArrowUpward, "Send") }
            }
        }
    }
}


private fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** A file the model sent: open it with another app (an APK opens the installer), save a copy, or share it. */
@Composable
private fun AttachmentCard(a: app.switchboard.data.Attachment) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val file = remember(a.path) { java.io.File(a.path) }
    var note by remember(a.path) { mutableStateOf<String?>(null) }
    fun uri() = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(a.mime)) { dest ->
        if (dest != null) note = runCatching {
            ctx.contentResolver.openOutputStream(dest)!!.use { out -> file.inputStream().use { it.copyTo(out) } }; "Saved"
        }.getOrElse { "Couldn't save: ${it.message}" }
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.material.icons.Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(humanSize(a.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!file.exists()) {
                Text("This file is no longer on the phone.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    note = runCatching {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri(), a.mime)
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)); null
                    }.getOrElse { "No app on this phone opens this type of file. Use Save or Share." }
                }) { Text(if (a.mime == "application/vnd.android.package-archive") "Install" else "Open") }
                TextButton(onClick = { save.launch(a.name) }) { Text("Save") }
                TextButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(a.mime)
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri()).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(android.content.Intent.createChooser(send, a.name))
                }) { Text("Share") }
            }
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
