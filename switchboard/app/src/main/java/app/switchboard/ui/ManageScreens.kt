package app.switchboard.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.switchboard.data.Agent
import app.switchboard.data.AuthType
import app.switchboard.data.Connector
import app.switchboard.data.ConnectorKind
import app.switchboard.data.Project
import app.switchboard.data.Repeat
import app.switchboard.data.ScheduledTask
import app.switchboard.data.Skill
import app.switchboard.net.Connectors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

object Pages {
    const val CONNECTORS = "connectors"
    const val SKILLS = "skills"
    const val AGENTS = "agents"
    const val PROJECTS = "projects"
    const val TASKS = "tasks"
    const val TOOLBOX = "toolbox"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagePage(vm: ChatViewModel, page: String, onBack: () -> Unit) {
    val title = mapOf(Pages.CONNECTORS to "Connectors", Pages.SKILLS to "Skills", Pages.AGENTS to "Agents",
        Pages.PROJECTS to "Projects", Pages.TASKS to "Scheduled tasks", Pages.TOOLBOX to "Toolbox")[page] ?: page
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (page) {
                Pages.CONNECTORS -> ConnectorsPage(vm)
                Pages.SKILLS -> SkillsPage(vm)
                Pages.AGENTS -> AgentsPage(vm)
                Pages.PROJECTS -> ProjectsPage(vm)
                Pages.TASKS -> TasksPage(vm)
                Pages.TOOLBOX -> ToolboxPage(vm)
            }
        }
    }
}

// ---------- shared bits ----------

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun <T> Picker(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "Choose"); Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onSelect(v); open = false }) }
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, minLines: Int = 1, secret: Boolean = false, placeholder: String? = null) =
    OutlinedTextField(value, onChange, label = { Text(label) }, minLines = minLines, singleLine = minLines == 1,
        placeholder = placeholder?.let { { Text(it) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth())

@Composable
private fun EditDialog(title: String, canSave: Boolean, onSave: () -> Unit, onDismiss: () -> Unit, content: @Composable () -> Unit) =
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } },
        confirmButton = { TextButton(enabled = canSave, onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })

@Composable
private fun ConfirmDelete(what: String, onConfirm: () -> Unit, onDismiss: () -> Unit) =
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Delete $what?") },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })

@Composable
private fun RowActions(onEdit: () -> Unit, onDelete: () -> Unit, name: String) = Row {
    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit $name") }
    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete $name") }
}

// ---------- connectors ----------

