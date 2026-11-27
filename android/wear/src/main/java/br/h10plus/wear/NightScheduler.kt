package br.h10plus.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Calendar

/** Noite automática: liga e desliga o modo noite numa janela diária (definida no app do celular). */
object NightScheduler {
    const val ACT_START = "br.h10plus.wear.NIGHT_START"
    const val ACT_STOP = "br.h10plus.wear.NIGHT_STOP"

    data class Cfg(val auto: Boolean, val start: Int, val end: Int)   // minutos do dia

    fun cfg(ctx: Context): Cfg {
        val json = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("json", null)
        return try {
            val n = JSONObject(json ?: "{}").optJSONObject("night") ?: return Cfg(false, 22 * 60, 10 * 60)
            fun mins(s: String, d: Int) = try { val p = s.split(":"); p[0].toInt() * 60 + p[1].toInt() } catch (_: Exception) { d }
            Cfg(n.optBoolean("auto", false), mins(n.optString("start", "22:00"), 22 * 60), mins(n.optString("end", "10:00"), 10 * 60))
        } catch (_: Exception) { Cfg(false, 22 * 60, 10 * 60) }
    }

    fun inWindow(c: Cfg, now: Calendar = Calendar.getInstance()): Boolean {
        val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (c.start <= c.end) m >= c.start && m < c.end else m >= c.start || m < c.end
    }

    private fun next(minOfDay: Int): Long {
        val c = Calendar.getInstance().apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); set(Calendar.HOUR_OF_DAY, minOfDay / 60); set(Calendar.MINUTE, minOfDay % 60) }
        if (c.timeInMillis <= System.currentTimeMillis() + 5000) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun pi(ctx: Context, action: String) = PendingIntent.getBroadcast(ctx, if (action == ACT_START) 1 else 2,
        Intent(ctx, NightAlarmReceiver::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun set(ctx: Context, at: Long, action: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, action)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (_: Exception) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
    }

    /** Reagenda os alarmes; se agora estiver dentro da janela e nada estiver gravando, começa já. */
    fun apply(ctx: Context, startNowIfInside: Boolean = false) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pi(ctx, ACT_START)); am.cancel(pi(ctx, ACT_STOP))
        val c = cfg(ctx)
        if (!c.auto) return
        set(ctx, next(c.start), ACT_START)
        set(ctx, next(c.end), ACT_STOP)
        if (startNowIfInside && inWindow(c) && !NightService.running) startNight(ctx)
    }

    fun startNight(ctx: Context) {
        if (WorkoutState.phase != WorkoutState.Phase.IDLE && WorkoutState.phase != WorkoutState.Phase.DONE) return
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, NightService::class.java).setAction(NightService.ACTION_START).putExtra("auto", true))
        } catch (e: Exception) { NightService.lastMsg = "Noite automática não iniciou: ${e.message}" }
    }
}

class NightAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            NightScheduler.ACT_START -> if (!NightService.running) NightScheduler.startNight(ctx)
            NightScheduler.ACT_STOP -> if (NightService.running && NightService.auto)
                try { ctx.startService(Intent(ctx, NightService::class.java).setAction(NightService.ACTION_STOP)) } catch (_: Exception) { }
        }
        NightScheduler.apply(ctx, startNowIfInside = intent.action == Intent.ACTION_BOOT_COMPLETED)
    }
}
