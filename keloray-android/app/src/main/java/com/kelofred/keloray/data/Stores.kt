package com.kelofred.keloray.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.kelofred.keloray.core.ChannelMap
import com.kelofred.keloray.core.ChannelSpec
import com.kelofred.keloray.core.JsonUtil
import com.kelofred.keloray.core.asMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Settings(
    /** null until the user has connected once. */
    val mode: Mode? = null,
    val region: String = "cn",
    val appId: String = "",
    val username: String = "",
    val token: String? = null,
    val uid: String? = null,
    val expireAt: Long = 0,
    val serverUrl: String = "",
    val minIntervalMs: Long = 250,
    val homeLat: Double? = null,
    val homeLon: Double? = null,
    val homeUtc: Double? = null,
) {
    val configured: Boolean
        get() = when (mode) {
            Mode.DIRECT -> appId.isNotBlank() && !token.isNullOrBlank()
            Mode.SERVER -> serverUrl.isNotBlank()
            null -> false
        }
}

/** Encrypts small secrets (the account password) with a key held in the Android Keystore. */
object Secrets {
    private const val ALIAS = "kelofred_secrets"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }.getOrNull()
}

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("kelofred", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val current: Settings get() = _settings.value

    private fun read(): Settings = Settings(
        mode = prefs.getString("mode", null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() },
        region = prefs.getString("region", "cn") ?: "cn",
        appId = prefs.getString("app_id", "").orEmpty(),
        username = prefs.getString("username", "").orEmpty(),
        token = prefs.getString("token", null),
        uid = prefs.getString("uid", null),
        expireAt = prefs.getLong("expire_at", 0),
        serverUrl = prefs.getString("server_url", "").orEmpty(),
        minIntervalMs = prefs.getLong("min_interval_ms", 250),
        homeLat = prefs.getString("home_lat", null)?.toDoubleOrNull(),
        homeLon = prefs.getString("home_lon", null)?.toDoubleOrNull(),
        homeUtc = prefs.getString("home_utc", null)?.toDoubleOrNull(),
    )

    fun update(block: (Settings) -> Settings) {
        val s = block(_settings.value)
        prefs.edit {
            putString("mode", s.mode?.name)
            putString("region", s.region)
            putString("app_id", s.appId)
            putString("username", s.username)
            putString("token", s.token)
            putString("uid", s.uid)
            putLong("expire_at", s.expireAt)
            putString("server_url", s.serverUrl)
            putLong("min_interval_ms", s.minIntervalMs)
            putString("home_lat", s.homeLat?.toString())
            putString("home_lon", s.homeLon?.toString())
            putString("home_utc", s.homeUtc?.toString())
        }
        _settings.value = s
    }

    fun password(): String? = prefs.getString("password_enc", null)?.let { Secrets.decrypt(it) }

    fun setPassword(password: String?) = prefs.edit {
        if (password.isNullOrEmpty()) remove("password_enc") else putString("password_enc", Secrets.encrypt(password))
    }

    fun signOut() {
        setPassword(null)
        update { it.copy(mode = null, token = null, uid = null, expireAt = 0) }
    }
}

/** Per-device spectral channel maps (direct mode), stored as config.yaml-shaped JSON. */
class ChannelMapStore(context: Context) {
    private val prefs = context.getSharedPreferences("kelofred_channels", Context.MODE_PRIVATE)
    private val _maps = MutableStateFlow(readAll())
    val maps: StateFlow<Map<String, ChannelMap>> = _maps.asStateFlow()

    private fun readAll(): Map<String, ChannelMap> = prefs.all.mapNotNull { (did, v) ->
        val m = (v as? String)?.let { runCatching { JsonUtil.parseObject(it) }.getOrNull() } ?: return@mapNotNull null
        did to ChannelMap(m)
    }.toMap()

    fun get(did: String): ChannelMap = _maps.value[did] ?: ChannelMap()

    fun save(did: String, specs: Map<String, ChannelSpec>) {
        val map = ChannelMap(specs)
        prefs.edit { putString(did, JsonUtil.stringify(map.toConfig())) }
        _maps.value = _maps.value + (did to map)
    }

    fun clear(did: String) {
        prefs.edit { remove(did) }
        _maps.value = _maps.value - did
    }
}

/** Automation rules for direct mode (server mode keeps them on the server). */
class RuleStore(context: Context) {
    private val prefs = context.getSharedPreferences("kelofred_rules", Context.MODE_PRIVATE)
    private val _rules = MutableStateFlow(read())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    private fun read(): List<Rule> = runCatching {
        (JsonUtil.parse(prefs.getString("rules", "[]") ?: "[]") as List<*>).mapNotNull { it.asMap()?.let(Rule::fromMap) }
    }.getOrDefault(emptyList())

    private fun write(rules: List<Rule>) {
        prefs.edit { putString("rules", JsonUtil.stringify(rules.map { it.toMap() })) }
        _rules.value = rules
    }

    fun get(name: String): Rule? = _rules.value.firstOrNull { it.name == name }

    fun save(rule: Rule) = write(_rules.value.filterNot { it.name == rule.name } + rule)

    fun delete(name: String) = write(_rules.value.filterNot { it.name == name })
}

/** Continuous scenes that were running, so they can resume after a restart. */
data class ActiveScene(val did: String, val scene: String, val params: Map<String, Any?>)

class ActiveSceneStore(context: Context) {
    private val prefs = context.getSharedPreferences("kelofred_active", Context.MODE_PRIVATE)

    fun read(): List<ActiveScene> = runCatching {
        (JsonUtil.parse(prefs.getString("active", "[]") ?: "[]") as List<*>).mapNotNull { e ->
            val m = e.asMap() ?: return@mapNotNull null
            ActiveScene(m["did"] as String, m["scene"] as String, m["params"].asMap() ?: emptyMap())
        }
    }.getOrDefault(emptyList())

    fun write(scenes: List<ActiveScene>) = prefs.edit {
        putString(
            "active",
            JsonUtil.stringify(scenes.map { mapOf("did" to it.did, "scene" to it.scene, "params" to it.params) }),
        )
    }
}
