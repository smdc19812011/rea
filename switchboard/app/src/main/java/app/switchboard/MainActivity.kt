package app.switchboard

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.switchboard.data.Conversation
import app.switchboard.ui.ChatScreen
import app.switchboard.ui.ChatViewModel
import app.switchboard.ui.ManagePage
import app.switchboard.ui.ModelSheet
import app.switchboard.ui.Pages
import app.switchboard.ui.Picker
import app.switchboard.ui.SettingsScreen
import app.switchboard.ui.SwitchboardTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            SwitchboardTheme {
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var page by rememberSaveable { mutableStateOf<String?>(null) }
                var showModels by rememberSaveable { mutableStateOf(false) }
                var renaming by remember { mutableStateOf<Conversation?>(null) }
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val ctx = LocalContext.current

                // System permission dialog for device tools (Bluetooth etc.), requested from the ViewModel.
                val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res -> vm.onPermissionsResult(res.values.all { it }) }
                LaunchedEffect(vm.permissionRequest) { vm.permissionRequest?.let { permLauncher.launch(it) } }
                LaunchedEffect(vm.oauthStatus) { vm.oauthStatus?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() } }

                BackHandler(enabled = page != null) { page = null }
                BackHandler(enabled = showSettings && page == null) { showSettings = false }
                BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

                if (page != null) { ManagePage(vm, page!!, onBack = { page = null }); return@SwitchboardTheme }
                if (showSettings) { SettingsScreen(vm, onBack = { showSettings = false }, onOpen = { page = it }); return@SwitchboardTheme }

                ModalNavigationDrawer(drawerState = drawer, drawerContent = {
                    ModalDrawerSheet {
                        Drawer(vm,
                            onPick = { vm.open(it); scope.launch { drawer.close() } },
                            onNew = { projectId -> vm.newChat(projectId); scope.launch { drawer.close() } },
                            onRename = { renaming = it },
                            onManageProjects = { showSettings = true; page = Pages.PROJECTS; scope.launch { drawer.close() } })
                    }
                }) {
                    ChatScreen(vm, onMenu = { scope.launch { drawer.open() } }, onSettings = { showSettings = true }, onPickModel = { showModels = true })
                }
                if (showModels) ModelSheet(vm, onDismiss = { showModels = false })
                renaming?.let { c -> RenameDialog(vm, c) { renaming = null } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.reloadConversations()
    }

    /** OAuth redirects (switchboard://oauth) and taps on scheduled-task notifications. */
    private fun handle(intent: Intent?) {
        val data = intent?.data
        if (data?.scheme == "switchboard" && data.host == "oauth") { vm.onOAuthRedirect(data); intent.data = null }
        intent?.getStringExtra("conversationId")?.let { vm.openById(it); intent.removeExtra("conversationId") }
    }
}

@Composable
private fun Drawer(vm: ChatViewModel, onPick: (String) -> Unit, onNew: (String?) -> Unit, onRename: (Conversation) -> Unit, onManageProjects: () -> Unit) {
    val projects = vm.settings.projects
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    Text("Switchboard", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
    ExtendedFloatingActionButton(onClick = { onNew(null) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New chat") },
        modifier = Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.size(8.dp))
    LazyColumn {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Projects", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onManageProjects) { Text(if (projects.isEmpty()) "New" else "Manage") }
            }
        }
        projects.forEach { p ->
            val open = p.id in expanded
            item(key = "p-" + p.id) {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Folder, null) },
                    label = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    badge = {
                        Row {
                            IconButton(onClick = { onNew(p.id) }) { Icon(Icons.Default.Add, "New chat in ${p.name}") }
                            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                    },
                    selected = false, onClick = { expanded = if (open) expanded - p.id else expanded + p.id },
                    modifier = Modifier.padding(horizontal = 12.dp))
            }
            if (open) items(vm.conversations.filter { it.projectId == p.id }, key = { it.id }) { c -> ChatRow(vm, c, indent = true, onPick, onRename) }
        }
        item { Text("Chats", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp)) }
        val loose = vm.conversations.filter { c -> c.projectId == null || projects.none { it.id == c.projectId } }
        if (loose.isEmpty()) item { Text("No chats yet", Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(loose, key = { it.id }) { c -> ChatRow(vm, c, indent = false, onPick, onRename) }
    }
}

@Composable
private fun ChatRow(vm: ChatViewModel, c: Conversation, indent: Boolean, onPick: (String) -> Unit, onRename: (Conversation) -> Unit) {
    NavigationDrawerItem(
        label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        badge = {
            Row {
                IconButton(onClick = { onRename(c) }) { Icon(Icons.Default.Edit, "Rename ${c.title}") }
                IconButton(onClick = { vm.delete(c.id) }) { Icon(Icons.Default.Close, "Delete ${c.title}") }
            }
        },
        selected = c.id == vm.current.id, onClick = { onPick(c.id) },
        modifier = Modifier.padding(start = if (indent) 32.dp else 12.dp, end = 12.dp))
}

@Composable
private fun RenameDialog(vm: ChatViewModel, c: Conversation, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(c.title) }
    var project by remember { mutableStateOf(c.projectId) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rename chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (vm.settings.projects.isNotEmpty())
                    Picker("Project", listOf<Pair<String?, String>>(null to "None") + vm.settings.projects.map { it.id to it.name }, project) { project = it }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { vm.renameChat(c.id, title, project); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

