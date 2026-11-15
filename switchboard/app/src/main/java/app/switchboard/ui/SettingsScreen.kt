package app.switchboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Row as FRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.switchboard.data.ApiKind
import app.switchboard.data.Presets
import app.switchboard.data.Provider
import app.switchboard.data.RunnerMode
import app.switchboard.data.DailyReset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import app.switchboard.net.Termux
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit, onOpen: (String) -> Unit = {}) {
    var editing by remember { mutableStateOf<Provider?>(null) }
    var confirmDelete by remember { mutableStateOf<Provider?>(null) }
    var presetMenu by remember { mutableStateOf(false) }
    var confirmUnrestricted by remember { mutableStateOf(false) }
    val s = vm.settings

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val st = vm.settings
            listOf(
                Triple(Pages.CONNECTORS, "Connectors", "MCP servers, REST APIs, OAuth · ${st.connectors.size}"),
                Triple(Pages.SKILLS, "Skills", "Reusable instructions · ${st.skills.size}"),
                Triple(Pages.AGENTS, "Agents", "Assistants with their own prompt and model · ${st.agents.size}"),
                Triple(Pages.PROJECTS, "Projects", "Chats with shared instructions and files · ${st.projects.size}"),
                Triple(Pages.TASKS, "Scheduled tasks", "Prompts that run on their own · ${st.tasks.size}"),
            ).forEach { (page, title, sub) ->
                ListItem(headlineContent = { Text(title) }, supportingContent = { Text(sub) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    modifier = Modifier.clickable { onOpen(page) })
            }
            HorizontalDivider()
            Text("Model providers", style = MaterialTheme.typography.titleMedium)
            Text("The selected provider answers new messages. You can switch mid-conversation; the full history is sent to whichever model is active.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.providers.forEach { p ->
                ListItem(
                    leadingContent = { RadioButton(selected = p.id == vm.activeProvider?.id, onClick = { vm.selectProvider(p.id) }) },
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text("${p.model.ifBlank { "no model" }} · ${p.baseUrl}", maxLines = 2) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editing = p }) { Icon(Icons.Default.Edit, "Edit ${p.name}") }
                            IconButton(onClick = { confirmDelete = p }) { Icon(Icons.Default.Delete, "Delete ${p.name}") }
                        }
                    },
                )
            }
            Box {
                FilledTonalButton(onClick = { presetMenu = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.size(6.dp)); Text("Add provider") }
                DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                    Presets.all.forEach { preset ->
                        DropdownMenuItem(text = { Text(preset.name) }, onClick = { presetMenu = false; editing = preset.copy(id = UUID.randomUUID().toString()) })
                    }
                }
            }

            Spacer(Modifier.size(16.dp))
            Text("Conversation", style = MaterialTheme.typography.titleMedium)
            var sys by remember { mutableStateOf(s.systemPrompt) }
            OutlinedTextField(sys, { sys = it; vm.setSystemPrompt(it) }, label = { Text("System prompt (all providers)") },
                minLines = 3, modifier = Modifier.fillMaxWidth())
            var max by remember { mutableStateOf(s.maxTokens.toString()) }
            OutlinedTextField(max, { v -> max = v.filter(Char::isDigit).take(7); max.toIntOrNull()?.let(vm::setMaxTokens) },
                label = { Text(if (s.unrestricted) "Max output tokens (all providers)" else "Max output tokens (Anthropic)") },
                supportingText = { Text("Up to ${"%,d".format(vm.maxTokenLimit)}") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(16.dp))
            FRow(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Unrestricted mode", style = MaterialTheme.typography.titleMedium)
                    Text("Removes the app's own limits: allows plain http:// to any host, raises the token cap to 1M and sends it to every provider, and adds raw request params. The provider still decides what its model will answer.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.unrestricted, onCheckedChange = { on -> if (on) confirmUnrestricted = true else { vm.setUnrestricted(false); max = vm.settings.maxTokens.toString() } })
            }
            if (s.unrestricted) {
                var extra by remember { mutableStateOf(s.extraBody) }
                val extraError = runCatching { vm.parseExtraBody(extra) }.exceptionOrNull()?.message
                OutlinedTextField(extra, { extra = it; if (runCatching { vm.parseExtraBody(it) }.isSuccess) vm.setExtraBody(it) },
                    label = { Text("Extra request params (JSON)") },
                    placeholder = { Text("{\"temperature\": 0.7}") },
                    isError = extraError != null,
                    supportingText = { Text(extraError ?: "Merged into every request body. model, stream and messages can't be overridden.") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    minLines = 3, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.size(16.dp))
            LimitsSection(vm)
            Spacer(Modifier.size(16.dp))
            ToolsSection(vm)
            Text("API keys are encrypted with the Android Keystore and only sent to the provider's own base URL.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (confirmUnrestricted) AlertDialog(onDismissRequest = { confirmUnrestricted = false },
        title = { Text("Turn on unrestricted mode?") },
        text = { Text("API keys and chats can be sent over unencrypted http:// to any server you enter, and very large token limits can run up provider costs. Only use servers you trust.") },
        confirmButton = { TextButton(onClick = { vm.setUnrestricted(true); confirmUnrestricted = false }) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = { confirmUnrestricted = false }) { Text("Cancel") } })
    editing?.let { p -> ProviderDialog(p, isNew = s.providers.none { it.id == p.id }, unrestricted = s.unrestricted, onDismiss = { editing = null }, onSave = { vm.upsertProvider(it); editing = null }) }
    confirmDelete?.let { p ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${p.name}?") }, text = { Text("Its saved API key will be removed from this device.") },
            confirmButton = { TextButton(onClick = { vm.removeProvider(p.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDialog(initial: Provider, isNew: Boolean, unrestricted: Boolean, onDismiss: () -> Unit, onSave: (Provider) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var kind by remember { mutableStateOf(initial.kind) }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var showKey by remember { mutableStateOf(false) }

    val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
    val urlOk = (url.startsWith("https://") && host.isNotEmpty()) ||
        (url.startsWith("http://") && host.isNotEmpty() && (unrestricted || app.switchboard.net.isLocalHost(host)))
    val keyNeeded = kind == ApiKind.ANTHROPIC
    val valid = name.isNotBlank() && urlOk && (!keyNeeded || key.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add provider" else "Edit provider") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("API format", style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ApiKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(selected = kind == k, onClick = { kind = k }, shape = SegmentedButtonDefaults.itemShape(i, ApiKind.entries.size)) {
                            Text(if (k == ApiKind.ANTHROPIC) "Anthropic" else "OpenAI-compatible")
                        }
                    }
                }
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Base URL") }, singleLine = true,
                    isError = !urlOk, supportingText = { if (!urlOk) Text(if (unrestricted) "Must be a full http:// or https:// URL" else "Must start with https:// (http:// only for local servers, or turn on Unrestricted mode)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it.trim() }, label = { Text(if (keyNeeded) "API key" else "API key (optional for local servers)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showKey) "Hide key" else "Show key") } })
                OutlinedTextField(model, { model = it.trim() }, label = { Text("Model ID") }, singleLine = true,
                    supportingText = { Text("You can also browse the provider's models from the chat screen.") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(initial.copy(name = name.trim(), kind = kind, baseUrl = url.trimEnd('/'), apiKey = key, model = model))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsSection(vm: ChatViewModel) {
    val s = vm.settings
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    FRow(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Tools: let the model run commands", style = MaterialTheme.typography.titleMedium)
            Text("The model can run commands, write files, build things and install APKs on this phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = s.toolsEnabled, onCheckedChange = vm::setToolsEnabled)
    }
    if (!s.toolsEnabled) return

    Text("Run on", style = MaterialTheme.typography.labelMedium)
    val modes = listOf(RunnerMode.PHONE to "This phone", RunnerMode.TERMUX to "Termux", RunnerMode.REMOTE to "Computer")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, (m, label) ->
            SegmentedButton(selected = s.runnerMode == m, onClick = { vm.setRunnerMode(m); status = null },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(label) }
        }
    }

    when (s.runnerMode) {
        RunnerMode.PHONE -> Text(
            "No setup. Commands run inside Switchboard with Android's built-in shell. Android doesn't let apps install packages or compilers, " +
                "so this handles files and simple commands only. To build APKs on the phone, choose Termux.",
            style = MaterialTheme.typography.bodySmall)

        RunnerMode.TERMUX -> {
            val installed = Termux.isInstalled(ctx)
            var granted by remember { mutableStateOf(Termux.hasPermission(ctx)) }
            val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
            Text("Full Linux tools on this phone. Switchboard starts the runner in Termux for you. One-time setup:", style = MaterialTheme.typography.bodySmall)
            SetupStep(1, "Install Termux from F-Droid and open it once.", done = installed)
            SetupStep(2, "In Termux, run this command (it lets Switchboard start commands there):", done = false)
            FRow(verticalAlignment = Alignment.CenterVertically) {
                Text(Termux.ENABLE_EXTERNAL_APPS, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(Termux.ENABLE_EXTERNAL_APPS)); status = "Command copied. Paste it in Termux." }) { Text("Copy") }
            }
            SetupStep(3, "Allow Switchboard to run commands in Termux.", done = granted)
            if (!granted) OutlinedButton(enabled = installed, onClick = { askPermission.launch(Termux.PERMISSION) }) { Text("Grant permission") }
            FilledTonalButton(enabled = installed && granted, onClick = {
                status = "Starting the runner in Termux… (the first start installs Python and can take a minute)"
                scope.launch { status = vm.startTermuxRunner().getOrElse { "Failed: ${it.message}" } }
            }) { Text("Start runner now") }
            Text("It also starts automatically when the model needs it.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        RunnerMode.REMOTE -> {
            val script = remember { ctx.assets.open("runner.py").bufferedReader().use { it.readText() } }
            val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-python")) { uri ->
                if (uri != null) status = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(script.toByteArray()) }; "Saved runner.py" }
                    .getOrElse { "Couldn't save: ${it.message}" }
            }
            Text("On the computer, run: python3 runner.py --host 0.0.0.0. Then enter http://<computer-ip>:8765 and the token it prints. " +
                "Plain http on your network needs Unrestricted mode.", style = MaterialTheme.typography.bodySmall)
            FRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { save.launch("runner.py") }) { Text("Save runner.py") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(script)); status = "Copied runner.py to the clipboard" }) { Text("Copy") }
            }
            var url by remember { mutableStateOf(s.runnerUrl) }
            var token by remember { mutableStateOf(s.runnerToken) }
            var showToken by remember { mutableStateOf(false) }
            OutlinedTextField(url, { url = it; vm.setRunner(it, token) }, label = { Text("Runner URL") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it; vm.setRunner(url, it) }, label = { Text("Runner token") }, singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { showToken = !showToken }) {
                    Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showToken) "Hide token" else "Show token") } },
                modifier = Modifier.fillMaxWidth())
        }
    }
    if (s.runnerMode != RunnerMode.TERMUX)
        FilledTonalButton(onClick = { status = "Connecting…"; scope.launch { status = vm.testRunner().getOrElse { "Failed: ${it.message}" } } }) { Text("Test") }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    FRow(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Ask before each action", style = MaterialTheme.typography.bodyLarge)
            Text(if (s.askBeforeRun) "You approve each command, file write and install." else "Off: the model runs everything without asking.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = s.askBeforeRun, onCheckedChange = vm::setAskBeforeRun)
    }
}

