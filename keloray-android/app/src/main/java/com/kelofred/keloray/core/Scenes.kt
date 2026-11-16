package com.kelofred.keloray.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** A frame is an attrs map to write to the device (may carry a `channels` map). */
typealias Frame = Map<String, Any?>

enum class SceneKind { LIGHT, SPECTRAL }

/** Loosely-typed scene options, read the way Python's keyword args behave. */
class SceneParams(private val m: Map<String, Any?>) {
    fun raw(k: String): Any? = m[k]

    fun double(k: String, def: Double): Double = doubleOrNull(k) ?: def

    fun doubleOrNull(k: String): Double? = when (val v = m[k]) {
        is Boolean -> if (v) 1.0 else 0.0
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    fun bool(k: String, def: Boolean): Boolean = when (val v = m[k]) {
        null -> def
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.trim().lowercase() in setOf("true", "1", "yes", "on")
        else -> true
    }

    fun intList(k: String): List<Int>? = (m[k] as? List<*>)?.mapNotNull { it.asDouble()?.toInt() }

    fun map(k: String): Map<String, Any?>? = m[k].asMap()

    fun seed(k: String): Long? = doubleOrNull(k)?.toLong()
}

/** Things effects read from the outside world; swappable in tests. */
class SceneEnv(
    val now: () -> LocalDateTime = { LocalDateTime.now() },
    val random: (Long?) -> Random = { seed -> if (seed == null) Random.Default else Random(seed) },
)

class SceneSpec(
    val name: String,
    val description: String,
    /** Same keys/values as the Python @scene defaults. */
    val defaults: Map<String, Any?>,
    /** Extra optional keyword args not present in [defaults] (e.g. solid's hsv). */
    val optional: List<String> = emptyList(),
    val kind: SceneKind = SceneKind.LIGHT,
    /** Runs until stopped (safe to resume after the app process restarts). */
    val continuous: Boolean = false,
    val effect: (SceneParams, SceneEnv) -> Flow<Frame>,
)

/** Human hints for scene options, shown under the parameter fields. */
val PARAM_HINTS = mapOf(
    "lum" to "Brightness 1-100",
    "on" to "true = on, false = off",
    "temperature" to "White temperature 0 (warm) - 100 (cool)",
    "hsv" to """Colour, e.g. {"h":280,"s":100,"v":100}""",
    "start" to "Starting brightness",
    "end" to "Final brightness",
    "seconds" to "Duration in seconds",
    "tick" to "Seconds between updates",
    "warm" to "Warmest temperature (0-100)",
    "cool" to "Coolest temperature (0-100)",
    "saturation" to "Colour saturation 0-100",
    "value" to "Colour brightness 0-100",
    "period" to "Seconds per full cycle",
    "low" to "Lowest brightness",
    "high" to "Highest brightness",
    "base" to "Base level",
    "jitter" to "How much it flickers",
    "base_lum" to "Overcast brightness",
    "flash_lum" to "Lightning peak brightness",
    "flash" to "Lightning peak level",
    "min_gap" to "Shortest seconds between flashes",
    "max_gap" to "Longest seconds between flashes",
    "double_flash_chance" to "Chance (0-1) of a multi-flicker",
    "seed" to "Random seed (blank = random)",
    "latitude" to "Latitude of the sun to mirror",
    "longitude" to "Longitude of the sun to mirror",
    "utc_offset" to "UTC offset of that location, hours",
    "wet_season" to "Dim and cool during monsoon months",
    "wet_months" to "Monsoon months, e.g. [11,12,1]",
    "caps" to """Per-channel limits, e.g. {"uv1":60,"uv2":60}""",
    "moonlight" to "Night-time blue level 0-100 (0 = off)",
)

private fun ease(t: Double) = t * t * (3 - 2 * t)
private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
private fun clamp(v: Int, lo: Int, hi: Int) = max(lo, min(hi, v))
private suspend fun sleep(seconds: Double) = delay((seconds * 1000).toLong().coerceAtLeast(0))

object Scenes {
    private val registry = LinkedHashMap<String, SceneSpec>()

    val all: List<SceneSpec> get() = registry.values.toList()

