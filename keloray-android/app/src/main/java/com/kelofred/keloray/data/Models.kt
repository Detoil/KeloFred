package com.kelofred.keloray.data

import com.kelofred.keloray.core.SceneKind
import com.kelofred.keloray.core.Scenes
import com.kelofred.keloray.core.asMap

enum class Mode { DIRECT, SERVER }

data class Device(
    val did: String,
    val productKey: String,
    val mac: String = "",
    val alias: String = "",
    val productName: String = "",
    val isOnline: Boolean = false,
    val raw: Map<String, Any?> = emptyMap(),
) {
    val displayName: String get() = alias.ifBlank { productName.ifBlank { did } }

    companion object {
        /** A device from Gizwits GET /app/bindings (mirrors Device.from_api). */
        fun fromGizwits(d: Map<String, Any?>): Device {
            fun s(k: String) = (d[k] as? String).orEmpty()
            return Device(
                did = s("did"),
                productKey = s("product_key"),
                mac = s("mac"),
                alias = s("dev_alias").ifBlank { s("remark") }.ifBlank { s("product_name") },
                productName = s("product_name"),
                isOnline = d["is_online"] == true,
                raw = d,
            )
        }

        /** A device from keloray-control's GET /api/devices (Device.__dict__). */
        fun fromServer(d: Map<String, Any?>): Device {
            fun s(k: String) = (d[k] as? String).orEmpty()
            return Device(
                did = s("did"),
                productKey = s("product_key"),
                mac = s("mac"),
                alias = s("alias"),
                productName = s("product_name"),
                isOnline = d["is_online"] == true,
                raw = d["raw"].asMap() ?: emptyMap(),
            )
        }
    }
}

data class DeviceState(
    val did: String,
    val attrs: Map<String, Any?>,
    val sceneRunning: Boolean,
    /** Known only when scenes run on this phone. */
    val sceneName: String? = null,
)

data class Discovery(
    val did: String,
    val productKey: String,
    val datapoints: Any?,
    val state: Map<String, Any?>,
)

data class SceneInfo(
    val name: String,
    val description: String,
    val defaults: Map<String, Any?>,
    val optional: List<String> = emptyList(),
    val kind: SceneKind = SceneKind.LIGHT,
) {
    val locationAware: Boolean get() = "latitude" in defaults && "longitude" in defaults

    companion object {
        fun local(): List<SceneInfo> = Scenes.all.map {
            SceneInfo(it.name, it.description, it.defaults, it.optional, it.kind)
        }

        fun fromServer(d: Map<String, Any?>): SceneInfo {
            val name = (d["name"] as? String).orEmpty()
            val local = Scenes.find(name)
            return SceneInfo(
                name = name,
                description = (d["description"] as? String).orEmpty().trim().lineSequence().firstOrNull().orEmpty(),
                defaults = d["defaults"].asMap() ?: emptyMap(),
                optional = local?.optional.orEmpty(),
                kind = local?.kind ?: if (name.startsWith("reef")) SceneKind.SPECTRAL else SceneKind.LIGHT,
            )
        }
    }
}

/**
 * An automation rule, the same shape keloray-control uses in config.yaml and
 * POST /api/schedules: either `at` ("HH:MM", daily) or a `cron` dict
 * (hour / minute / day_of_week, APScheduler style).
 */
data class Rule(
    val name: String,
    val did: String,
    val scene: String,
    val params: Map<String, Any?> = emptyMap(),
    val cron: Map<String, Any?> = emptyMap(),
    val at: String? = null,
    val enabled: Boolean = true,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "name" to name, "did" to did, "scene" to scene, "params" to params,
        "cron" to cron, "at" to at, "enabled" to enabled,
    )

    companion object {
        fun fromMap(d: Map<String, Any?>): Rule = Rule(
            name = (d["name"] as? String).orEmpty(),
            did = (d["did"] as? String).orEmpty(),
            scene = (d["scene"] as? String).orEmpty(),
            params = d["params"].asMap() ?: emptyMap(),
            cron = d["cron"].asMap() ?: emptyMap(),
            at = d["at"] as? String,
            enabled = d["enabled"] as? Boolean ?: true,
        )
    }
}

/** Raised when the Gizwits API or the keloray server answers with an error. */
class ApiException(val status: Int, val payload: Any?, message: String) : Exception(message)