@Composable
private fun SetupStep(n: Int, text: String, done: Boolean) {
    FRow(verticalAlignment = Alignment.Top) {
        Text(if (done) "✓" else "$n.", modifier = Modifier.size(width = 24.dp, height = 20.dp),
            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LimitsSection(vm: ChatViewModel) {
    val s = vm.settings
    Text("Request limits", style = MaterialTheme.typography.titleMedium)
    Text("Shared by all chats; every model request counts, including each step of a tool run. When a limit is hit, the request waits and is sent automatically when the window opens. Leave blank for no limit.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    var perMin by remember { mutableStateOf(if (s.maxPerMinute > 0) s.maxPerMinute.toString() else "") }
    var perDay by remember { mutableStateOf(if (s.maxPerDay > 0) s.maxPerDay.toString() else "") }
    fun save() = vm.setRateLimits(perMin.toIntOrNull() ?: 0, perDay.toIntOrNull() ?: 0)
    FRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(perMin, { perMin = it.filter(Char::isDigit).take(6); save() }, label = { Text("Per minute") }, singleLine = true,
            placeholder = { Text("No limit") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        OutlinedTextField(perDay, { perDay = it.filter(Char::isDigit).take(7); save() }, label = { Text("Per day") }, singleLine = true,
            placeholder = { Text("No limit") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
    }
    Text("Daily limit resets", style = MaterialTheme.typography.labelMedium)
    val resets = listOf(DailyReset.MIDNIGHT to "At midnight", DailyReset.ROLLING to "Rolling 24 hours")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        resets.forEachIndexed { i, (r, label) ->
            SegmentedButton(selected = s.dailyReset == r, onClick = { vm.setDailyReset(r) }, shape = SegmentedButtonDefaults.itemShape(i, resets.size)) { Text(label) }
        }
    }
    var usage by remember { mutableStateOf(vm.usage()) }
    LaunchedEffect(s.dailyReset, s.timeZone) { while (true) { usage = vm.usage(); kotlinx.coroutines.delay(1000) } }
    Text("Used: ${usage.lastMinute}${if (s.maxPerMinute > 0) " / ${s.maxPerMinute}" else ""} in the last minute · " +
        "${usage.today}${if (s.maxPerDay > 0) " / ${s.maxPerDay}" else ""} " +
        if (s.dailyReset == DailyReset.MIDNIGHT) "today (resets at midnight, ${vm.zone.id})" else "in the last 24 hours",
        style = MaterialTheme.typography.bodySmall)

    Spacer(Modifier.size(16.dp))
    Text("Time zone", style = MaterialTheme.typography.titleMedium)
    var picking by remember { mutableStateOf(false) }
    val device = ZoneId.systemDefault().id
    ListItem(
        headlineContent = { Text(if (s.timeZone.isBlank()) "Device default ($device)" else s.timeZone) },
        supportingContent = { Text("Now: " + ZonedDateTime.now(vm.zone).format(DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a")) +
            ". Used for the daily reset and to tell the model your local time.") },
        trailingContent = { TextButton(onClick = { picking = true }) { Text("Change") } },
    )
    if (picking) TimeZonePicker(current = s.timeZone, onPick = { vm.setTimeZone(it); picking = false }, onDismiss = { picking = false })
}

@Composable
private fun TimeZonePicker(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val zones = remember { ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted() }
    var query by remember { mutableStateOf("") }
    val shown = zones.filter { query.isBlank() || it.replace('_', ' ').contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time zone") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search, e.g. Manila or Europe") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    if (query.isBlank()) item {
                        ZoneRow("Device default (${ZoneId.systemDefault().id})", selected = current.isBlank()) { onPick("") }
                    }
                    items(shown, key = { it }) { id -> ZoneRow(id.replace('_', ' '), selected = id == current) { onPick(id) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ZoneRow(label: String, selected: Boolean, onClick: () -> Unit) {
    FRow(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
