package com.kelofred.keloray.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kelofred.keloray.data.Mode
import com.kelofred.keloray.data.REGIONS
import com.kelofred.keloray.data.REGION_LABELS
import com.kelofred.keloray.ui.AppViewModel
import com.kelofred.keloray.ui.ErrorPanel
import com.kelofred.keloray.ui.SectionCard
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val busy by vm.busy.collectAsState()
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf(settings.mode ?: Mode.DIRECT) }
    var region by rememberSaveable { mutableStateOf(settings.region) }
    var appId by rememberSaveable { mutableStateOf(settings.appId) }
    var username by rememberSaveable { mutableStateOf(settings.username) }
    var password by rememberSaveable { mutableStateOf("") }
    var serverUrl by rememberSaveable { mutableStateOf(settings.serverUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<List<String>?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("KeloFred", style = MaterialTheme.typography.displaySmall)
        Text(
            "Scenes, coral spectrum control and schedules for your Keloray lights.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == Mode.DIRECT,
                onClick = { mode = Mode.DIRECT; error = null },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Cloud (direct)") }
            SegmentedButton(
                selected = mode == Mode.SERVER,
                onClick = { mode = Mode.SERVER; error = null },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("My server") }
        }

        if (mode == Mode.DIRECT) {
            SectionCard(
                "Gizwits cloud",
                subtitle = "The phone talks to the same cloud the Keloray app uses. Scenes run on the phone.",
            ) {
                Text("Region", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    REGIONS.keys.forEachIndexed { i, r ->
                        SegmentedButton(
                            selected = region == r,
                            onClick = { region = r },
                            shape = SegmentedButtonDefaults.itemShape(i, REGIONS.size),
                        ) { Text(REGION_LABELS[r] ?: r) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = appId,
                        onValueChange = { appId = it.trim() },
                        label = { Text("App-Id") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(
                        enabled = !probing,
                        onClick = {
                            probing = true
                            error = null
                            scope.launch {
                                try {
                                    val (r, ids) = vm.probe(region)
                                    probeResult = ids
                                    if (ids.isNotEmpty()) {
                                        region = r
                                        appId = ids.first()
                                    } else {
                                        error = "No App-Id answered in any region. Check your internet connection."
                                    }
                                } catch (e: Exception) {
                                    error = vm.friendly(e)
                                } finally {
                                    probing = false
                                }
                            }
                        },
                    ) {
                        if (probing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Find")
                    }
                }
                probeResult?.takeIf { it.size > 1 }?.let { ids ->
                    Text("Several App-Ids answered; pick one if sign-in fails:", style = MaterialTheme.typography.bodySmall)
                    ids.forEach { id ->
                        FilterChip(selected = appId == id, onClick = { appId = id }, label = { Text(id.take(12) + "…") })
                    }
                }
                Text(
                    "Tap Find to test the App-Ids built into the Keloray app and fill in the one that works.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Keloray app username (email or phone)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Your password is encrypted with a key in the Android Keystore and only used to sign in again when the session expires.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = !busy && appId.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        error = null
                        vm.signInDirect(region, appId, username, password) { error = it }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Sign in")
                }
            }
        } else {
            SectionCard(
                "keloray-control server",
                subtitle = "Use the phone as a remote for `python -m keloray serve`. Scenes and schedules keep running on the server.",
            ) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it.trim() },
                    label = { Text("Server address") },
                    placeholder = { Text("http://192.168.1.50:8000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Start the server so other devices on your network can reach it:\npython -m keloray serve --host 0.0.0.0",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = !busy && serverUrl.isNotBlank(),
                    onClick = {
                        error = null
                        vm.connectServer(serverUrl) { error = it }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Connect")
                }
            }
        }

        error?.let { ErrorPanel(it) }
    }
}
