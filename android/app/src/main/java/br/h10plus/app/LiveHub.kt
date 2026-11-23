package br.h10plus.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import android.content.Intent
import org.json.JSONObject

/** Estado do treino ao vivo, compartilhado entre o site (resumo), o SDK da Polar (FC) e o serviço da notificação/bolha. */
object LiveHub {
    val ZONE = intArrayOf(0xFF4C8BF5.toInt(), 0xFF2FBF71.toInt(), 0xFFF2C230.toInt(), 0xFFF2803A.toInt(), 0xFFE5484D.toInt())
    val ZONE_DARK = intArrayOf(0xFF0B2A5E.toInt(), 0xFF0B3D22.toInt(), 0xFF3A2A00.toInt(), 0xFF4A2000.toInt(), 0xFF4A0E10.toInt())
    val ZONE_NAMES = arrayOf("Z1 Recuperação", "Z2 Aeróbico", "Z3 Tempo", "Z4 Limiar", "Z5 Máximo")

    @Volatile var active = false
    @Volatile var s: JSONObject = JSONObject()
    @Volatile var nativeHr = 0
    @Volatile var nativeHrAt = 0L
    @Volatile var appVisible = false
    @Volatile var serviceRunning = false
    @Volatile var bubbleDismissed = false
    @Volatile var bubbleShown = false
    @Volatile var previewUntil = 0L          // "Testar telinha": mostra por alguns segundos mesmo sem treino
    @Volatile var lastErr: String? = null
    @Volatile var bgReason = ""             // último estado avaliado com o app fora da tela
    fun err(where: String, e: Throwable) { lastErr = "$where: ${e.javaClass.simpleName} ${e.message ?: ""}".take(200) }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("h10live", Context.MODE_PRIVATE)
    fun bubbleOn(ctx: Context) = prefs(ctx).getBoolean("bubble", true)
    fun setBubble(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("bubble", on).apply()

    fun hr(now: Long = System.currentTimeMillis()): Int {
        if (now - nativeHrAt < 5000 && nativeHr > 0) return nativeHr
        return s.optInt("hr", 0)
    }

    fun zoneOf(hr: Int): Int {
        val zb = s.optJSONArray("zb") ?: return -1
        if (hr <= 0 || zb.length() != 5) return -1
        var z = 0; for (k in 0 until 5) if (hr >= zb.optInt(k)) z = k
        return if (hr < zb.optInt(0)) -1 else z
    }

    /** Recebe o resumo do site; liga/desliga o serviço. */
    fun update(ctx: Context, json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val on = o.optBoolean("active", false)
        if (on) {
            if (!active) bubbleDismissed = false
            s = o; active = true
            if (!serviceRunning) startService(ctx)
        } else if (active) {
            active = false
            if (System.currentTimeMillis() < previewUntil) return
            try { ctx.stopService(Intent(ctx, WorkoutFgService::class.java)) } catch (_: Exception) { }
        }
    }

    fun startService(ctx: Context) {
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, WorkoutFgService::class.java)) }
        catch (e: Exception) { err("iniciar serviço", e) }
    }

    fun fmtClock(sec: Long): String {
        val h = sec / 3600; val m = sec % 3600 / 60; val ss = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }

    fun num1(v: Double) = "%.1f".format(v).replace('.', ',')
    fun num2(v: Double) = "%.2f".format(v).replace('.', ',')

    /** Barra com o tempo em cada zona. */
    fun zoneBar(wPx: Int, hPx: Int, radius: Float): Bitmap {
        val b = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val zs = s.optJSONArray("zs")
        val vals = DoubleArray(5) { zs?.optDouble(it, 0.0) ?: 0.0 }
        val tot = vals.sum()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, wPx.toFloat(), hPx.toFloat()), radius, radius, Path.Direction.CW) })
        if (tot <= 0) { p.color = 0x33888888; c.drawRect(0f, 0f, wPx.toFloat(), hPx.toFloat(), p); return b }
        var x = 0f
        for (z in 0 until 5) {
            val w = (vals[z] / tot * wPx).toFloat(); if (w <= 0f) continue
            p.color = ZONE[z]; c.drawRect(x, 0f, x + w, hPx.toFloat(), p); x += w
        }
        return b
    }
}
