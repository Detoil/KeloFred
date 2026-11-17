package com.kelofred.keloray.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kelofred.keloray.core.CHANNEL_COLORS
import com.kelofred.keloray.core.CHANNEL_LABELS
import com.kelofred.keloray.core.CORAL_CHANNELS
import com.kelofred.keloray.core.ChannelMap
import com.kelofred.keloray.core.DatapointInfo
import com.kelofred.keloray.core.JsonUtil
import com.kelofred.keloray.core.PARAM_HINTS
import com.kelofred.keloray.core.SceneKind
import com.kelofred.keloray.core.asDouble
import com.kelofred.keloray.core.asMap
import com.kelofred.keloray.core.coerceValue
import com.kelofred.keloray.core.formatValue
import com.kelofred.keloray.data.DeviceState
import com.kelofred.keloray.data.Mode
import com.kelofred.keloray.data.SceneInfo
import com.kelofred.keloray.data.Settings
import com.kelofred.keloray.ui.AppViewModel
import com.kelofred.keloray.ui.ErrorPanel
import com.kelofred.keloray.ui.HueGradient
import com.kelofred.keloray.ui.LevelSlider
import com.kelofred.keloray.ui.Load
import com.kelofred.keloray.ui.SectionCard
import com.kelofred.keloray.ui.StatusDot
import com.kelofred.keloray.ui.WarmCoolGradient
import com.kelofred.keloray.ui.hsvColor
import com.kelofred.keloray.ui.valueOrNull
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    vm: AppViewModel,
    did: String,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onDiscover: () -> Unit,
    onChannelMap: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    val states by vm.states.collectAsState()
    val running by vm.running.collectAsState()
    val scenes by vm.scenes.collectAsState()
    val maps by vm.channelMaps.collectAsState()
    val device = vm.device(did)
    val stateLoad = states[did] ?: Load.Idle
    val state = stateLoad.valueOrNull
    val direct = settings.mode == Mode.DIRECT
    val channelMap = if (direct) maps[did] ?: ChannelMap() else ChannelMap()

    var menuOpen by remember { mutableStateOf(false) }
    var rawOpen by remember { mutableStateOf(false) }
    var sheetScene by remember { mutableStateOf<SceneInfo?>(null) }

    LaunchedEffect(did) {
        vm.loadState(did)
        if (scenes.isEmpty()) vm.loadScenes()
    }

    val coral = remember(state, channelMap, device) { likelyCoral(device?.productName, device?.alias, state, channelMap) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        device?.let { StatusDot(it.isOnline); Spacer(Modifier.width(8.dp)) }
                        Text(device?.displayName ?: did, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.loadState(did) }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Datapoints (discover)") }, onClick = { menuOpen = false; onDiscover() })
                            if (direct) {
                                DropdownMenuItem(text = { Text("Coral channel map") }, onClick = { menuOpen = false; onChannelMap() })
                            }
                            DropdownMenuItem(text = { Text("Raw datapoint write") }, onClick = { menuOpen = false; rawOpen = true })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (stateLoad is Load.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (stateLoad is Load.Error) ErrorPanel(stateLoad.message, onRetry = { vm.loadState(did) })

            // running scene banner
            val local = running[did]
            val remoteRunning = !direct && state?.sceneRunning == true
            if (local != null || remoteRunning) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                local?.let { "Running ${it.scene}" } ?: "A scene is running on the server",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            local?.lastError?.let {
                                Text("Retrying: $it", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                        }
                        FilledTonalButton(onClick = { vm.stopScene(did) }) { Text("Stop") }
                    }
                }
            }

            SectionCard("Power") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.power(did, true) }) { Text("On") }
                    OutlinedButton(onClick = { vm.power(did, false) }) { Text("Off") }
                    Spacer(Modifier.weight(1f))
                    state?.attrs?.get("onOff")?.let { v ->
                        val on = v == true || v.asDouble()?.let { it != 0.0 } == true
                        Text(if (on) "Currently on" else "Currently off", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (coral) {
                SpectralCard(did, vm, state, channelMap, direct, onChannelMap)
                LightCards(did, vm, state, collapsed = true)
            } else {
                LightCards(did, vm, state, collapsed = false)
                SpectralCard(did, vm, state, channelMap, direct, onChannelMap, startCollapsed = true)
            }

            ScenesCard(scenes, coral) { sheetScene = it }

            state?.let { CurrentValues(it) }
            Spacer(Modifier.height(24.dp))
        }
    }

    sheetScene?.let { s ->
        SceneSheet(s, settings, onDismiss = { sheetScene = null }) { params ->
            sheetScene = null
            vm.startScene(did, s.name, params)
        }
    }

    if (rawOpen) {
        RawWriteDialog(onDismiss = { rawOpen = false }) { attrs ->
            rawOpen = false
            vm.control(did, attrs, "Sent")
        }
    }
}