    fun get(name: String): SceneSpec = registry[name]
        ?: throw IllegalArgumentException("unknown scene '$name'. known: ${registry.keys.sorted()}")

    fun find(name: String): SceneSpec? = registry[name]

    /** Build the frame flow for a scene with defaults merged under [params]. */
    fun flowFor(name: String, params: Map<String, Any?>, env: SceneEnv = SceneEnv()): Flow<Frame> {
        val spec = get(name)
        return spec.effect(SceneParams(spec.defaults + params), env)
    }

    private fun register(spec: SceneSpec) {
        registry[spec.name] = spec
    }

    private fun scene(
        name: String,
        description: String,
        defaults: Map<String, Any?>,
        optional: List<String> = emptyList(),
        kind: SceneKind = SceneKind.LIGHT,
        continuous: Boolean = false,
        body: suspend FlowCollector<Frame>.(SceneParams, SceneEnv) -> Unit,
    ) = register(
        SceneSpec(name, description, defaults, optional, kind, continuous) { p, env ->
            flow { body(p, env) }
        },
    )

    init {
        scene("solid", "Set a fixed state once.", linkedMapOf("lum" to 100L, "on" to true), listOf("temperature", "hsv")) { p, _ ->
            val f = linkedMapOf<String, Any?>("onOff" to if (p.bool("on", true)) 1 else 0)
            p.doubleOrNull("lum")?.let { f["lum"] = it.toInt() }
            p.doubleOrNull("temperature")?.let { f["temperature"] = it.toInt() }
            p.raw("hsv")?.let { f["hsv"] = it }
            emit(f)
        }

        scene(
            "fade", "Smoothly fade brightness from one level to another.",
            linkedMapOf("start" to 0L, "end" to 100L, "seconds" to 5L, "tick" to 0.4),
        ) { p, _ ->
            val start = p.double("start", 0.0)
            val end = p.double("end", 100.0)
            val tick = p.double("tick", 0.4)
            val steps = max(1, (p.double("seconds", 5.0) / tick).toInt())
            emit(mapOf("onOff" to 1))
            for (i in 0..steps) {
                val t = ease(i.toDouble() / steps)
                emit(mapOf("lum" to pyRound(lerp(start, end, t))))
                sleep(tick)
            }
        }

        scene(
            "sunrise", "Warm, slow dawn: brightness + color temperature ramp up.",
            linkedMapOf("seconds" to 900L, "tick" to 2.0, "warm" to 0L, "cool" to 100L),
        ) { p, _ ->
            val tick = p.double("tick", 2.0)
            val warm = p.double("warm", 0.0)
            val cool = p.double("cool", 100.0)
            val steps = max(1, (p.double("seconds", 900.0) / tick).toInt())
            emit(linkedMapOf("onOff" to 1, "lum" to 1))
            for (i in 0..steps) {
                val t = ease(i.toDouble() / steps)
                emit(
                    linkedMapOf(
                        "lum" to max(1, pyRound(lerp(1.0, 100.0, t))),
                        "temperature" to pyRound(lerp(warm, cool, t)),
                    ),
                )
                sleep(tick)
            }
        }

        scene(
            "sunset", "Reverse of sunrise: dim down to warm, then off.",
            linkedMapOf("seconds" to 900L, "tick" to 2.0, "warm" to 0L, "cool" to 100L),
        ) { p, _ ->
            val tick = p.double("tick", 2.0)
            val warm = p.double("warm", 0.0)
            val cool = p.double("cool", 100.0)
            val steps = max(1, (p.double("seconds", 900.0) / tick).toInt())
            for (i in 0..steps) {
                val t = ease(i.toDouble() / steps)
                emit(
                    linkedMapOf(
                        "lum" to max(1, pyRound(lerp(100.0, 1.0, t))),
                        "temperature" to pyRound(lerp(cool, warm, t)),
                    ),
                )
                sleep(tick)
            }
            emit(mapOf("onOff" to 0))
        }

        scene(
            "color_loop", "Cycle hue endlessly at fixed saturation/brightness.",
            linkedMapOf("saturation" to 100L, "value" to 100L, "period" to 30L, "tick" to 0.5),
            continuous = true,
        ) { p, _ ->
            val sat = p.double("saturation", 100.0).toInt()
            val value = p.double("value", 100.0).toInt()
            val period = p.double("period", 30.0)
            val tick = p.double("tick", 0.5)
            emit(mapOf("onOff" to 1))
            var elapsed = 0.0
            while (true) {
                val hue = Math.floorMod(((elapsed / period) * 360).toInt(), 360)
                emit(mapOf("hsv" to linkedMapOf("h" to hue, "s" to sat, "v" to value)))
                sleep(tick)
                elapsed += tick
            }
        }

        scene(
            "breathe", "Gentle brightness pulse (sinusoidal).",
            linkedMapOf("low" to 10L, "high" to 100L, "period" to 6L, "tick" to 0.3),
            continuous = true,
        ) { p, _ ->
            val low = p.double("low", 10.0)
            val high = p.double("high", 100.0)
            val period = p.double("period", 6.0)
            val tick = p.double("tick", 0.3)
            emit(mapOf("onOff" to 1))
            var elapsed = 0.0
            while (true) {
                val s = (sin(2 * PI * elapsed / period - PI / 2) + 1) / 2
                emit(mapOf("lum" to pyRound(lerp(low, high, s))))
                sleep(tick)
                elapsed += tick
            }
        }

        scene(
            "candle", "Warm flicker that mimics a candle flame.",
            linkedMapOf("base" to 60L, "jitter" to 25L, "tick" to 0.25),
            continuous = true,
        ) { p, _ ->
            val base = p.double("base", 60.0)
            val jitter = p.double("jitter", 25.0)
            val tick = p.double("tick", 0.25)
            emit(linkedMapOf("onOff" to 1, "temperature" to 5))
            var elapsed = 0.0
            while (true) {
                val f = sin(elapsed * 7.3) + sin(elapsed * 13.1) * 0.5 + sin(elapsed * 23.7) * 0.25
                val lum = base + f / 1.75 * jitter
                emit(mapOf("lum" to clamp(pyRound(lum), 1, 100)))
                sleep(tick)
                elapsed += tick
            }
        }

        scene(
            "thunderstorm", "Overcast storm with random lightning flashes.",
            linkedMapOf(
                "base_lum" to 8L, "flash_lum" to 100L, "min_gap" to 2.0, "max_gap" to 15.0,
                "double_flash_chance" to 0.4, "tick" to 0.5, "seed" to null,
            ),
            continuous = true,
        ) { p, env ->
            val rng = env.random(p.seed("seed"))
            val baseLum = clamp(p.double("base_lum", 8.0).toInt(), 1, 100)
            val flashLum = clamp(p.double("flash_lum", 100.0).toInt(), 1, 100)
            val minGap = p.double("min_gap", 2.0)
            val maxGap = p.double("max_gap", 15.0)
            val chance = p.double("double_flash_chance", 0.4)
            val tick = p.double("tick", 0.5)
            val base = linkedMapOf(
                "onOff" to 1, "lum" to baseLum, "temperature" to 100,
                "hsv" to linkedMapOf("h" to 220, "s" to 30, "v" to baseLum),
            )
            val flash = linkedMapOf(
                "onOff" to 1, "lum" to flashLum, "temperature" to 100,
                "hsv" to linkedMapOf("h" to 0, "s" to 0, "v" to flashLum),
            )
            emit(base)
            while (true) {
                val gap = minGap + (maxGap - minGap) * rng.nextDouble()
                var waited = 0.0
                while (waited < gap) {
                    emit(base)
                    sleep(tick)
                    waited += tick
                }
                val flickers = if (rng.nextDouble() < chance) (if (rng.nextBoolean()) 2 else 3) else 1
                repeat(flickers) {
                    emit(flash)
                    sleep(tick)
                    emit(base)
                    sleep(tick)
                }
            }
        }

        scene(
            "tropics", "Track real tropical daylight (default ~Singapore).",
            linkedMapOf(
                "latitude" to Solar.DEFAULT_LAT, "longitude" to Solar.DEFAULT_LON,
                "utc_offset" to Solar.DEFAULT_UTC, "tick" to 60L, "wet_season" to false, "wet_months" to null,
            ),
            continuous = true,
        ) { p, env ->
            val wetMonths = p.intList("wet_months") ?: listOf(11, 12, 1)
            val wet = p.bool("wet_season", false)
            val tick = p.double("tick", 60.0)
            while (true) {
                val now = env.now()
                val inWet = wet && now.monthValue in wetMonths
                emit(
                    Solar.sunChannels(
                        now,
                        latitudeDeg = p.double("latitude", Solar.DEFAULT_LAT),
                        longitudeDeg = p.double("longitude", Solar.DEFAULT_LON),
                        utcOffsetHours = p.double("utc_offset", Solar.DEFAULT_UTC),
                        luxScale = if (inWet) 0.6 else 1.0,
                        cctBumpK = if (inWet) 600.0 else 0.0,
                    ),
                )
                sleep(tick)
            }
        }

        scene(
            "reef_day", "Tropical reef sun: spectral channel mix tracking the day.",
            linkedMapOf(
                "latitude" to Solar.DEFAULT_LAT, "longitude" to Solar.DEFAULT_LON,
                "utc_offset" to Solar.DEFAULT_UTC, "tick" to 60L, "wet_season" to false, "wet_months" to null,
                "caps" to null, "moonlight" to 0.0,
            ),
            kind = SceneKind.SPECTRAL,
            continuous = true,
        ) { p, env ->
            val wetMonths = p.intList("wet_months") ?: listOf(11, 12, 1)
            val wet = p.bool("wet_season", false)
            val tick = p.double("tick", 60.0)
            while (true) {
                val now = env.now()
                val scale = if (wet && now.monthValue in wetMonths) 0.55 else 1.0
                val elev = Solar.elevationAt(
                    now,
                    p.double("latitude", Solar.DEFAULT_LAT),
                    p.double("longitude", Solar.DEFAULT_LON),
                    p.double("utc_offset", Solar.DEFAULT_UTC),
                )
                emit(Reef.reefChannels(elev, p.map("caps"), scale, p.double("moonlight", 0.0)))
                sleep(tick)
            }
        }

        scene(
            "reef_storm", "Overcast reef storm with lightning on the white channels.",
            linkedMapOf(
                "base" to 15L, "flash" to 100L, "min_gap" to 2.0, "max_gap" to 15.0,
                "double_flash_chance" to 0.4, "tick" to 0.5, "seed" to null,
            ),
            kind = SceneKind.SPECTRAL,
            continuous = true,
        ) { p, env ->
            val rng = env.random(p.seed("seed"))
            val b = clamp(p.double("base", 15.0).toInt(), 0, 100)
            val f = clamp(p.double("flash", 100.0).toInt(), 0, 100)
            val minGap = p.double("min_gap", 2.0)
            val maxGap = p.double("max_gap", 15.0)
            val chance = p.double("double_flash_chance", 0.4)
            val tick = p.double("tick", 0.5)
            val base = linkedMapOf(
                "onOff" to 1,
                "channels" to linkedMapOf(
                    "cw" to b, "rb" to (b * 1.4).toInt(), "sb" to b, "uv1" to 0, "uv2" to 0,
                    "dr" to 0, "g" to (b * 0.4).toInt(),
                ),
            )
            val flash = linkedMapOf(
                "onOff" to 1,
                "channels" to linkedMapOf(
                    "cw" to f, "sb" to (f * 0.7).toInt(), "rb" to (f * 0.5).toInt(), "uv1" to 0,
                    "uv2" to 0, "dr" to 0, "g" to 0,
                ),
            )
            emit(base)
            while (true) {
                val gap = minGap + (maxGap - minGap) * rng.nextDouble()
                var waited = 0.0
                while (waited < gap) {
                    emit(base)
                    sleep(tick)
                    waited += tick
                }
                val flickers = if (rng.nextDouble() < chance) (if (rng.nextBoolean()) 2 else 3) else 1
                repeat(flickers) {
                    emit(flash)
                    sleep(tick)
                    emit(base)
                    sleep(tick)
                }
            }
        }
    }
}
