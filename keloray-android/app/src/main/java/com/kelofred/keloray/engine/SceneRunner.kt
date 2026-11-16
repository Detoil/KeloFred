package com.kelofred.keloray.engine

import android.util.Log
import com.kelofred.keloray.core.ChannelMap
import com.kelofred.keloray.core.Frame
import com.kelofred.keloray.core.SceneEnv
import com.kelofred.keloray.core.SceneParams
import com.kelofred.keloray.core.SceneSpec
import com.kelofred.keloray.core.Scenes
import com.kelofred.keloray.core.asMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs one effect per device, writing frames with rate limiting, bounded
 * retries and a give-up threshold. Port of keloray-control's SceneRunner.
 *
 * Starting a scene on a device cancels whatever was running there.
 */
class SceneRunner(
    private val scope: CoroutineScope,
    private val write: suspend (did: String, attrs: Map<String, Any?>) -> Unit,
    private val channelMap: (did: String) -> ChannelMap = { ChannelMap() },
    private val minIntervalMs: () -> Long = { 250 },
    private val env: SceneEnv = SceneEnv(),
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val backoffMs: List<Long> = listOf(500, 1000),
    private val maxAttempts: Int = 3,
    private val maxConsecutiveFailures: Int = 10,
) {
    data class Running(
        val did: String,
        val scene: String,
        val params: Map<String, Any?>,
        val continuous: Boolean,
        val startedAt: Long = System.currentTimeMillis(),
        val frames: Int = 0,
        val lastError: String? = null,
    )

    private val _running = MutableStateFlow<Map<String, Running>>(emptyMap())
    val running: StateFlow<Map<String, Running>> = _running.asStateFlow()

    private val jobs = HashMap<String, Job>()
    private val lock = Mutex()

    fun isRunning(did: String): Boolean = _running.value.containsKey(did)

    /** Expand a spectral `channels` frame into the fixture's real datapoints. */
    fun expand(frame: Frame, did: String): Frame {
        val channels = frame["channels"].asMap() ?: return frame
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in frame) if (k != "channels") out[k] = v
        out.putAll(channelMap(did).toAttrs(channels))
        return out
    }

    /** @throws IllegalArgumentException for an unknown scene name. */
    suspend fun start(did: String, sceneName: String, params: Map<String, Any?> = emptyMap()): Running {
        val spec = Scenes.get(sceneName)
        return lock.withLock {
            synchronized(jobs) { jobs.remove(did) }?.cancelAndJoin()
            val info = Running(did, sceneName, params, spec.continuous)
            val job = scope.launch(start = CoroutineStart.LAZY) { run(did, spec, spec.defaults + params) }
            synchronized(jobs) { jobs[did] = job }
            _running.update { it + (did to info) }
            job.invokeOnCompletion {
                synchronized(jobs) {
                    if (jobs[did] === job) {
                        jobs.remove(did)
                        _running.update { it - did }
                    }
                }
            }
            job.start()
            info
        }
    }

    suspend fun stop(did: String) {
        lock.withLock {
            val job = synchronized(jobs) { jobs.remove(did) }
            _running.update { it - did }
            job?.cancelAndJoin()
        }
    }

    suspend fun stopAll() {
        for (did in _running.value.keys.toList()) stop(did)
    }

    private class GiveUp : Exception()

    private suspend fun run(did: String, spec: SceneSpec, opts: Map<String, Any?>) {
        var last: Long? = null
        var consecutiveFailures = 0
        try {
            spec.effect(SceneParams(opts), env).collect { raw ->
                val frame = expand(raw, did)
                last?.let {
                    val wait = minIntervalMs() - (clockMs() - it)
                    if (wait > 0) delay(wait)
                }
                var error: Exception? = null
                var wrote = false
                for (attempt in 1..maxAttempts) {
                    try {
                        write(did, frame)
                        wrote = true
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e
                        if (attempt < maxAttempts) {
                            val d = backoffMs[minOf(attempt - 1, backoffMs.size - 1)]
                            Log.w(TAG, "scene ${spec.name} on $did: write failed ($attempt/$maxAttempts): ${e.message}")
                            delay(d)
                        } else {
                            Log.w(TAG, "scene ${spec.name} on $did: write failed after $maxAttempts attempts; skipping frame")
                        }
                    } finally {
                        last = clockMs()
                    }
                }
                if (wrote) {
                    consecutiveFailures = 0
                    _running.update { m ->
                        m[did]?.let { m + (did to it.copy(frames = it.frames + 1, lastError = null)) } ?: m
                    }
                } else {
                    consecutiveFailures++
                    _running.update { m ->
                        m[did]?.let { m + (did to it.copy(lastError = error?.message)) } ?: m
                    }
                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        Log.e(TAG, "scene ${spec.name} on $did: $consecutiveFailures failed frames in a row; stopping")
                        throw GiveUp()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: GiveUp) {
            // stopped after too many failures; completion handler clears state
        } catch (e: Exception) {
            Log.e(TAG, "scene ${spec.name} on $did: unexpected error; stopping", e)
        }
    }

    companion object {
        private const val TAG = "SceneRunner"
    }
}