private fun likelyCoral(productName: String?, alias: String?, state: DeviceState?, map: ChannelMap): Boolean {
    if (map.configured()) return true
    val name = "${productName.orEmpty()} ${alias.orEmpty()}".lowercase()
    if (listOf("coral", "reef", "marine", "aquarium", "al100", "al-").any { name.contains(it) }) return true
    val numeric = state?.attrs.orEmpty().filter { it.value.asDouble() != null && it.value !is Boolean }.keys
    val guessed = ChannelMap.autoMap(numeric.map { DatapointInfo(it, null, null, "uint8", null, null, emptyMap()) })
    return guessed.size >= 3
}

@Composable
private fun Collapsible(title: String, subtitle: String?, startCollapsed: Boolean, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(!startCollapsed) }
    SectionCard(
        title,
        subtitle = subtitle,
        action = {
            IconButton(onClick = { open = !open }) {
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand")
            }
        },
    ) {
        AnimatedVisibility(open) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
    }
}

@Composable
private fun LightCards(did: String, vm: AppViewModel, state: DeviceState?, collapsed: Boolean) {
    val attrs = state?.attrs.orEmpty()
    val lum = attrs["lum"].asDouble()?.toInt() ?: 80
    val temp = attrs["temperature"].asDouble()?.toInt() ?: 50
    val hsv = attrs["hsv"].asMap()
    var hue by remember(hsv) { mutableStateOf(hsv?.get("h").asDouble()?.toInt() ?: 30) }
    var sat by remember(hsv) { mutableStateOf(hsv?.get("s").asDouble()?.toInt() ?: 100) }
    var bright by remember(lum) { mutableStateOf(lum) }

    Collapsible("White light", "Brightness and colour temperature", collapsed) {
        LevelSlider("Brightness", lum, range = 1..100, onCommit = {
            bright = it
            vm.control(did, mapOf("onOff" to 1, "lum" to it))
        })
        LevelSlider(
            "Colour temperature", temp, gradient = WarmCoolGradient,
            valueText = { if (it < 34) "warm $it" else if (it > 66) "cool $it" else "$it" },
            onCommit = { vm.control(did, mapOf("onOff" to 1, "temperature" to it)) },
        )
    }
    Collapsible("Colour", "Hue and saturation (uses the brightness above)", true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(hsvColor(hue, sat, 100)),
            )
            Spacer(Modifier.width(12.dp))
            Text("h $hue · s $sat · v $bright", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        LevelSlider("Hue", hue, range = 0..360, gradient = HueGradient, onCommit = {
            hue = it
            vm.control(did, mapOf("onOff" to 1, "hsv" to mapOf("h" to it, "s" to sat, "v" to bright)))
        })
        LevelSlider(
            "Saturation", sat,
            gradient = listOf(Color.White, hsvColor(hue, 100, 100)),
            onCommit = {
                sat = it
                vm.control(did, mapOf("onOff" to 1, "hsv" to mapOf("h" to hue, "s" to it, "v" to bright)))
            },
        )
    }
}