@Composable
private fun ConnectorsPage(vm: ChatViewModel) {
    var editing by remember { mutableStateOf<Connector?>(null) }
    var deleting by remember { mutableStateOf<Connector?>(null) }
    val status = remember { mutableStateOf(mapOf<String, String>()) }
    val scope = rememberCoroutineScope()
    Hint("Give the model tools from other services. MCP servers (Streamable HTTP) share their tools automatically; REST APIs become one flexible request tool. " +
        "OAuth sign-in uses the redirect URI ${Connectors.REDIRECT_URI}.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { editing = Connector(kind = ConnectorKind.MCP, name = "", url = "https://") }) { Text("Add MCP server") }
        FilledTonalButton(onClick = { editing = Connector(kind = ConnectorKind.REST, name = "", url = "https://") }) { Text("Add REST API") }
    }
    vm.oauthStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    if (vm.settings.connectors.isEmpty()) Hint("No connectors yet.")
    vm.settings.connectors.forEach { c ->
        HorizontalDivider()
        ListItem(
            leadingContent = { Switch(checked = c.enabled, onCheckedChange = { vm.upsertConnector(c.copy(enabled = it)) }) },
            headlineContent = { Text(c.name) },
            supportingContent = {
                Column {
                    Text("${if (c.kind == ConnectorKind.MCP) "MCP" else "REST"} · ${c.url}", maxLines = 1)
                    Text("Auth: ${c.auth.type.name.lowercase()}" + if (c.auth.type == AuthType.OAUTH) (if (vm.isSignedIn(c.id)) " · signed in" else " · not signed in") else "")
                    status.value[c.id]?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            },
            trailingContent = { RowActions({ editing = c }, { deleting = c }, c.name) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp)) {
            OutlinedButton(onClick = {
                status.value = status.value + (c.id to "Testing…")
                scope.launch { status.value = status.value + (c.id to vm.testConnector(c).getOrElse { "Failed: ${it.message}" }) }
            }) { Text("Test") }
            if (c.auth.type == AuthType.OAUTH) {
                if (vm.isSignedIn(c.id)) TextButton(onClick = { vm.signOut(c.id) }) { Text("Sign out") }
                FilledTonalButton(onClick = { scope.launch { vm.connectOAuth(c) } }) { Text(if (vm.isSignedIn(c.id)) "Reconnect" else "Connect") }
            }
        }
    }
    editing?.let { c -> ConnectorDialog(c, vm.settings.connectors.none { it.id == c.id }, onSave = { vm.upsertConnector(it); editing = null }, onDismiss = { editing = null }) }
    deleting?.let { c -> ConfirmDelete(c.name, { vm.removeConnector(c.id) }) { deleting = null } }
}

@Composable
private fun ConnectorDialog(initial: Connector, isNew: Boolean, onSave: (Connector) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var desc by remember { mutableStateOf(initial.description) }
    var auth by remember { mutableStateOf(initial.auth) }
    val mcp = initial.kind == ConnectorKind.MCP
    val urlOk = url.startsWith("https://") && url.length > 9 || url.startsWith("http://")
    EditDialog(if (isNew) (if (mcp) "Add MCP server" else "Add REST API") else "Edit ${initial.name}", canSave = name.isNotBlank() && urlOk,
        onSave = { onSave(initial.copy(name = name.trim(), url = url.trim(), description = desc.trim(), auth = auth)) }, onDismiss = onDismiss) {
        Field(name, { name = it }, "Name")
        Field(url, { url = it.trim() }, if (mcp) "Server URL (Streamable HTTP endpoint)" else "Base URL")
        if (!mcp) Field(desc, { desc = it }, "What this API does (shown to the model)", minLines = 2)
        Picker("Authentication", AuthType.entries.map { it to when (it) {
            AuthType.NONE -> "None"; AuthType.BEARER -> "Bearer token"; AuthType.HEADER -> "API key header"; AuthType.BASIC -> "Username + password"; AuthType.OAUTH -> "OAuth 2.0 sign-in"
        } }, auth.type) { auth = auth.copy(type = it) }
        when (auth.type) {
            AuthType.NONE -> Unit
            AuthType.BEARER -> Field(auth.token, { auth = auth.copy(token = it.trim()) }, "Token", secret = true)
            AuthType.HEADER -> { Field(auth.headerName, { auth = auth.copy(headerName = it.trim()) }, "Header name"); Field(auth.token, { auth = auth.copy(token = it.trim()) }, "Key", secret = true) }
            AuthType.BASIC -> { Field(auth.username, { auth = auth.copy(username = it) }, "Username"); Field(auth.password, { auth = auth.copy(password = it) }, "Password", secret = true) }
            AuthType.OAUTH -> {
                val o = auth.oauth
                Hint(if (mcp) "Most MCP servers fill these in automatically when you tap Connect; leave them blank first." else "From the service's developer settings. Redirect URI: ${Connectors.REDIRECT_URI}")
                Field(o.authUrl, { auth = auth.copy(oauth = o.copy(authUrl = it.trim())) }, "Authorization URL")
                Field(o.tokenUrl, { auth = auth.copy(oauth = o.copy(tokenUrl = it.trim())) }, "Token URL")
                Field(o.clientId, { auth = auth.copy(oauth = o.copy(clientId = it.trim())) }, "Client ID")
                Field(o.clientSecret, { auth = auth.copy(oauth = o.copy(clientSecret = it.trim())) }, "Client secret (optional)", secret = true)
                Field(o.scopes, { auth = auth.copy(oauth = o.copy(scopes = it)) }, "Scopes (space-separated, optional)")
            }
        }
    }
}

// ---------- skills ----------

@Composable
private fun SkillsPage(vm: ChatViewModel) {
    var editing by remember { mutableStateOf<Skill?>(null) }
    var deleting by remember { mutableStateOf<Skill?>(null) }
    Hint("Skills are reusable instructions (a house style, a checklist, a workflow). The model sees each skill's name and description and loads the full text when a task needs it. The model can also save skills for you.")
    FilledTonalButton(onClick = { editing = Skill(name = "", description = "", instructions = "") }) { Icon(Icons.Default.Add, null); Spacer(Modifier.size(6.dp)); Text("New skill") }
    if (vm.settings.skills.isEmpty()) Hint("No skills yet.")
    vm.settings.skills.forEach { k ->
        HorizontalDivider()
        ListItem(leadingContent = { Switch(checked = k.enabled, onCheckedChange = { vm.upsertSkill(k.copy(enabled = it)) }) },
            headlineContent = { Text(k.name) }, supportingContent = { Text(k.description, maxLines = 2) },
            trailingContent = { RowActions({ editing = k }, { deleting = k }, k.name) })
    }
    editing?.let { k ->
        var name by remember(k.id) { mutableStateOf(k.name) }
        var desc by remember(k.id) { mutableStateOf(k.description) }
        var instr by remember(k.id) { mutableStateOf(k.instructions) }
        EditDialog(if (k.name.isEmpty()) "New skill" else "Edit skill", name.isNotBlank() && instr.isNotBlank(),
            onSave = { vm.upsertSkill(k.copy(name = name.trim(), description = desc.trim(), instructions = instr)); editing = null }, onDismiss = { editing = null }) {
            Field(name, { name = it }, "Name")
            Field(desc, { desc = it }, "When to use it", minLines = 2)
            Field(instr, { instr = it }, "Instructions (Markdown)", minLines = 8)
        }
    }
    deleting?.let { k -> ConfirmDelete(k.name, { vm.removeSkill(k.id) }) { deleting = null } }
}

// ---------- agents ----------

@Composable
private fun AgentsPage(vm: ChatViewModel) {
    var editing by remember { mutableStateOf<Agent?>(null) }
    var deleting by remember { mutableStateOf<Agent?>(null) }
    Hint("Agents are named assistants with their own instructions and, optionally, their own model. Pick one for a chat from the model menu, or let the model hand tasks to them (run_agent).")
    FilledTonalButton(onClick = { editing = Agent(name = "", systemPrompt = "") }) { Icon(Icons.Default.Add, null); Spacer(Modifier.size(6.dp)); Text("New agent") }
    if (vm.settings.agents.isEmpty()) Hint("No agents yet.")
    vm.settings.agents.forEach { a ->
        HorizontalDivider()
        val providerName = vm.settings.providers.firstOrNull { it.id == a.providerId }?.name ?: "active provider"
        ListItem(headlineContent = { Text(a.name) },
            supportingContent = { Text("${a.description.ifBlank { a.systemPrompt.take(80) }}\n$providerName · ${a.model.ifBlank { "provider's model" }}${if (!a.useTools) " · no tools" else ""}", maxLines = 3) },
            trailingContent = { RowActions({ editing = a }, { deleting = a }, a.name) })
    }
    editing?.let { a ->
        var name by remember(a.id) { mutableStateOf(a.name) }
        var desc by remember(a.id) { mutableStateOf(a.description) }
        var prompt by remember(a.id) { mutableStateOf(a.systemPrompt) }
        var provider by remember(a.id) { mutableStateOf(a.providerId) }
        var model by remember(a.id) { mutableStateOf(a.model) }
        var tools by remember(a.id) { mutableStateOf(a.useTools) }
        EditDialog(if (a.name.isEmpty()) "New agent" else "Edit agent", name.isNotBlank() && prompt.isNotBlank(),
            onSave = { vm.upsertAgent(a.copy(name = name.trim(), description = desc.trim(), systemPrompt = prompt, providerId = provider, model = model.trim(), useTools = tools)); editing = null },
            onDismiss = { editing = null }) {
            Field(name, { name = it }, "Name")
            Field(desc, { desc = it }, "What it's for")
            Field(prompt, { prompt = it }, "Instructions (system prompt)", minLines = 6)
            Picker("Provider", listOf<Pair<String?, String>>(null to "Active provider") + vm.settings.providers.map { it.id to it.name }, provider) { provider = it }
            Field(model, { model = it }, "Model ID (blank = provider's model)")
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Can use tools and connectors", Modifier.weight(1f)); Switch(checked = tools, onCheckedChange = { tools = it }) }
        }
    }
    deleting?.let { a -> ConfirmDelete(a.name, { vm.removeAgent(a.id) }) { deleting = null } }
}

// ---------- projects ----------

@Composable
private fun ProjectsPage(vm: ChatViewModel) {
    var editing by remember { mutableStateOf<Project?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }
    Hint("Projects group chats and share instructions and reference files with every chat in them. Start a chat in a project from the side menu.")
    FilledTonalButton(onClick = { editing = Project(name = "") }) { Icon(Icons.Default.Add, null); Spacer(Modifier.size(6.dp)); Text("New project") }
    if (vm.settings.projects.isEmpty()) Hint("No projects yet.")
    vm.settings.projects.forEach { p ->
        HorizontalDivider()
        val chats = vm.conversations.count { it.projectId == p.id }
        ListItem(headlineContent = { Text(p.name) },
            supportingContent = { Text("$chats chats · ${p.files.size} files${if (p.instructions.isNotBlank()) " · " + p.instructions.take(60) else ""}", maxLines = 2) },
            trailingContent = { RowActions({ editing = p }, { deleting = p }, p.name) })
    }
    editing?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        var instr by remember(p.id) { mutableStateOf(p.instructions) }
        var files by remember(p.id) { mutableStateOf(p.files) }
        var note by remember(p.id) { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch { vm.addProjectFile(p.id, uri).onSuccess { files = files + it; note = null }.onFailure { note = it.message } }
        }
        EditDialog(if (p.name.isEmpty()) "New project" else "Edit project", name.isNotBlank(),
            onSave = { vm.upsertProject(p.copy(name = name.trim(), instructions = instr, files = files)); editing = null }, onDismiss = { editing = null }) {
            Field(name, { name = it }, "Name")
            Field(instr, { instr = it }, "Instructions for every chat in this project", minLines = 4)
            Text("Reference files (text is shared with the model, up to ~100 KB total)", style = MaterialTheme.typography.labelMedium)
            files.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${f.name} (${f.size / 1024} KB)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { files = files - f; java.io.File(f.path).delete() }) { Icon(Icons.Default.Close, "Remove ${f.name}") }
                }
            }
            OutlinedButton(onClick = { pick.launch(arrayOf("text/*", "application/json", "application/xml", "application/*")) }) { Text("Add file") }
            note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    deleting?.let { p -> ConfirmDelete("${p.name} (its chats are kept)", { vm.removeProject(p.id) }) { deleting = null } }
}

// ---------- scheduled tasks ----------

@Composable
private fun TasksPage(vm: ChatViewModel) {
    var editing by remember { mutableStateOf<ScheduledTask?>(null) }
    var deleting by remember { mutableStateOf<ScheduledTask?>(null) }
    var tick by remember { mutableStateOf(0) }
    Hint("Prompts that run on their own: once, every few hours, daily or weekly, in your time zone (${vm.zone.id}). Each run is saved as a chat and you get a notification. Runs happen in the background even when the app is closed (Android may delay them a little to save battery).")
    FilledTonalButton(onClick = { editing = ScheduledTask(name = "", prompt = "", startAt = System.currentTimeMillis() + 3_600_000) }) {
        Icon(Icons.Default.Add, null); Spacer(Modifier.size(6.dp)); Text("New scheduled task")
    }
    if (vm.settings.tasks.isEmpty()) Hint("No scheduled tasks yet. You can also ask the model, e.g. \"every weekday at 8:00, summarize the news\" (with Tools on).")
    vm.settings.tasks.forEach { t ->
        HorizontalDivider()
        val last = remember(t.id, tick) { vm.taskRun(t.id) }
        ListItem(leadingContent = { Switch(checked = t.enabled, onCheckedChange = { vm.upsertTask(t.copy(enabled = it)) }) },
            headlineContent = { Text(t.name) },
            supportingContent = {
                Column {
                    Text("${repeatText(t)} · next: ${vm.nextRunText(t)}")
                    last?.let { Text("Last run: ${if (it.ok) "✓" else "✗"} ${it.summary}", maxLines = 2, color = if (it.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
                }
            },
            trailingContent = { RowActions({ editing = t }, { deleting = t }, t.name) })
        TextButton(onClick = { vm.runTaskNow(t.id); tick++ }, modifier = Modifier.padding(start = 16.dp)) { Text("Run now") }
    }
    editing?.let { t -> TaskDialog(vm, t, onDismiss = { editing = null }) }
    deleting?.let { t -> ConfirmDelete(t.name, { vm.removeTask(t.id) }) { deleting = null } }
}

private fun repeatText(t: ScheduledTask) = when (t.repeat) {
    Repeat.ONCE -> "Once"; Repeat.HOURLY -> if (t.everyHours == 1) "Every hour" else "Every ${t.everyHours} hours"; Repeat.DAILY -> "Daily"; Repeat.WEEKLY -> "Weekly"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDialog(vm: ChatViewModel, t: ScheduledTask, onDismiss: () -> Unit) {
    val zone = vm.zone
    val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(t.startAt), zone)
    var name by remember { mutableStateOf(t.name) }
    var prompt by remember { mutableStateOf(t.prompt) }
    var repeat by remember { mutableStateOf(t.repeat) }
    var date by remember { mutableStateOf(start.toLocalDate()) }
    var time by remember { mutableStateOf(start.toLocalTime().withSecond(0).withNano(0)) }
    var hours by remember { mutableStateOf(t.everyHours.toString()) }
    var project by remember { mutableStateOf(t.projectId) }
    var agent by remember { mutableStateOf(t.agentId) }
    var tools by remember { mutableStateOf(t.allowTools) }
    var same by remember { mutableStateOf(t.sameChat) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    EditDialog(if (t.name.isEmpty()) "New scheduled task" else "Edit task", name.isNotBlank() && prompt.isNotBlank(), onSave = {
        vm.upsertTask(t.copy(name = name.trim(), prompt = prompt, repeat = repeat, startAt = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli(),
            everyHours = hours.toIntOrNull()?.coerceIn(1, 168) ?: 1, projectId = project, agentId = agent, allowTools = tools, sameChat = same))
        onDismiss()
    }, onDismiss = onDismiss) {
        Field(name, { name = it }, "Name")
        Field(prompt, { prompt = it }, "What to do each time", minLines = 4)
        val repeats = listOf(Repeat.ONCE to "Once", Repeat.HOURLY to "Hourly", Repeat.DAILY to "Daily", Repeat.WEEKLY to "Weekly")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            repeats.forEachIndexed { i, (r, l) -> SegmentedButton(selected = repeat == r, onClick = { repeat = r }, shape = SegmentedButtonDefaults.itemShape(i, repeats.size)) { Text(l) } }
        }
        Text(if (repeat == Repeat.ONCE) "Runs at" else "First run", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDate = true }) { Text(date.toString()) }
            OutlinedButton(onClick = { pickTime = true }) { Text(time.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))) }
        }
        if (repeat == Repeat.HOURLY) OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(3) }, label = { Text("Every how many hours") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Picker("Project", listOf<Pair<String?, String>>(null to "None") + vm.settings.projects.map { it.id to it.name }, project) { project = it }
        Picker("Agent", listOf<Pair<String?, String>>(null to "None (default assistant)") + vm.settings.agents.map { it.id to it.name }, agent) { agent = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Allow tools and connectors"); Hint("Runs unattended, so actions happen without asking.") }
            Switch(checked = tools, onCheckedChange = { tools = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Keep all runs in one chat"); Hint("Off: each run starts a new chat.") }
            Switch(checked = same, onCheckedChange = { same = it })
        }
    }
    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; pickDate = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } }) { DatePicker(state) }
    }
    if (pickTime) {
        val state = rememberTimePickerState(time.hour, time.minute, is24Hour = false)
        AlertDialog(onDismissRequest = { pickTime = false }, text = { TimePicker(state) },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); pickTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } })
    }
}


