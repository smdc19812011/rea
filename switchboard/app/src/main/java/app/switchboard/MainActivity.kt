package app.switchboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.switchboard.ui.ChatScreen
import app.switchboard.ui.ChatViewModel
import app.switchboard.ui.ModelSheet
import app.switchboard.ui.SettingsScreen
import app.switchboard.ui.SwitchboardTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SwitchboardTheme {
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showModels by rememberSaveable { mutableStateOf(false) }
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()

                // System permission dialog for device tools (Bluetooth etc.), requested from the ViewModel.
                val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { res -> vm.onPermissionsResult(res.values.all { it }) }
                androidx.compose.runtime.LaunchedEffect(vm.permissionRequest) { vm.permissionRequest?.let { permLauncher.launch(it) } }

                BackHandler(enabled = showSettings) { showSettings = false }
                BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

                if (showSettings) {
                    SettingsScreen(vm, onBack = { showSettings = false })
                    return@SwitchboardTheme
                }
                ModalNavigationDrawer(drawerState = drawer, drawerContent = {
                    ModalDrawerSheet {
                        Text("Switchboard", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
                        ExtendedFloatingActionButton(
                            onClick = { vm.newChat(); scope.launch { drawer.close() } },
                            icon = { Icon(Icons.Default.Add, null) }, text = { Text("New chat") },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(Modifier.size(12.dp))
                        if (vm.conversations.isEmpty()) Text("No chats yet", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn {
                            items(vm.conversations, key = { it.id }) { c ->
                                NavigationDrawerItem(
                                    label = {
                                        Row { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                                    },
                                    badge = { IconButton(onClick = { vm.delete(c.id) }) { Icon(Icons.Default.Close, "Delete chat ${c.title}") } },
                                    selected = c.id == vm.current.id,
                                    onClick = { vm.open(c.id); scope.launch { drawer.close() } },
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                            }
                        }
                    }
                }) {
                    ChatScreen(vm,
                        onMenu = { scope.launch { drawer.open() } },
                        onSettings = { showSettings = true },
                        onPickModel = { showModels = true })
                }
                if (showModels) ModelSheet(vm, onDismiss = { showModels = false })
            }
        }
    }
}