@Composable
private fun SpectralCard(
    did: String,
    vm: AppViewModel,
    state: DeviceState?,
    map: ChannelMap,
    direct: Boolean,
    onChannelMap: () -> Unit,
    startCollapsed: Boolean = false,
) {
    val attrs = state?.attrs.orEmpty()
    val levels = remember(did) { mutableStateMapOf<String, Int>() }
    LaunchedEffect(attrs, map) {
        for (ch in CORAL_CHANNELS) {
            val spec = map.specs.getValue(ch)
            val raw = attrs[spec.name].asDouble() ?: continue
            val span = spec.hi - spec.lo
            levels[ch] = if (span == 0.0) 0 else ((raw - spec.lo) / span * 100).roundToInt().coerceIn(0, 100)
        }
    }
    fun send() = vm.control(did, mapOf("onOff" to 1, "channels" to CORAL_CHANNELS.associateWith { levels[it] ?: 0 }))

    Collapsible("Coral spectrum", "Seven LED channels, 0-100 each", startCollapsed) {
        if (direct && !map.configured()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Channels aren't mapped to this fixture's datapoints yet, so these sliders send the logical names (cw, rb, ...) as-is.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onChannelMap) { Text("Map channels") }
                }
            }
        }
        if (!direct) {
            Text(
                "Channel names are mapped by the server's config.yaml `channels:` block.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (ch in CORAL_CHANNELS) {
            val c = Color(CHANNEL_COLORS.getValue(ch))
            LevelSlider(
                CHANNEL_LABELS.getValue(ch) + if (direct && map.configured()) "  (${map.specs.getValue(ch).name})" else "",
                levels[ch] ?: 0,
                gradient = listOf(c.copy(alpha = 0.15f), c),
                onCommit = {
                    levels[ch] = it
                    send()
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                CORAL_CHANNELS.forEach { levels[it] = 0 }
                send()
            }) { Text("All 0") }
            OutlinedButton(onClick = {
                // a gentle blue-heavy "actinic" look for viewing corals
                mapOf("cw" to 10, "rb" to 70, "sb" to 40, "uv1" to 30, "uv2" to 30, "dr" to 0, "g" to 5)
                    .forEach { (k, v) -> levels[k] = v }
                send()
            }) { Text("Actinic") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenesCard(scenes: List<SceneInfo>, coral: Boolean, onPick: (SceneInfo) -> Unit) {
    SectionCard("Scenes", subtitle = "Tap one to adjust its options and run it") {
        if (scenes.isEmpty()) {
            Text("Loading scenes…", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        val groups = scenes.groupBy { it.kind }
        val order = if (coral) listOf(SceneKind.SPECTRAL, SceneKind.LIGHT) else listOf(SceneKind.LIGHT, SceneKind.SPECTRAL)
        for (kind in order) {
            val list = groups[kind].orEmpty()
            if (list.isEmpty()) continue
            Text(
                if (kind == SceneKind.SPECTRAL) "Coral fixtures" else "RGB / white bulbs",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { s ->
                    AssistChip(
                        onClick = { onPick(s) },
                        label = { Text(s.name) },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SceneSheet(scene: SceneInfo, settings: Settings, onDismiss: () -> Unit, onRun: (Map<String, Any?>) -> Unit) {
    val keys = (scene.defaults.keys + scene.optional).distinct()
    val initial = remember(scene) {
        keys.associateWith { k ->
            val home = when (k) {
                "latitude" -> settings.homeLat
                "longitude" -> settings.homeLon
                "utc_offset" -> settings.homeUtc
                else -> null
            }
            formatValue(if (scene.locationAware && home != null) home else scene.defaults[k])
        }
    }
    val values = remember(scene) { mutableStateMapOf<String, String>().apply { putAll(initial) } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(scene.name, style = MaterialTheme.typography.headlineSmall)
            Text(scene.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (scene.locationAware && settings.homeLat == null) {
                Text(
                    "Tip: set a home location in Settings to prefill latitude/longitude here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            HorizontalDivider()
            for (k in keys) {
                val default = scene.defaults[k]
                if (default is Boolean) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(k, style = MaterialTheme.typography.bodyLarge)
                            PARAM_HINTS[k]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Switch(
                            checked = values[k]?.lowercase() == "true",
                            onCheckedChange = { values[k] = it.toString() },
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = values[k].orEmpty(),
                        onValueChange = { values[k] = it },
                        label = { Text(k) },
                        supportingText = PARAM_HINTS[k]?.let { { Text(it) } },
                        placeholder = if (default == null) ({ Text("optional") }) else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { values.clear(); values.putAll(initial) }) { Text("Reset") }
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    val params = LinkedHashMap<String, Any?>()
                    for (k in keys) {
                        val text = values[k].orEmpty().trim()
                        if (text.isNotEmpty()) params[k] = coerceValue(text)
                    }
                    onRun(params)
                }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Run")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CurrentValues(state: DeviceState) {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        "Current values",
        subtitle = "${state.attrs.size} datapoints reported by the device",
        action = {
            IconButton(onClick = { open = !open }) {
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
        },
    ) {
        AnimatedVisibility(open) {
            Column {
                state.attrs.toSortedMap().forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(k, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Text(
                            if (v is Map<*, *> || v is List<*>) JsonUtil.stringify(v) else v.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RawWriteDialog(onDismiss: () -> Unit, onSend: (Map<String, Any?>) -> Unit) {
    var text by rememberSaveable { mutableStateOf("{\"onOff\": 1, \"lum\": 80}") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raw datapoint write") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Sent as {\"attrs\": …}. Canonical keys (onOff, lum, temperature, hsv) are mapped to the product's real datapoints; a \"channels\" object is expanded with the channel map.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { JsonUtil.parseObject(text) }
                    .onSuccess(onSend)
                    .onFailure { error = "Not a JSON object: ${it.message}" }
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
