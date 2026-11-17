package com.kelofred.keloray.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.kelofred.keloray.KeloApp
import com.kelofred.keloray.core.asDouble
import com.kelofred.keloray.data.Mode
import com.kelofred.keloray.data.Rule
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The time part of a [Rule]: a daily "HH:MM" (`at`) or an APScheduler-style
 * cron with hour / minute / day_of_week, which is what keloray-control accepts.
 */
data class RuleTime(val hour: Int, val minute: Int, val days: Set<DayOfWeek> = DayOfWeek.entries.toSet()) {

    fun next(after: ZonedDateTime): ZonedDateTime {
        var d = after.toLocalDate()
        repeat(8) {
            if (d.dayOfWeek in days) {
                val t = ZonedDateTime.of(d, LocalTime.of(hour, minute), after.zone)
                if (t.isAfter(after)) return t
            }
            d = d.plusDays(1)
        }
        throw IllegalStateException("rule has no days")
    }

    fun describe(): String = "%02d:%02d · %s".format(hour, minute, describeDays(days))

    /** The (at, cron) pair to store on a [Rule]. */
    fun toRuleFields(): Pair<String?, Map<String, Any?>> =
        if (days.size == 7) {
            "%02d:%02d".format(hour, minute) to emptyMap()
        } else {
            null to linkedMapOf(
                "hour" to hour,
                "minute" to minute,
                "day_of_week" to DayOfWeek.entries.filter { it in days }.joinToString(",") { CRON_NAMES[it.value - 1] },
            )
        }

    companion object {
        private val CRON_NAMES = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

        fun describeDays(days: Set<DayOfWeek>): String = when {
            days.size == 7 -> "Every day"
            days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
            days == DayOfWeek.entries.take(5).toSet() -> "Weekdays"
            else -> DayOfWeek.entries.filter { it in days }
                .joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        }

        /** null if the rule's schedule can't be run by the phone (e.g. "every 5 minutes"). */
        fun of(rule: Rule): RuleTime? {
            rule.at?.takeIf { it.isNotBlank() }?.let { at ->
                val parts = at.trim().split(":")
                val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
                val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
                return if (h in 0..23 && m in 0..59) RuleTime(h, m) else null
            }
            val c = rule.cron
            val h = intField(c["hour"]) ?: return null
            val m = if (c["minute"] == null) 0 else intField(c["minute"]) ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            val days = parseDays(c["day_of_week"]) ?: return null
            if (days.isEmpty()) return null
            return RuleTime(h, m, days)
        }

        private fun intField(v: Any?): Int? = v.asDouble()?.toInt() ?: (v as? String)?.trim()?.toIntOrNull()

        /** APScheduler day_of_week: "mon-fri", "sat,sun", "0-4" (0 = Monday), "*". */
        fun parseDays(spec: Any?): Set<DayOfWeek>? {
            if (spec == null) return DayOfWeek.entries.toSet()
            val s = spec.toString().trim().lowercase()
            if (s.isEmpty() || s == "*") return DayOfWeek.entries.toSet()
            fun one(t: String): Int? {
                val tt = t.trim()
                tt.toIntOrNull()?.let { return if (it in 0..6) it else null }
                val i = CRON_NAMES.indexOf(tt.take(3))
                return if (i >= 0) i else null
            }
            val out = mutableSetOf<DayOfWeek>()
            for (part in s.split(",")) {
                if ("-" in part) {
                    val (a, b) = part.split("-", limit = 2)
                    val from = one(a) ?: return null
                    val to = one(b) ?: return null
                    var i = from
                    while (true) {
                        out += DayOfWeek.of(i + 1)
                        if (i == to) break
                        i = (i + 1) % 7
                    }
                } else {
                    out += DayOfWeek.of((one(part) ?: return null) + 1)
                }
            }
            return out
        }
    }
}

/** Arms one exact alarm per enabled rule (direct mode). */
object ScheduleAlarms {
    private const val TAG = "ScheduleAlarms"

    private fun pendingIntent(context: Context, name: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AlarmReceiver::class.java).setData(("kelofred://rule/" + Uri.encode(name)).toUri()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    /** Cancel everything previously armed and arm the current enabled rules. */
    fun sync(context: Context, rules: List<Rule>) {
        val am = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences("kelofred_alarms", Context.MODE_PRIVATE)
        prefs.getStringSet("names", emptySet())?.forEach { am.cancel(pendingIntent(context, it)) }
        val armed = mutableSetOf<String>()
        for (rule in rules) {
            if (arm(context, am, rule)) armed += rule.name
        }
        prefs.edit { putStringSet("names", armed) }
    }

    fun armNext(context: Context, rule: Rule) {
        arm(context, context.getSystemService(AlarmManager::class.java), rule)
    }

    private fun arm(context: Context, am: AlarmManager, rule: Rule): Boolean {
        if (!rule.enabled) return false
        val time = RuleTime.of(rule) ?: return false
        val at = time.next(ZonedDateTime.now()).toInstant().toEpochMilli()
        val pi = pendingIntent(context, rule.name)
        try {
            if (canExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm not permitted, falling back to inexact", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        return true
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.data?.lastPathSegment ?: return
        val graph = (context.applicationContext as KeloApp).graph
        val rule = graph.rules.get(name) ?: return
        if (graph.settings.current.mode == Mode.DIRECT && rule.enabled) {
            SceneService.startScene(context, rule.did, rule.scene, rule.params, reason = rule.name)
        }
        ScheduleAlarms.armNext(context, rule)
    }
}

/** Re-arms alarms after reboot / app update / clock changes and resumes continuous scenes. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as KeloApp).graph
        if (graph.settings.current.mode != Mode.DIRECT) return
        ScheduleAlarms.sync(context, graph.rules.rules.value)
        val action = intent.action
        if ((action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) &&
            graph.activeScenes.read().isNotEmpty()
        ) {
            SceneService.restore(context)
        }
    }
}
