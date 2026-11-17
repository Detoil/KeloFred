package com.kelofred.keloray.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.kelofred.keloray.core.JsonUtil
import com.kelofred.keloray.data.Device
import com.kelofred.keloray.data.Mode
import com.kelofred.keloray.data.Rule
import com.kelofred.keloray.data.SceneInfo
import com.kelofred.keloray.data.Settings
import com.kelofred.keloray.engine.RuleTime
import com.kelofred.keloray.engine.ScheduleAlarms
import com.kelofred.keloray.ui.AppViewModel
import com.kelofred.keloray.ui.DropdownField
import com.kelofred.keloray.ui.EmptyState
import com.kelofred.keloray.ui.ErrorPanel
import com.kelofred.keloray.ui.Load
import com.kelofred.keloray.ui.valueOrNull
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(vm: AppViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val load by vm.schedules.collectAsState()
    val devices = vm.devices.collectAsState().value.valueOrNull.orEmpty()
    val scenes by vm.scenes.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Rule?>(null) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.loadSchedules()
        if (scenes.isEmpty()) vm.loadScenes()
        if (devices.isEmpty()) vm.refreshDevices()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Schedules") },
                actions = { IconButton(onClick = { vm.loadSchedules() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val note = if (settings.mode == Mode.SERVER) {
                    "These run on your keloray-control server. Rules added here last until the server restarts; keep permanent ones under `rules:` in config.yaml."
                } else {
                    "These run on this phone using exact alarms, even when the app is closed. The phone has to be on and online at the scheduled time."
                }
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (settings.mode == Mode.DIRECT && !ScheduleAlarms.canExact(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    ErrorPanel(
                        "Exact alarms are off, so schedules may fire late.",
                        actionLabel = "Allow",
                        onRetry = {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()),
                            )
                        },
                    )
                }
            }
            when (val l = load) {
                is Load.Error -> item { ErrorPanel(l.message, onRetry = { vm.loadSchedules() }) }
                is Load.Ok -> if (l.value.isEmpty()) {
                    item { EmptyState("No schedules", "Tap New to run a scene at a set time, e.g. a sunrise at 06:30 on weekdays.") }
                } else {
                    items(l.value, key = { it.name }) { rule ->
                        RuleCard(
                            rule,
                            deviceName = devices.firstOrNull { it.did == rule.did }?.displayName ?: rule.did,
                            onToggle = { vm.saveSchedule(rule.copy(enabled = it), if (it) "Enabled" else "Disabled") },
                            onDelete = { vm.deleteSchedule(rule.name) },
                            onEdit = { editing = rule },
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    if (creating || editing != null) {
        RuleEditor(
            initial = editing,
            devices = devices,
            scenes = scenes,
            settings = settings,
            onDismiss = { creating = false; editing = null },
            onSave = { rule ->
                val old = editing
                creating = false
                editing = null
                if (old != null && old.name != rule.name) vm.deleteSchedule(old.name)
                vm.saveSchedule(rule)
            },
        )
    }
}

@Composable
private fun RuleCard(rule: Rule, deviceName: String, onToggle: (Boolean) -> Unit, onDelete: () -> Unit, onEdit: () -> Unit) {
    val time = RuleTime.of(rule)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = time != null, onClick = onEdit),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    time?.describe() ?: "cron ${JsonUtil.stringify(rule.cron)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("${rule.scene} on $deviceName", style = MaterialTheme.typography.bodySmall)
                if (rule.params.isNotEmpty()) {
                    Text(
                        JsonUtil.stringify(rule.params),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
// internal (not private) so RuleEditorTest can render it on its own
internal fun RuleEditor(
    initial: Rule?,
    devices: List<Device>,
    scenes: List<SceneInfo>,
    settings: Settings,
    onDismiss: () -> Unit,
    onSave: (Rule) -> Unit,
) {
    val t0 = initial?.let { RuleTime.of(it) } ?: RuleTime(6, 30)
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var did by remember { mutableStateOf(initial?.did ?: devices.firstOrNull()?.did) }
    var scene by remember { mutableStateOf(initial?.scene ?: "sunrise") }
    var params by remember { mutableStateOf(initial?.params?.let { JsonUtil.stringify(it) } ?: "{}") }
    var days by remember { mutableStateOf(t0.days) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var pickTime by remember { mutableStateOf(false) }
    val timeState = rememberTimePickerState(t0.hour, t0.minute, is24Hour = true)
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New schedule" else "Edit schedule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DropdownField("Light", devices.map { it.did to it.displayName }, did, { did = it }, Modifier.fillMaxWidth())
                DropdownField(
                    "Scene", scenes.map { it.name to it.name }, scene,
                    {
                        scene = it
                        val info = scenes.firstOrNull { s -> s.name == it }
                        if (info?.locationAware == true && settings.homeLat != null && params.trim() == "{}") {
                            params = JsonUtil.stringify(
                                listOfNotNull(
                                    "latitude" to settings.homeLat,
                                    settings.homeLon?.let { v -> "longitude" to v },
                                    settings.homeUtc?.let { v -> "utc_offset" to v },
                                ).toMap(),
                            )
                        }
                    },
                    Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    params, { params = it; error = null },
                    label = { Text("Scene options (JSON)") },
                    supportingText = { Text("{} uses the scene's defaults, e.g. {\"seconds\": 1200}") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Time  %02d:%02d".format(timeState.hour, timeState.minute))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(
                            selected = d in days,
                            onClick = { days = if (d in days) days - d else days + d },
                            label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", Modifier.weight(1f))
                    Switch(enabled, { enabled = it })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = runCatching { JsonUtil.parseObject(params.ifBlank { "{}" }) }.getOrNull()
                when {
                    name.isBlank() -> error = "Give it a name"
                    did == null -> error = "Pick a light"
                    days.isEmpty() -> error = "Pick at least one day"
                    p == null -> error = "Options must be a JSON object"
                    else -> {
                        val (at, cron) = RuleTime(timeState.hour, timeState.minute, days).toRuleFields()
                        onSave(Rule(name.trim(), did!!, scene, p, cron, at, enabled))
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickTime) {
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { pickTime = false }) { Text("OK") } },
            text = { TimePicker(state = timeState) },
        )
    }
}
