package com.kelofred.keloray.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kelofred.keloray.KeloApp
import com.kelofred.keloray.core.ChannelSpec
import com.kelofred.keloray.data.ApiException
import com.kelofred.keloray.data.Backend
import com.kelofred.keloray.data.Device
import com.kelofred.keloray.data.DeviceState
import com.kelofred.keloray.data.Discovery
import com.kelofred.keloray.data.GizwitsClient
import com.kelofred.keloray.data.Mode
import com.kelofred.keloray.data.REGIONS
import com.kelofred.keloray.data.Rule
import com.kelofred.keloray.data.SceneInfo
import com.kelofred.keloray.data.Settings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Error(val message: String) : Load<Nothing>
}

val <T> Load<T>.valueOrNull: T? get() = (this as? Load.Ok<T>)?.value

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val graph = (app as KeloApp).graph
    val settings: StateFlow<Settings> = graph.settings.settings
    val running = graph.runner.running
    val channelMaps = graph.channelMaps.maps

    private val _devices = MutableStateFlow<Load<List<Device>>>(Load.Idle)
    val devices: StateFlow<Load<List<Device>>> = _devices.asStateFlow()

    private val _scenes = MutableStateFlow<List<SceneInfo>>(emptyList())
    val scenes: StateFlow<List<SceneInfo>> = _scenes.asStateFlow()

    private val _states = MutableStateFlow<Map<String, Load<DeviceState>>>(emptyMap())
    val states: StateFlow<Map<String, Load<DeviceState>>> = _states.asStateFlow()

    private val _discovery = MutableStateFlow<Map<String, Load<Discovery>>>(emptyMap())
    val discovery: StateFlow<Map<String, Load<Discovery>>> = _discovery.asStateFlow()

    private val _schedules = MutableStateFlow<Load<List<Rule>>>(Load.Idle)
    val schedules: StateFlow<Load<List<Rule>>> = _schedules.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private fun backend(): Backend? = graph.backend()

    fun say(text: String) {
        _messages.tryEmit(text)
    }

    fun friendly(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "Request failed"
        is UnknownHostException -> "No connection (can't resolve ${e.message ?: "host"})"
        is ConnectException -> if (settings.value.mode == Mode.SERVER) {
            "Can't reach the server at ${settings.value.serverUrl}. Is `keloray serve --host 0.0.0.0` running?"
        } else {
            "Connection refused"
        }
        is SocketTimeoutException -> "Timed out"
        is IllegalArgumentException -> e.message ?: "Invalid input"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun op(label: String? = null, block: suspend (Backend) -> Unit) {
        val b = backend() ?: return
        viewModelScope.launch {
            try {
                block(b)
                label?.let { say(it) }
            } catch (e: Exception) {
                say(friendly(e))
            }
        }
    }

    // -- connection --------------------------------------------------------

    suspend fun probe(region: String): Pair<String, List<String>> {
        val ids = GizwitsClient.probeAppIds(region)
        if (ids.isNotEmpty()) return region to ids
        for (other in REGIONS.keys - region) {
            val found = GizwitsClient.probeAppIds(other)
            if (found.isNotEmpty()) return other to found
        }
        return region to emptyList()
    }

    fun signInDirect(region: String, appId: String, username: String, password: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                graph.signInDirect(region, appId.trim(), username.trim(), password)
                refreshDevices()
            } catch (e: Exception) {
                onError(friendly(e))
            } finally {
                _busy.value = false
            }
        }
    }

    fun connectServer(url: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                graph.connectServer(url)
                refreshDevices()
            } catch (e: Exception) {
                onError(friendly(e))
            } finally {
                _busy.value = false
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            graph.signOut()
            _devices.value = Load.Idle
            _states.value = emptyMap()
            _discovery.value = emptyMap()
            _schedules.value = Load.Idle
            _scenes.value = emptyList()
        }
    }

    fun updateSettings(block: (Settings) -> Settings) = graph.settings.update(block)

    // -- devices -----------------------------------------------------------

    fun refreshDevices() {
        val b = backend() ?: return
        viewModelScope.launch {
            _devices.value = Load.Loading
            _devices.value = try {
                val list = b.devices()
                graph.deviceNames.update { it + list.associate { d -> d.did to d.displayName } }
                Load.Ok(list)
            } catch (e: Exception) {
                Load.Error(friendly(e))
            }
            if (_scenes.value.isEmpty()) loadScenes()
        }
    }

    fun loadScenes() {
        val b = backend() ?: return
        viewModelScope.launch {
            runCatching { b.scenes() }
                .onSuccess { _scenes.value = it }
                .onFailure { say("Couldn't load scenes: ${friendly(it)}") }
        }
    }

    fun device(did: String): Device? = _devices.value.valueOrNull?.firstOrNull { it.did == did }

    fun loadState(did: String) {
        val b = backend() ?: return
        viewModelScope.launch {
            if (_states.value[did] !is Load.Ok) _states.update { it + (did to Load.Loading) }
            val result = try {
                Load.Ok(b.state(did))
            } catch (e: Exception) {
                Load.Error(friendly(e))
            }
            _states.update { it + (did to result) }
        }
    }

    fun power(did: String, on: Boolean) = op(if (on) "Turned on" else "Turned off") {
        it.power(did, on)
        loadState(did)
    }

    fun control(did: String, attrs: Map<String, Any?>, label: String? = null) = op(label) {
        it.control(did, attrs)
    }

    fun startScene(did: String, scene: String, params: Map<String, Any?>) = op("Started $scene") {
        it.startScene(did, scene, params)
        if (it.mode == Mode.SERVER) loadState(did)
    }

    fun stopScene(did: String) = op("Scene stopped") {
        it.stopScene(did)
        if (it.mode == Mode.SERVER) loadState(did)
    }

    fun discover(did: String) {
        val b = backend() ?: return
        viewModelScope.launch {
            _discovery.update { it + (did to Load.Loading) }
            val result = try {
                Load.Ok(b.discover(did))
            } catch (e: Exception) {
                Load.Error(friendly(e))
            }
            _discovery.update { it + (did to result) }
        }
    }

    // -- channel maps (direct mode) ----------------------------------------

    fun saveChannelMap(did: String, specs: Map<String, ChannelSpec>) {
        graph.channelMaps.save(did, specs)
        say("Channel map saved")
    }

    fun clearChannelMap(did: String) {
        graph.channelMaps.clear(did)
        say("Channel map reset")
    }

    // -- schedules ---------------------------------------------------------

    fun loadSchedules() {
        val b = backend() ?: return
        viewModelScope.launch {
            if (_schedules.value !is Load.Ok) _schedules.value = Load.Loading
            _schedules.value = try {
                Load.Ok(b.schedules())
            } catch (e: Exception) {
                Load.Error(friendly(e))
            }
        }
    }

    fun saveSchedule(rule: Rule, label: String = "Schedule saved") = op(label) {
        it.saveSchedule(rule)
        loadSchedules()
    }

    fun deleteSchedule(name: String) = op("Schedule deleted") {
        it.deleteSchedule(name)
        loadSchedules()
    }
}
