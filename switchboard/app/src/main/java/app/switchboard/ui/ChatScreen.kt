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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.remember
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onMenu: () -> Unit, onSettings: () -> Unit, onPickModel: () -> Unit) {
    val provider = vm.activeProvider
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    var askedNotif by rememberSaveable { mutableStateOf(false) }
    val send: (String, Boolean) -> Unit = { text, asImage ->
        if (android.os.Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askedNotif = true; notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.send(text, asImage)
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
                            if (provider != null) Text((vm.currentAgent?.let { "${it.name} · " } ?: "") + provider.name + if (vm.settings.unrestricted) " · Unrestricted" else "",
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
            vm.pendingApproval?.takeIf { p -> vm.current.messages.none { m -> m.toolCalls.any { it.id == p.id } } }?.let { p ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Approve: ${toolTitle(p.name)}", style = MaterialTheme.typography.labelLarge)
                        Text(Runner.describe(p), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.decide(ChatViewModel.Approval.ONCE) }) { Text("Approve") }
                            OutlinedButton(onClick = { vm.decide(ChatViewModel.Approval.ALWAYS) }) { Text("Always") }
                            TextButton(onClick = { vm.decide(ChatViewModel.Approval.DENY) }) { Text("Deny") }
                        }
                    }
                }
            }
            vm.toolStatus?.let {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Remaining requests for the model in use (its own limits), refreshed every second; hidden when it has no limit.
            val st = vm.settings
            val chatProvider = vm.providerFor(vm.currentAgent)
            var info by remember { mutableStateOf(chatProvider?.let { vm.usageFor(it) }) }
            LaunchedEffect(chatProvider, st.dailyReset, st.timeZone) { while (true) { info = chatProvider?.let { vm.usageFor(it) }; kotlinx.coroutines.delay(1000) } }
            val lim = info?.first; val used = info?.second
            val minLeft = if (lim != null && used != null) (lim.perMinute - used.lastMinute).coerceAtLeast(0) else 0
            val dayLeft = if (lim != null && used != null) (lim.perDay - used.today).coerceAtLeast(0) else 0
            val parts = if (lim == null) emptyList() else listOfNotNull(
                "$minLeft/${lim.perMinute} remaining min".takeIf { lim.perMinute > 0 },
                "$dayLeft/${lim.perDay} remaining 24hr".takeIf { lim.perDay > 0 },
            )
            // With several providers taking turns, the counter is for the one answering next.
            val footer = parts.takeIf { it.isNotEmpty() }?.joinToString("   ")?.let { if (vm.rotation.size > 1 && chatProvider != null) "${chatProvider.name}:  $it" else it }
            val exhausted = lim != null && ((lim.perMinute > 0 && minLeft == 0) || (lim.perDay > 0 && dayLeft == 0))
            Composer(enabled = provider != null, streaming = vm.streaming, onSend = send, onStop = vm::stop, footer = footer, footerWarn = exhausted,
                pending = vm.pendingAttachments, attaching = vm.attaching, onAttach = vm::addAttachments, onRemove = vm::removePending)
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
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        m.attachments.forEach { a -> Box(Modifier.widthIn(max = 320.dp)) { AttachmentCard(a) } }
        if (m.text.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
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
        if (m.thinking.isNotBlank()) ThinkingBlock(m.thinking, live = live && m.text.isEmpty() && m.toolCalls.isEmpty())
        if (m.text.isEmpty() && m.toolCalls.isEmpty() && live && m.thinking.isBlank()) TypingDots()
        else if (m.text.isNotEmpty()) MarkdownText(m.text, if (m.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        m.attachments.forEach { AttachmentCard(it) } // images etc. the model produced itself
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
    ChatViewModel.BUILD_APK -> "Build Android app"
    "create_skill" -> "Save a skill"
    "create_agent" -> "Create an agent"
    "schedule_task" -> "Schedule a task"
    "run_agent" -> "Ask an agent"
    "use_skill" -> "Load a skill"
    "generate_image" -> "Generate image"
    "create_document" -> "Create file"
    "text_to_speech" -> "Create audio"
    else -> if (name.startsWith("mcp__") || name.startsWith("api__")) "Connector: " + name.substringAfter("__").replace("__", " › ") else name
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
private fun Composer(enabled: Boolean, streaming: Boolean, onSend: (String, Boolean) -> Unit, onStop: () -> Unit, footer: String? = null, footerWarn: Boolean = false,
                     pending: List<app.switchboard.data.Attachment> = emptyList(), attaching: Boolean = false,
                     onAttach: (List<android.net.Uri>) -> Unit = {}, onRemove: (app.switchboard.data.Attachment) -> Unit = {}) {
    var text by rememberSaveable { mutableStateOf("") }
    // Any file type, several at once.
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { onAttach(it) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
      Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        if (pending.isNotEmpty() || attaching) androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(pending, key = { it.path }) { a ->
                androidx.compose.material3.InputChip(selected = false, onClick = {},
                    label = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                    leadingIcon = { Icon(if (a.mime.startsWith("image/")) Icons.Default.Image else Icons.Default.Description, null, Modifier.size(16.dp)) },
                    trailingIcon = { Icon(Icons.Default.Close, "Remove ${a.name}", Modifier.size(16.dp).clickable { onRemove(a) }) })
            }
            if (attaching) item { CircularProgressIndicator(Modifier.padding(8.dp).size(18.dp), strokeWidth = 2.dp) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (footer != null) 4.dp else 12.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = enabled && !streaming, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Add, "Attach files")
            }
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
                    onClick = { onSend(text, false); text = "" }, enabled = enabled && !attaching && (text.isNotBlank() || pending.isNotEmpty()), modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Icon(Icons.Default.ArrowUpward, "Send") }
            }
        }
        footer?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = if (footerWarn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 8.dp)
                    .semantics { contentDescription = "Requests remaining: $it" })
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
    // Decoded off the main thread and downsampled to ~1200 px so big images don't use much memory.
    val preview by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, a.path) {
        if (a.mime.startsWith("image/") && a.mime != "image/svg+xml") value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(a.path, bounds)
                var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
                android.graphics.BitmapFactory.decodeFile(a.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.let {
                    it.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            preview?.let { img ->
                androidx.compose.foundation.Image(img, contentDescription = a.name,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).padding(bottom = 8.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
            }
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


/** The model's reasoning: a live preview while it thinks, then a collapsed "Thought process" you can open. */
@Composable
private fun ThinkingBlock(text: String, live: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !live) { open = !open }
        .background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp) else Icon(if (open) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp))
            Spacer(Modifier.size(6.dp))
            Text(if (live) "Thinking… (${text.split(Regex("\\s+")).size} words)" else "Thought process", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (live || open) Text(if (live) text.takeLast(400) else text, style = MaterialTheme.typography.bodySmall,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (live) 4 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}
