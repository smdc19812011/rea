package app.switchboard.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.switchboard.data.Conversation
import app.switchboard.data.Role as MsgRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "now", "5m", "8h", "3d", or "20 Oct" for older chats. */
fun ago(ms: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val d = (now - ms).coerceAtLeast(0) / 1000
    return when {
        d < 60 -> "now"
        d < 3600 -> "${d / 60}m"
        d < 86_400 -> "${d / 3600}h"
        d < 7 * 86_400 -> "${d / 86_400}d"
        else -> Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM"))
    }
}

/**
 * The chat list: search, projects (expandable, with "new chat here"), chats with when they were last active and what
 * answered, and a New chat button. Used as the side pane on wide screens and inside the drawer on phones.
 */
@Composable
fun ChatListPanel(
    vm: ChatViewModel, onPick: (String) -> Unit, onNew: (String?) -> Unit, onRename: (Conversation) -> Unit,
    onManageProjects: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier,
) {
    val projects = vm.settings.projects
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    fun matches(c: Conversation) = q.isEmpty() || c.title.contains(q, true) || c.messages.any { it.text.contains(q, true) }

    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(if (searching) Icons.Default.Close else Icons.Default.Search, if (searching) "Close search" else "Search chats") }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
        }
        if (searching) OutlinedTextField(query, { query = it }, placeholder = { Text("Search chats and messages") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))

        LazyColumn(Modifier.weight(1f)) {
            if (q.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Projects", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = onManageProjects) { Text(if (projects.isEmpty()) "New" else "Manage") }
                    }
                }
                projects.forEach { p ->
                    val open = p.id in expanded
                    item(key = "p-" + p.id) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp))
                            .combinedClickableCompat(onClick = { expanded = if (open) expanded - p.id else expanded + p.id })
                            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(12.dp))
                            Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onNew(p.id) }) { Icon(Icons.Default.Add, "New chat in ${p.name}") }
                            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                    }
                    if (open) items(vm.conversations.filter { it.projectId == p.id }, key = { it.id }) { c -> ChatRow(vm, c, indent = true, onPick, onRename) }
                }
            }
            item {
                Text(if (q.isEmpty()) "Recent" else "Results", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
            }
            val list = if (q.isEmpty()) vm.conversations.filter { c -> c.projectId == null || projects.none { it.id == c.projectId } } else vm.conversations.filter(::matches)
            if (list.isEmpty()) item { Text(if (q.isEmpty()) "No chats yet" else "Nothing found", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(list, key = { "c-" + it.id }) { c -> ChatRow(vm, c, indent = false, onPick, onRename) }
            item { Spacer(Modifier.size(88.dp)) } // room above the New chat button
        }
    }
    ExtendedFloatingActionButton(onClick = { onNew(null) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New chat") },
        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

/** One chat: title and "when", then what it belongs to or what answered last. Long-press for Rename / Delete. */
@Composable
private fun ChatRow(vm: ChatViewModel, c: Conversation, indent: Boolean, onPick: (String) -> Unit, onRename: (Conversation) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val selected = c.id == vm.current.id
    val subtitle = vm.settings.projects.firstOrNull { it.id == c.projectId }?.name
        ?: c.messages.lastOrNull { it.role == MsgRole.ASSISTANT && it.source != null }?.source
        ?: "No replies yet"
    Box {
        Column(Modifier.fillMaxWidth().padding(start = if (indent) 24.dp else 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickableCompat(onClick = { onPick(c.id) }, onLongClick = { menu = true })
            .padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(8.dp))
                Text(ago(c.updatedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename or move") }, onClick = { menu = false; onRename(c) })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; vm.delete(c.id) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null) =
    combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
