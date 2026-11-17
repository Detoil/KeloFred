package com.kelofred.keloray.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kelofred.keloray.BuildConfig
import com.kelofred.keloray.core.Solar
import com.kelofred.keloray.data.Mode
import com.kelofred.keloray.data.REGION_LABELS
import com.kelofred.keloray.ui.AppViewModel
import com.kelofred.keloray.ui.SectionCard
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    val context = LocalContext.current
    var lat by remember { mutableStateOf(s.homeLat?.toString() ?: "") }
    var lon by remember { mutableStateOf(s.homeLon?.toString() ?: "") }
    var utc by remember { mutableStateOf(s.homeUtc?.toString() ?: "") }
    var interval by remember { mutableStateOf(s.minIntervalMs.toString()) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    // re-read battery / notification status when returning from system settings
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Settings") },
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
            SectionCard("Connection") {
                if (s.mode == Mode.SERVER) {
                    Text("keloray-control server", style = MaterialTheme.typography.bodyLarge)
                    Text(s.serverUrl, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Gizwits cloud · ${REGION_LABELS[s.region] ?: s.region}", style = MaterialTheme.typography.bodyLarge)
                    Text("Signed in as ${s.username}", style = MaterialTheme.typography.bodySmall)
                    Text("App-Id ${s.appId}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { confirmSignOut = true }) {
                    Text(if (s.mode == Mode.SERVER) "Change connection" else "Sign out / change connection")
                }
            }

            SectionCard(
                "Home location",
                subtitle = "Prefills latitude, longitude and UTC offset for the tropics and reef_day sun trackers. Leave blank to use the scene defaults (~Singapore).",
            ) {
                Row {
                    OutlinedTextField(
                        lat, { lat = it }, label = { Text("Latitude") }, singleLine = true,
                        placeholder = { Text(Solar.DEFAULT_LAT.toString()) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        lon, { lon = it }, label = { Text("Longitude") }, singleLine = true,
                        placeholder = { Text(Solar.DEFAULT_LON.toString()) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row {
                    OutlinedTextField(
                        utc, { utc = it }, label = { Text("UTC offset (hours)") }, singleLine = true,
                        placeholder = { Text(Solar.DEFAULT_UTC.toString()) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val secs = ZonedDateTime.now(ZoneId.systemDefault()).offset.totalSeconds
                        utc = (secs / 3600.0).toString()
                    }) { Text("Use phone's") }
                }
                Text(
                    "The trackers follow your phone's clock, so a reef can mirror a far-away sun at your local hours: e.g. Singapore's sun shape while you live elsewhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = {
                    val la = lat.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                    val lo = lon.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                    val u = utc.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                    if ((lat.isNotBlank() && la == null) || (lon.isNotBlank() && lo == null) || (utc.isNotBlank() && u == null)) {
                        vm.say("Numbers only, e.g. -33.92")
                    } else {
                        vm.updateSettings { it.copy(homeLat = la, homeLon = lo, homeUtc = u) }
                        vm.say("Home location saved")
                    }
                }) { Text("Save location") }
            }

            if (s.mode == Mode.DIRECT) {
                key(refresh) {
                    SectionCard("Running scenes on this phone", subtitle = "Scenes run in a foreground service with a notification.") {
                        OutlinedTextField(
                            interval, { interval = it.filter(Char::isDigit) },
                            label = { Text("Minimum gap between cloud writes (ms)") },
                            supportingText = { Text("Raise it if you see rate-limit errors. Default 250.") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FilledTonalButton(onClick = {
                            val ms = interval.toLongOrNull()?.coerceIn(50, 10_000) ?: 250
                            interval = ms.toString()
                            vm.updateSettings { it.copy(minIntervalMs = ms) }
                            vm.say("Saved")
                        }) { Text("Save") }

                        val pm = context.getSystemService(PowerManager::class.java)
                        val unrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)
                        Text(
                            if (unrestricted) {
                                "Battery: unrestricted. Long scenes keep running with the screen off."
                            } else {
                                "Battery: optimized. Android may pause long scenes (tropics, reef_day) while the screen is off. Allow unrestricted battery use for KeloFred to prevent that."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!unrestricted) {
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }) { Text("Open battery settings") }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            Text("Notifications are off, so you won't see which scenes are running.", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                Text("Allow notifications")
                            }
                        }
                        Text(
                            "For all-day reef lighting, a keloray-control server on an always-on machine (e.g. a Raspberry Pi) is more reliable than a phone. Switch with \"Change connection\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionCard("About") {
                Text("KeloFred ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "An unofficial client for Keloray / Gizwits lights. It controls your own devices on your own account through the same cloud API the official app uses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Change connection?") },
            text = {
                Text(
                    if (s.mode == Mode.DIRECT) {
                        "Running scenes on this phone stop and schedules are paused until you sign in again. Saved schedules and channel maps are kept."
                    } else {
                        "You can reconnect to the server or sign in to the cloud instead."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    vm.signOut()
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}
