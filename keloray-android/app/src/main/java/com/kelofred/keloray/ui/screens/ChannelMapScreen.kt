package com.kelofred.keloray.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kelofred.keloray.core.CHANNEL_COLORS
import com.kelofred.keloray.core.CHANNEL_LABELS
import com.kelofred.keloray.core.CORAL_CHANNELS
import com.kelofred.keloray.core.ChannelMap
import com.kelofred.keloray.core.ChannelSpec
import com.kelofred.keloray.core.SchemaNormalizer
import com.kelofred.keloray.ui.AppViewModel
import com.kelofred.keloray.ui.DropdownField
import com.kelofred.keloray.ui.ErrorPanel
import com.kelofred.keloray.ui.Load
import com.kelofred.keloray.ui.SectionCard
import com.kelofred.keloray.ui.valueOrNull

private data class Row3(val name: String, val lo: String, val hi: String)

/**
 * Maps the seven logical coral channels to the fixture's real datapoints:
 * the in-app replacement for hand-editing the `channels:` block of
 * config.yaml. "Solo" lights one channel so you can see which LED it drives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelMapScreen(vm: AppViewModel, did: String, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val all by vm.discovery.collectAsState()
    val maps by vm.channelMaps.collectAsState()
    val load = all[did] ?: Load.Idle
    val datapoints = load.valueOrNull?.let { SchemaNormalizer.datapoints(it.datapoints) }.orEmpty()
    val current = maps[did] ?: ChannelMap()

    val rows = remember(did) {
        mutableStateMapOf<String, Row3>().apply {
            CORAL_CHANNELS.forEach { ch ->
                val s = current.specs.getValue(ch)
                put(ch, Row3(s.name, fmt(s.lo), fmt(s.hi)))
            }
        }
    }

    LaunchedEffect(did) { if (load !is Load.Ok) vm.discover(did) }

    fun specs(): Map<String, ChannelSpec> = CORAL_CHANNELS.associateWith { ch ->
        val r = rows.getValue(ch)
        ChannelSpec(r.name.ifBlank { ch }, r.lo.toDoubleOrNull() ?: 0.0, r.hi.toDoubleOrNull() ?: 100.0)
    }

    fun solo(ch: String) {
        val map = ChannelMap(specs())
        val attrs = linkedMapOf<String, Any?>("onOff" to 1)
        attrs.putAll(map.toAttrs(CORAL_CHANNELS.associateWith { if (it == ch) 60 else 0 }))
        // send already-expanded datapoints so the unsaved map is what gets tested
        vm.control(did, attrs, "Showing ${CHANNEL_LABELS[ch]} only")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Coral channel map") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Pick the real datapoint behind each LED channel. The range is the datapoint's raw min..max; app levels 0-100 are scaled onto it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (load) {
                is Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is Load.Error -> ErrorPanel("Couldn't read the schema: ${load.message}", onRetry = { vm.discover(did) })
                else -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    enabled = datapoints.isNotEmpty(),
                    onClick = {
                        val guess = ChannelMap.autoMap(datapoints)
                        guess.forEach { (ch, s) -> rows[ch] = Row3(s.name, fmt(s.lo), fmt(s.hi)) }
                        vm.say(if (guess.isEmpty()) "No channel names recognised; pick them by hand" else "Guessed ${guess.size} of 7; check each with Solo")
                    },
                ) { Text("Auto-detect") }
                OutlinedButton(onClick = {
                    CORAL_CHANNELS.forEach { rows[it] = Row3(it, "0", "100") }
                    vm.clearChannelMap(did)
                }) { Text("Reset") }
            }

            val options = datapoints.map { it.name to (it.name + (it.range?.let { r -> "  ${fmt(r.first)}..${fmt(r.second)}" } ?: "")) }
            for (ch in CORAL_CHANNELS) {
                val r = rows.getValue(ch)
                SectionCard(CHANNEL_LABELS.getValue(ch), subtitle = "logical name: $ch", action = {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color(CHANNEL_COLORS.getValue(ch)))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                }) {
                    if (options.isNotEmpty()) {
                        DropdownField(
                            label = "Datapoint",
                            options = options,
                            selected = r.name,
                            onSelect = { name ->
                                val range = datapoints.firstOrNull { it.name == name }?.range
                                rows[ch] = Row3(name, range?.let { fmt(it.first) } ?: r.lo, range?.let { fmt(it.second) } ?: r.hi)
                            },
                        )
                    } else {
                        OutlinedTextField(
                            value = r.name,
                            onValueChange = { rows[ch] = r.copy(name = it.trim()) },
                            label = { Text("Datapoint name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = r.lo,
                            onValueChange = { rows[ch] = r.copy(lo = it) },
                            label = { Text("min") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = r.hi,
                            onValueChange = { rows[ch] = r.copy(hi = it) },
                            label = { Text("max") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { solo(ch) }) { Text("Solo") }
                    }
                }
            }
            Button(onClick = { vm.saveChannelMap(did, specs()) }, modifier = Modifier.fillMaxWidth()) { Text("Save channel map") }
        }
    }
}

private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
