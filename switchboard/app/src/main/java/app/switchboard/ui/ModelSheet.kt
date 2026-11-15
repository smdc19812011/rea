package app.switchboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Bottom sheet: pick the active provider, then type a model ID or pick from the provider's live /models list. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ModelSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val provider = vm.activeProvider ?: return onDismiss()
    val scope = rememberCoroutineScope()
    var models by remember(provider.id) { mutableStateOf<List<String>?>(null) }
    var loading by remember(provider.id) { mutableStateOf(false) }
    var error by remember(provider.id) { mutableStateOf<String?>(null) }
    var query by remember(provider.id) { mutableStateOf(provider.model) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Provider", style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.settings.providers.forEach { p ->
                    FilterChip(selected = p.id == provider.id, onClick = { vm.selectProvider(p.id) }, label = { Text(p.name) })
                }
            }
            Spacer(Modifier.size(12.dp))
            Text("Model", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Model ID or search") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (query.isNotBlank()) { vm.setModel(query.trim()); onDismiss() } }),
            )
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.setModel(query.trim()); onDismiss() }, enabled = query.isNotBlank()) { Text("Use this ID") }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(enabled = !loading, onClick = {
                    loading = true; error = null
                    scope.launch {
                        vm.fetchModels(provider).onSuccess { models = it; query = "" }.onFailure { error = it.message }
                        loading = false
                    }
                }) { Text(if (models == null) "Browse models" else "Refresh") }
                if (loading) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            models?.let { list ->
                val filtered = list.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
                Text("${filtered.size} of ${list.size} models", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(top = 4.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(filtered, key = { it }) { id ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = id == provider.model, role = Role.RadioButton) { vm.setModel(id); onDismiss() }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(id, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (id == provider.model) Icon(Icons.Default.Check, "Current model", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}
