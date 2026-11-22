package br.h10plus.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject

/**
 * Widget da tela inicial: FC ao vivo (cor da zona), treino em andamento (cronômetro),
 * prontidão do dia, noite do relógio, último treino e atalhos Cardio / Musculação / Repouso.
 * O site manda o resumo (widgetUpdate); a FC ao vivo vem direto do SDK da Polar.
 */
class H10Widget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)

    companion object {
        private val ZONE_COLORS = intArrayOf(0xFF4C8BF5.toInt(), 0xFF2FBF71.toInt(), 0xFFF2C230.toInt(), 0xFFF2803A.toInt(), 0xFFE5484D.toInt())
        private val ZONE_NAMES = arrayOf("Z1 Recuperação", "Z2 Aeróbico", "Z3 Tempo", "Z4 Limiar", "Z5 Máximo")
        private const val GREY = 0xFF8A8F98.toInt()
        private var lastLive = 0L

        fun prefs(ctx: Context) = ctx.getSharedPreferences("h10widget", Context.MODE_PRIVATE)

        private fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, H10Widget::class.java))

        /** Resumo enviado pelo site. */
        fun setSummary(ctx: Context, json: String) {
            prefs(ctx).edit().putString("sum", json).apply()
            refresh(ctx)
            WeekWidget.refresh(ctx)
            Vo2Widget.refresh(ctx)
        }

        /** FC ao vivo da cinta (0 = sem sinal). Atualiza no máximo a cada 2 s. */
        fun live(ctx: Context, hr: Int, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastLive < 2000) return
            lastLive = now
            prefs(ctx).edit().putInt("hr", hr).putLong("hrT", now).apply()
            if (ids(ctx).isNotEmpty()) refresh(ctx)
        }

        private fun link(ctx: Context, q: String, code: Int): PendingIntent {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("h10plus://treino?$q"), ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun refresh(ctx: Context) {
            val all = ids(ctx); if (all.isEmpty()) return
            val p = prefs(ctx)
            val s = try { JSONObject(p.getString("sum", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
            val v = RemoteViews(ctx.packageName, R.layout.widget_h10)

            // FC ao vivo
            val hrT = p.getLong("hrT", 0)
            val hr = if (System.currentTimeMillis() - hrT < 15000) p.getInt("hr", 0) else 0
            val zb = s.optJSONArray("zb")
            var z = -1
            if (hr > 0 && zb != null && zb.length() == 5) { z = 0; for (k in 0 until 5) if (hr >= zb.optInt(k)) z = k }
            v.setTextViewText(R.id.wHr, if (hr > 0) hr.toString() else "--")
            v.setTextColor(R.id.wHr, if (z >= 0) ZONE_COLORS[z] else if (hr > 0) 0xFFFFFFFF.toInt() else GREY)
            if (z >= 0) {
                v.setTextViewText(R.id.wZone, ZONE_NAMES[z])
                v.setInt(R.id.wZone, "setBackgroundColor", ZONE_COLORS[z])
                v.setViewVisibility(R.id.wZone, View.VISIBLE)
            } else {
                v.setTextViewText(R.id.wZone, if (hr > 0) "sem zonas" else "cinta desconectada")
                v.setInt(R.id.wZone, "setBackgroundColor", 0x00000000)
                v.setViewVisibility(R.id.wZone, View.VISIBLE)
            }

            // treino em andamento
            val wk = s.optJSONObject("wk")
            if (wk != null) {
                val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - wk.optLong("start"))
                v.setChronometer(R.id.wChrono, base, null, true)
                v.setViewVisibility(R.id.wChrono, View.VISIBLE)
                v.setTextViewText(R.id.wWkType, wk.optString("type", "Treino"))
                v.setViewVisibility(R.id.wWkType, View.VISIBLE)
                v.setViewVisibility(R.id.wButtons, View.GONE)
                v.setViewVisibility(R.id.wOpen, View.VISIBLE)
            } else {
                v.setChronometer(R.id.wChrono, SystemClock.elapsedRealtime(), null, false)
                v.setViewVisibility(R.id.wChrono, View.GONE)
                v.setViewVisibility(R.id.wWkType, View.GONE)
                v.setViewVisibility(R.id.wButtons, View.VISIBLE)
                v.setViewVisibility(R.id.wOpen, View.GONE)
            }

            // linhas de resumo
            v.setTextViewText(R.id.wReady, s.optString("ready", "Prontidão: abra o app"))
            v.setTextColor(R.id.wReady, when (s.optString("readyLvl")) { "bad" -> 0xFFE5484D.toInt(); "good" -> 0xFF2FBF71.toInt(); else -> 0xFFD0D4DA.toInt() })
            val night = s.optString("night", "")
            v.setTextViewText(R.id.wNight, night)
            v.setViewVisibility(R.id.wNight, if (night.isEmpty()) View.GONE else View.VISIBLE)
            v.setTextColor(R.id.wNight, if (s.optString("nightLvl") == "bad") 0xFFE5484D.toInt() else 0xFFD0D4DA.toInt())
            v.setTextViewText(R.id.wLast, s.optString("last", ""))

            // toques
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.wRoot, open)
            v.setOnClickPendingIntent(R.id.wOpen, open)
            v.setOnClickPendingIntent(R.id.wCardio, link(ctx, "acao=start&tipo=cardio&origem=widget", 1))
            v.setOnClickPendingIntent(R.id.wForca, link(ctx, "acao=start&tipo=forca&origem=widget", 2))
            v.setOnClickPendingIntent(R.id.wRest, link(ctx, "acao=repouso&origem=widget", 3))

            AppWidgetManager.getInstance(ctx).updateAppWidget(all, v)
        }
    }
}
