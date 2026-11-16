package com.kelofred.keloray.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kelofred.keloray.core.JsonUtil
import com.kelofred.keloray.core.SchemaNormalizer
import com.kelofred.keloray.ui.AppViewModel
import com.kelofred.keloray.ui.CodeBlock
import com.kelofred.keloray.ui.ErrorPanel
import com.kelofred.keloray.ui.Load
import com.kelofred.keloray.ui.SectionCard

/** The app's version of `python -m keloray discover <did>`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(vm: AppViewModel, did: String, onBack: () -> Unit) {
    val all by vm.discovery.collectAsState()
    val load = all[did] ?: Load.Idle

    LaunchedEffect(did) { if (load !is Load.Ok) vm.discover(did) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Datapoints") },
                actions = { IconButton(onClick = { vm.discover(did) }) { Icon(Icons.Filled.Refresh, "Refresh") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (load) {
                is Load.Loading, Load.Idle -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is Load.Error -> ErrorPanel(load.message, onRetry = { vm.discover(did) })
                is Load.Ok -> {
                    val d = load.value
                    val dps = SchemaNormalizer.datapoints(d.datapoints)
                    SectionCard("Product", subtitle = "did $did") {
                        Text("product_key ${d.productKey}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    SectionCard("${dps.size} datapoints", subtitle = "Names, types and ranges this product understands") {
                        if (dps.isEmpty()) Text("No datapoint descriptors found in the schema; see the raw JSON below.")
                        dps.forEachIndexed { i, dp ->
                            if (i > 0) HorizontalDivider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(dp.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                                    dp.displayName?.takeIf { it != dp.name }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Column {
                                    Text(
                                        listOfNotNull(dp.dataType, dp.type).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    dp.range?.let { (lo, hi) ->
                                        Text("${fmt(lo)}..${fmt(hi)}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                                    }
                                    dp.enum?.let {
                                        Text("enum ${JsonUtil.stringify(it)}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    SectionCard("Current values") { CodeBlock(JsonUtil.prettify(d.state)) }
                    SectionCard("Raw schema JSON") { CodeBlock(JsonUtil.prettify(d.datapoints)) }
                }
            }
        }
    }
}

private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
