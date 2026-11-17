package com.kelofred.keloray.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kelofred.keloray.data.Device
import com.kelofred.keloray.data.Mode
import com.kelofred.keloray.ui.AppViewModel
import com.kelofred.keloray.ui.EmptyState
import com.kelofred.keloray.ui.ErrorPanel
import com.kelofred.keloray.ui.Load
import com.kelofred.keloray.ui.StatusDot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    vm: AppViewModel,
    snackbar: SnackbarHostState,
    onOpen: (String) -> Unit,
    onSchedules: () -> Unit,
    onSettings: () -> Unit,
) {
    val devices by vm.devices.collectAsState()
    val running by vm.running.collectAsState()
    val settings by vm.settings.collectAsState()

    LaunchedEffect(Unit) {
        if (devices is Load.Idle) vm.refreshDevices()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("KeloFred")
                        Text(
                            if (settings.mode == Mode.SERVER) "Server · ${settings.serverUrl}" else "Cloud · ${settings.region.uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSchedules) { Icon(Icons.Filled.DateRange, contentDescription = "Schedules") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = devices is Load.Loading,
            onRefresh = { vm.refreshDevices() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val d = devices) {
                is Load.Error -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    item { ErrorPanel(d.message, onRetry = { vm.refreshDevices() }) }
                }
                is Load.Ok -> if (d.value.isEmpty()) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            EmptyState(
                                "No lights yet",
                                "No devices are bound to this account. Add them in the Keloray app first, then pull to refresh.",
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(d.value, key = { it.did }) { device ->
                            DeviceCard(
                                device = device,
                                sceneName = running[device.did]?.scene,
                                onOpen = { onOpen(device.did) },
                                onPower = { vm.power(device.did, it) },
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun DeviceCard(device: Device, sceneName: String?, onOpen: () -> Unit, onPower: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(device.isOnline)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(device.productName, if (device.isOnline) "online" else "offline")
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = { onPower(true) }) { Text("On") }
                OutlinedButton(onClick = { onPower(false) }) { Text("Off") }
                Spacer(Modifier.weight(1f))
                if (sceneName != null) {
                    AssistChip(onClick = onOpen, label = { Text(sceneName) })
                }
            }
        }
    }
}
