package com.kelofred.keloray

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kelofred.keloray.ui.AppViewModel
import com.kelofred.keloray.ui.screens.ChannelMapScreen
import com.kelofred.keloray.ui.screens.DeviceScreen
import com.kelofred.keloray.ui.screens.DevicesScreen
import com.kelofred.keloray.ui.screens.DiscoverScreen
import com.kelofred.keloray.ui.screens.SchedulesScreen
import com.kelofred.keloray.ui.screens.SettingsScreen
import com.kelofred.keloray.ui.screens.SetupScreen
import com.kelofred.keloray.ui.theme.KeloTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeAskForNotifications()
        setContent {
            KeloTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(vm)
                }
            }
        }
    }

    private fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("kelofred_ui", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit { putBoolean("asked_notifications", true) }
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
fun App(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    if (!settings.configured) {
        SetupScreen(vm)
        return
    }

    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "devices") {
        composable("devices") {
            DevicesScreen(
                vm, snackbar,
                onOpen = { did -> nav.navigate("device/$did") },
                onSchedules = { nav.navigate("schedules") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("device/{did}") { entry ->
            val did = entry.arguments?.getString("did").orEmpty()
            DeviceScreen(
                vm, did, snackbar,
                onBack = { nav.popBackStack() },
                onDiscover = { nav.navigate("discover/$did") },
                onChannelMap = { nav.navigate("channels/$did") },
            )
        }
        composable("discover/{did}") { entry ->
            DiscoverScreen(vm, entry.arguments?.getString("did").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("channels/{did}") { entry ->
            ChannelMapScreen(vm, entry.arguments?.getString("did").orEmpty(), snackbar, onBack = { nav.popBackStack() })
        }
        composable("schedules") { SchedulesScreen(vm, snackbar, onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(vm, snackbar, onBack = { nav.popBackStack() }) }
    }
}
