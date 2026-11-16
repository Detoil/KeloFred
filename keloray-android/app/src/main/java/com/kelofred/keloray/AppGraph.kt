package com.kelofred.keloray

import android.app.Application
import android.content.Context
import com.kelofred.keloray.data.ActiveScene
import com.kelofred.keloray.data.ActiveSceneStore
import com.kelofred.keloray.data.Backend
import com.kelofred.keloray.data.ChannelMapStore
import com.kelofred.keloray.data.DirectBackend
import com.kelofred.keloray.data.GizwitsClient
import com.kelofred.keloray.data.Mode
import com.kelofred.keloray.data.RuleStore
import com.kelofred.keloray.data.ServerBackend
import com.kelofred.keloray.data.ServerClient
import com.kelofred.keloray.data.SettingsStore
import com.kelofred.keloray.engine.SceneRunner
import com.kelofred.keloray.engine.SceneService
import com.kelofred.keloray.engine.ScheduleAlarms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class KeloApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        SceneService.ensureChannels(this)
    }
}

/** App-wide singletons: settings, stores, the Gizwits client and the scene runner. */
class AppGraph(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(context)
    val channelMaps = ChannelMapStore(context)
    val rules = RuleStore(context)
    val activeScenes = ActiveSceneStore(context)

    /** did -> friendly name, for notifications. */
    val deviceNames = MutableStateFlow<Map<String, String>>(emptyMap())

    @Volatile private var gizwits: GizwitsClient? = null
    @Volatile private var gizwitsKey: String? = null
    @Volatile private var server: ServerClient? = null

    val runner = SceneRunner(
        scope = appScope,
        write = { did, attrs -> gizwits().control(did, attrs) },
        channelMap = { did -> channelMaps.get(did) },
        minIntervalMs = { settings.current.minIntervalMs },
    )

    init {
        // Remember continuous scenes so they can resume if Android kills the
        // process. drop(1) skips the empty initial value so a cold start
        // doesn't wipe what is waiting to be restored.
        appScope.launch {
            runner.running.drop(1).collect { m ->
                activeScenes.write(m.values.filter { it.continuous }.map { ActiveScene(it.did, it.scene, it.params) })
            }
        }
    }

    fun newGizwits(region: String, appId: String): GizwitsClient = GizwitsClient(
        appId = appId,
        region = region,
        onAuth = { a -> settings.update { it.copy(token = a.token, uid = a.uid, expireAt = a.expireAt) } },
        credentials = {
            val s = settings.current
            val pw = settings.password()
            if (s.username.isNotBlank() && !pw.isNullOrEmpty()) s.username to pw else null
        },
    )

    @Synchronized
    fun gizwits(): GizwitsClient {
        val s = settings.current
        val key = "${s.region}|${s.appId}"
        val existing = gizwits
        if (existing != null && key == gizwitsKey) return existing
        return newGizwits(s.region, s.appId).also {
            it.token = s.token
            it.uid = s.uid
            it.expireAt = s.expireAt
            gizwits = it
            gizwitsKey = key
        }
    }

    /** Sign in to the Gizwits cloud and switch to direct mode. */
    suspend fun signInDirect(region: String, appId: String, username: String, password: String) {
        settings.update { it.copy(region = region, appId = appId, username = username) }
        settings.setPassword(password)
        val client = newGizwits(region, appId)
        client.login(username, password)
        synchronized(this) {
            gizwits = client
            gizwitsKey = "$region|$appId"
        }
        settings.update { it.copy(mode = Mode.DIRECT) }
        ScheduleAlarms.sync(context, rules.rules.value)
    }

    /** Check the keloray-control server answers, then switch to server mode. */
    suspend fun connectServer(url: String) {
        val normalized = ServerClient.normalizeUrl(url)
        val client = ServerClient(normalized)
        client.scenes()
        server = client
        settings.update { it.copy(mode = Mode.SERVER, serverUrl = normalized) }
        ScheduleAlarms.sync(context, emptyList())
    }

    fun backend(): Backend? = when (settings.current.mode) {
        Mode.DIRECT -> DirectBackend(
            client = gizwits(),
            runner = runner,
            channelMaps = channelMaps,
            rules = rules,
            onSceneStarted = { SceneService.keepAlive(context) },
            onRulesChanged = { ScheduleAlarms.sync(context, rules.rules.value) },
        )
        Mode.SERVER -> {
            val url = settings.current.serverUrl
            val s = server?.takeIf { it.base.toString() == url } ?: ServerClient(url).also { server = it }
            ServerBackend(s)
        }
        null -> null
    }

    suspend fun restoreActiveScenes() {
        if (settings.current.mode != Mode.DIRECT) return
        for (a in activeScenes.read()) {
            if (!runner.isRunning(a.did)) runCatching { runner.start(a.did, a.scene, a.params) }
        }
    }

    suspend fun signOut() {
        runner.stopAll()
        ScheduleAlarms.sync(context, emptyList())
        settings.signOut()
        synchronized(this) {
            gizwits = null
            gizwitsKey = null
        }
    }
}