// ---------- toolbox ----------

@Composable
private fun ToolboxPage(vm: ChatViewModel) {
    val s = vm.settings
    val mode = s.runnerMode
    val machine = when (mode) { app.switchboard.data.RunnerMode.PHONE -> "this phone"; app.switchboard.data.RunnerMode.TERMUX -> "Termux"; app.switchboard.data.RunnerMode.REMOTE -> "your computer" }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<app.switchboard.data.ToolPackage?>(null) }
    var deleting by remember { mutableStateOf<app.switchboard.data.ToolPackage?>(null) }
    var openLog by remember { mutableStateOf<String?>(null) }
    Hint("Tools installed once on $machine (where Tools run; change it in Settings > Tools). Chats, agents, skills and scheduled tasks are told what's installed and where, so they use these instead of downloading again.")
    if (!s.toolsEnabled) Text("Turn on Tools in Settings so the model can use these.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (mode == app.switchboard.data.RunnerMode.PHONE) {
        Text("Tools are set to run on this phone's sandbox, which can only take GitHub repos and downloads. The Android SDK, ADB, npm, Python and the rest need Termux (on this phone) or a computer.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { vm.setRunnerMode(app.switchboard.data.RunnerMode.TERMUX); vm.setToolsEnabled(true) }) { Text("Use Termux for tools") }
    }
    val catalog = app.switchboard.engine.Toolbox.catalog
    val added = s.packages.map { it.name }.toSet()
    val devSet = catalog.filter { it.name in app.switchboard.engine.Toolbox.developerSet && app.switchboard.engine.Toolbox.availableOn(it, mode) }
    if (devSet.any { p -> s.packages.firstOrNull { it.name == p.name }?.installs?.get(mode.name)?.ok != true })
        FilledTonalButton(onClick = {
            devSet.forEach { p -> val existing = s.packages.firstOrNull { it.name == p.name } ?: p.also { vm.upsertPackage(it) }; vm.installPackage(existing.id) }
        }) { Text("Install the developer set (Android SDK, ADB, npm, Python, Git, Java)") }
    OutlinedButton(onClick = { editing = app.switchboard.data.ToolPackage(name = "", source = "") }) { Text("Add your own tool or source") }
    if (s.packages.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::installAll) { Text("Install all missing") }
        TextButton(onClick = vm::checkPackages) { Text("Check what's installed") }
    } else Hint("Nothing in the toolbox yet.")
    s.packages.forEach { p ->
        HorizontalDivider()
        val st = p.installs[mode.name]
        val busy = vm.installing[p.id]
        ListItem(
            headlineContent = { Text(p.name) },
            supportingContent = {
                Column {
                    Text("${app.switchboard.engine.Toolbox.kindOf(p).name.lowercase()} · ${app.switchboard.engine.Toolbox.commandFor(p, mode).lineSequence().first().take(70)}", maxLines = 1)
                    when {
                        busy != null -> Text(busy, color = MaterialTheme.colorScheme.primary)
                        !app.switchboard.engine.Toolbox.availableOn(p, mode) -> Text("Not available on $machine" +
                            if (mode == app.switchboard.data.RunnerMode.TERMUX) " (computers only). Delete it, or switch Tools to Computer to use it." else ".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        st == null -> Text("Not installed on $machine")
                        st.ok -> Text("✓ Installed${if (st.location.isNotBlank()) " at ${st.location}" else ""}", color = MaterialTheme.colorScheme.primary)
                        else -> Text("✗ ${app.switchboard.engine.Toolbox.summary(st.log).take(160).ifBlank { "Failed" }}", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            trailingContent = { RowActions({ editing = p }, { deleting = p }, p.name) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 16.dp)) {
            if (app.switchboard.engine.Toolbox.availableOn(p, mode))
                FilledTonalButton(enabled = busy == null, onClick = { vm.installPackage(p.id) }) { Text(if (st?.ok == true) "Update" else "Install") }
            if (st != null && st.log.isNotBlank()) TextButton(onClick = { openLog = if (openLog == p.id) null else p.id }) { Text(if (openLog == p.id) "Hide log" else "Log") }
            if (st?.ok == true && app.switchboard.engine.Toolbox.kindOf(p) in setOf(app.switchboard.data.PackageKind.GIT, app.switchboard.data.PackageKind.URL))
                TextButton(onClick = { vm.deletePackageFiles(p.id) }) { Text("Delete files") }
        }
        if (openLog == p.id) Text(st?.log.orEmpty(), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier.padding(start = 16.dp))
    }
    // Every built-in tool, always visible; ones already in the toolbox are listed above.
    val more = catalog.filter { it.name !in added }
    if (more.isNotEmpty()) {
        HorizontalDivider()
        Text("Common tools", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        more.forEach { p ->
            val ok = app.switchboard.engine.Toolbox.availableOn(p, mode)
            ListItem(
                headlineContent = { Text(p.name) },
                supportingContent = { Text(if (ok) p.description else "${p.description}\nNeeds ${if (p.scripts.keys == setOf(app.switchboard.data.RunnerMode.TERMUX.name)) "Termux" else "Termux or a computer"}") },
                trailingContent = {
                    TextButton(enabled = ok, onClick = { vm.upsertPackage(p); vm.installPackage(p.id) }) { Text("Add & install") }
                },
            )
        }
    }
    if (adding) PresetPicker(vm, onDismiss = { adding = false })
    editing?.let { p -> PackageDialog(p, s.packages.none { it.id == p.id }, onSave = { vm.upsertPackage(it); editing = null; vm.installPackage(it.id) }, onDismiss = { editing = null }) }
    deleting?.let { p -> ConfirmDelete("${p.name} from the toolbox (installed files stay unless you tap Delete files first)", { vm.removePackage(p.id) }) { deleting = null } }
}

@Composable
private fun PresetPicker(vm: ChatViewModel, onDismiss: () -> Unit) {
    val existing = vm.settings.packages.map { it.name }.toSet()
    val presets = remember(vm.settings.runnerMode) { app.switchboard.engine.Toolbox.presets(vm.settings.runnerMode).filter { it.name !in existing } }
    var picked by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add common tools") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (presets.isEmpty()) Hint("Everything available for this machine is already in your toolbox.")
                presets.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = p.name in picked, onCheckedChange = { picked = if (it) picked + p.name else picked - p.name })
                        Column(Modifier.weight(1f)) { Text(p.name); Hint(p.description) }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = picked.isNotEmpty(), onClick = {
            presets.filter { it.name in picked }.forEach { vm.upsertPackage(it); vm.installPackage(it.id) }
            onDismiss()
        }) { Text("Add and install") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun PackageDialog(initial: app.switchboard.data.ToolPackage, isNew: Boolean, onSave: (app.switchboard.data.ToolPackage) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var source by remember { mutableStateOf(initial.source) }
    var kind by remember { mutableStateOf(initial.kind) }
    var desc by remember { mutableStateOf(initial.description) }
    var token by remember { mutableStateOf(initial.token) }
    val detected = app.switchboard.engine.Toolbox.detect(source)
    EditDialog(if (isNew) "Add a tool" else "Edit ${initial.name}", name.isNotBlank() && source.isNotBlank(),
        onSave = { onSave(initial.copy(name = name.trim(), source = source.trim(), kind = kind, description = desc.trim(), token = token.trim(),
            // custom edits replace any preset commands
            scripts = if (source.trim() == initial.source) initial.scripts else emptyMap(), checks = if (source.trim() == initial.source) initial.checks else emptyMap())) },
        onDismiss = onDismiss) {
        Field(name, { name = it }, "Name", placeholder = "e.g. My firmware repo")
        Field(source, { source = it }, "Source address", minLines = 2, placeholder = "https://github.com/user/repo, https://…/file.zip, ffmpeg, pip:requests, npm:typescript, or a shell script")
        Picker("Type", listOf(app.switchboard.data.PackageKind.AUTO to "Automatic (${detected.name.lowercase()})",
            app.switchboard.data.PackageKind.GIT to "Git repository", app.switchboard.data.PackageKind.URL to "Download link (archives are unpacked)",
            app.switchboard.data.PackageKind.PACKAGE to "System package (pkg / apt / brew)", app.switchboard.data.PackageKind.PIP to "Python package (pip)",
            app.switchboard.data.PackageKind.NPM to "Node package (npm)", app.switchboard.data.PackageKind.SCRIPT to "Install script (shell commands)"), kind) { kind = it }
        Field(desc, { desc = it }, "What it is (shown to the model, optional)")
        if ((if (kind == app.switchboard.data.PackageKind.AUTO) detected else kind) in setOf(app.switchboard.data.PackageKind.GIT, app.switchboard.data.PackageKind.URL))
            Field(token, { token = it }, "Access token for private sources (optional)", secret = true)
    }
}
