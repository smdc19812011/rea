package app.switchboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Bottom sheet: pick the provider, this chat's agent, and which of the provider's ranked chat models to use now. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelSheet(vm: ChatViewModel, onDismiss: () -> Unit, onEditProviders: () -> Unit = {}) {
    val provider = vm.activeProvider ?: return onDismiss()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Provider", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.settings.providers.forEach { p ->
                    FilterChip(selected = p.id == provider.id, onClick = { vm.selectProvider(p.id) }, label = { Text(p.name) })
                }
            }
            if (vm.settings.agents.isNotEmpty()) {
                Spacer(Modifier.size(12.dp))
                Text("Agent for this chat", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vm.current.agentId == null, onClick = { vm.setChatAgent(null) }, label = { Text("None") })
                    vm.settings.agents.forEach { a -> FilterChip(selected = vm.current.agentId == a.id, onClick = { vm.setChatAgent(a.id) }, label = { Text(a.name) }) }
                }
            }
            Spacer(Modifier.size(12.dp))
            Text("Chat model", style = MaterialTheme.typography.titleSmall)
            Text("Ranked in the provider's settings. If the one in use can't answer, the next one takes over, with the whole conversation.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val ranked = provider.chatModels.ifEmpty { listOfNotNull(provider.model.takeIf { it.isNotBlank() }) }
            if (ranked.isEmpty()) Text("No models chosen yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(ranked, key = { _, id -> id }) { i, id ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = id == provider.model, role = Role.RadioButton) { vm.setModel(id); onDismiss() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}.", style = MaterialTheme.typography.labelLarge, modifier = Modifier.size(width = 28.dp, height = 20.dp))
                        Text(id, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (id == provider.model) Icon(Icons.Default.Check, "In use", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            TextButton(onClick = { onDismiss(); onEditProviders() }) { Text("Choose and rank models…") }
            Spacer(Modifier.size(16.dp))
        }
    }
}
