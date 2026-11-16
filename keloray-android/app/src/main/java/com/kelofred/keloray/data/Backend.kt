package com.kelofred.keloray.data

import com.kelofred.keloray.engine.SceneRunner

/**
 * What the UI talks to. Two implementations:
 *  - [DirectBackend]: the phone calls the Gizwits cloud itself and runs scenes
 *    in a foreground service.
 *  - [ServerBackend]: the phone is a remote for keloray-control's REST API.
 */
interface Backend {
    val mode: Mode

    suspend fun devices(): List<Device>
    suspend fun state(did: String): DeviceState
    suspend fun discover(did: String): Discovery

    /** Manual write. Stops any running scene on that device first. May carry `channels`. */
    suspend fun control(did: String, attrs: Map<String, Any?>)
    suspend fun power(did: String, on: Boolean)

    suspend fun scenes(): List<SceneInfo>
    suspend fun startScene(did: String, scene: String, params: Map<String, Any?>)
    suspend fun stopScene(did: String)

    suspend fun schedules(): List<Rule>
    suspend fun saveSchedule(rule: Rule)
    suspend fun deleteSchedule(name: String)
}

class DirectBackend(
    private val client: GizwitsClient,
    private val runner: SceneRunner,
    private val channelMaps: ChannelMapStore,
    private val rules: RuleStore,
    /** Keeps the foreground service alive while scenes run. */
    private val onSceneStarted: () -> Unit,
    /** Re-arms alarms after rules change. */
    private val onRulesChanged: () -> Unit,
) : Backend {
    override val mode = Mode.DIRECT

    override suspend fun devices(): List<Device> = client.bindings()

    override suspend fun state(did: String): DeviceState {
        val running = runner.running.value[did]
        return DeviceState(did, client.latest(did), running != null, running?.scene)
    }

    override suspend fun discover(did: String): Discovery {
        val dev = client.bindings().firstOrNull { it.did == did }
            ?: throw ApiException(404, null, "Device $did is not in your bindings")
        return Discovery(did, dev.productKey, client.datapoints(dev.productKey), client.latest(did))
    }

    override suspend fun control(did: String, attrs: Map<String, Any?>) {
        runner.stop(did)
        client.control(did, runner.expand(attrs, did))
    }

    override suspend fun power(did: String, on: Boolean) {
        runner.stop(did)
        client.control(did, mapOf("onOff" to if (on) 1 else 0))
    }

    override suspend fun scenes(): List<SceneInfo> = SceneInfo.local()

    override suspend fun startScene(did: String, scene: String, params: Map<String, Any?>) {
        runner.start(did, scene, params)
        onSceneStarted()
    }

    override suspend fun stopScene(did: String) = runner.stop(did)

    override suspend fun schedules(): List<Rule> = rules.rules.value

    override suspend fun saveSchedule(rule: Rule) {
        rules.save(rule)
        onRulesChanged()
    }

    override suspend fun deleteSchedule(name: String) {
        rules.delete(name)
        onRulesChanged()
    }

    fun channelMap(did: String) = channelMaps.get(did)
}

class ServerBackend(private val server: ServerClient) : Backend {
    override val mode = Mode.SERVER

    override suspend fun devices() = server.devices()
    override suspend fun state(did: String) = server.state(did)
    override suspend fun discover(did: String) = server.discover(did)
    override suspend fun control(did: String, attrs: Map<String, Any?>) = server.control(did, attrs)
    override suspend fun power(did: String, on: Boolean) = server.power(did, on)
    override suspend fun scenes() = server.scenes()
    override suspend fun startScene(did: String, scene: String, params: Map<String, Any?>) =
        server.startScene(did, scene, params)
    override suspend fun stopScene(did: String) = server.stopScene(did)
    override suspend fun schedules() = server.schedules()
    override suspend fun saveSchedule(rule: Rule) = server.saveSchedule(rule)
    override suspend fun deleteSchedule(name: String) = server.deleteSchedule(name)
}
