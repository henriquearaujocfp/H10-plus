package br.h10plus.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Base comum: lê o resumo enviado pelo site e desenha o gráfico num bitmap do tamanho do widget. */
abstract class ChartWidgetBase : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx, javaClass)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: Bundle) = render(ctx, javaClass)

    companion object {
        val ZONE = intArrayOf(0xFF4C8BF5.toInt(), 0xFF2FBF71.toInt(), 0xFFF2C230.toInt(), 0xFFF2803A.toInt(), 0xFFE5484D.toInt())
        const val GREY = 0xFF8A8F98.toInt()
        const val LIGHT = 0xFFD0D4DA.toInt()
        const val TRACK = 0xFF262A31.toInt()
        const val BG = 0xFF121418.toInt()
        const val CAF = 0xFFF2C230.toInt()
        val zone: ZoneId get() = ZoneId.systemDefault()
        private val fmt = DateTimeFormatter.ofPattern("dd/MM")

        fun dm(d: LocalDate): String = d.format(fmt)
        fun num(v: Double, dec: Int = 1) = String.format(Locale("pt", "BR"), "%.${dec}f", v)

        fun summary(ctx: Context): JSONObject =
            try { JSONObject(H10Widget.prefs(ctx).getString("sum", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

        fun render(ctx: Context, cls: Class<*>) {
            when (cls) {
                WeekWidget::class.java -> WeekWidget.refresh(ctx)
                Vo2Widget::class.java -> Vo2Widget.refresh(ctx)
            }
        }

        /** Tamanho do gráfico em px a partir das opções do widget (dp). */
        fun chartSize(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val portrait = ctx.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
            var wDp = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            var hDp = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            if (wDp <= 0) wDp = 300
            if (hDp <= 0) hDp = 140
            val d = ctx.resources.displayMetrics.density
            val w = ((wDp - 24) * d).roundToInt().coerceIn(200, 1400)
            val h = ((hDp - 24 - 22) * d).roundToInt().coerceIn(120, 900)   // menos padding e cabeçalho
            return w to h
        }

        fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 10,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun paint(color: Int, sp: Float = 0f, ctx: Context? = null) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            if (sp > 0 && ctx != null) textSize = sp * ctx.resources.displayMetrics.scaledDensity
        }
    }
}

/** Cardio da semana (seg–dom): minutos por zona em barras empilhadas, TRIMP do dia, média diária das 4 semanas anteriores. */
class WeekWidget : ChartWidgetBase() {
    companion object {
        private val DAYS = arrayOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, WeekWidget::class.java)); if (ids.isEmpty()) return
            val s = summary(ctx)
            val cardio = s.optJSONObject("cardio") ?: JSONObject()
            val today = LocalDate.now(zone)
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            fun day(d: LocalDate): DoubleArray {
                val a = cardio.optJSONArray(d.toString()) ?: return DoubleArray(7)
                return DoubleArray(7) { a.optDouble(it, 0.0) }
            }
            val week = (0 until 7).map { day(monday.plusDays(it.toLong())) }
            // média diária de minutos nas 4 semanas anteriores (28 dias antes da segunda)
            var prevMin = 0.0
            for (i in 1..28) prevMin += day(monday.minusDays(i.toLong())).take(5).sum()
            val avg = prevMin / 28.0
            val trimp = week.sumOf { it[5] }
            val nDays = week.count { it.take(5).sum() >= 1 }
            val z2 = week.sumOf { it[1] }.roundToInt()
            val goal = s.optInt("z2goal", 0)
            val vo2 = week.any { it[6] >= 1 }
            val sub = "$nDays/7 dias · Z2 $z2" + (if (goal > 0) "/$goal" else "") + " min · 4×4 " + (if (vo2) "✓" else "—") + " · TRIMP ${trimp.roundToInt()}"

            for (id in ids) {
                val (w, h) = chartSize(ctx, mgr, id)
                val bmp = draw(ctx, w, h, week, avg, today.dayOfWeek.value - 1)
                val v = RemoteViews(ctx.packageName, R.layout.widget_chart)
                v.setTextViewText(R.id.cTitle, "Cardio da semana")
                v.setTextViewText(R.id.cSub, sub)
                v.setImageViewBitmap(R.id.cImg, bmp)
                v.setOnClickPendingIntent(R.id.cRoot, openApp(ctx))
                mgr.updateAppWidget(id, v)
            }
        }

        private fun draw(ctx: Context, w: Int, h: Int, week: List<DoubleArray>, avg: Double, todayIdx: Int): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            val dpx = ctx.resources.displayMetrics.density
            val small = paint(GREY, 11f, ctx).apply { textAlign = Paint.Align.CENTER }
            val val_ = paint(LIGHT, 11f, ctx).apply { textAlign = Paint.Align.CENTER }
            val legendH = small.textSize * 1.7f
            val labelH = small.textSize * 1.5f
            val topPad = val_.textSize * 1.3f
            val chartTop = topPad
            val chartBot = h - legendH - labelH
            val gap = 6 * dpx
            val colW = (w - gap * 6) / 7f
            val maxMin = max(30.0, max(week.maxOf { it.take(5).sum() }, avg * 1.2))
            val scale = (chartBot - chartTop) / maxMin.toFloat()
            val seg = Paint(Paint.ANTI_ALIAS_FLAG)
            for (i in 0 until 7) {
                val x0 = i * (colW + gap); val x1 = x0 + colW
                val tot = week[i].take(5).sum()
                if (tot >= 1) {
                    var y = chartBot
                    c.save()
                    val total = RectF(x0, chartBot - (tot * scale).toFloat(), x1, chartBot)
                    val clip = android.graphics.Path().apply { addRoundRect(total, 4 * dpx, 4 * dpx, android.graphics.Path.Direction.CW) }
                    c.clipPath(clip)
                    for (z in 0 until 5) {
                        val hz = (week[i][z] * scale).toFloat(); if (hz <= 0f) continue
                        seg.color = ZONE[z]; c.drawRect(x0, y - hz, x1, y, seg); y -= hz
                    }
                    c.restore()
                    if (week[i][5] >= 1) c.drawText(week[i][5].roundToInt().toString(), (x0 + x1) / 2, total.top - 3 * dpx, val_)
                } else if (i <= todayIdx) {
                    seg.color = TRACK; c.drawRoundRect(RectF(x0, chartBot - 3 * dpx, x1, chartBot), 2 * dpx, 2 * dpx, seg)
                }
                val lbl = if (i == todayIdx) "Hoje" else DAYS[i]
                val p = if (i == todayIdx) paint(0xFFFFFFFF.toInt(), 11f, ctx).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true } else small
                c.drawText(lbl, (x0 + x1) / 2, chartBot + labelH * 0.85f, p)
            }
            // média das 4 semanas anteriores
            if (avg >= 1) {
                val ya = chartBot - (avg * scale).toFloat()
                val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY; strokeWidth = 1.2f * dpx; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(5 * dpx, 4 * dpx), 0f) }
                c.drawLine(0f, ya, w.toFloat(), ya, dash)
            }
            // legenda
            val ly = h - legendH * 0.3f
            val lp = paint(GREY, 11f, ctx)
            var lx = 0f
            val sq = 8 * dpx
            for (z in 0 until 5) {
                seg.color = ZONE[z]; c.drawRoundRect(RectF(lx, ly - sq, lx + sq, ly), 2 * dpx, 2 * dpx, seg)
                lx += sq + 3 * dpx; val t = "Z${z + 1}"; c.drawText(t, lx, ly, lp); lx += lp.measureText(t) + 8 * dpx
            }
            val avgTxt = if (avg >= 1) "- - média 4 sem. (${avg.roundToInt()} min/dia)" else "sem média ainda"
            lp.textAlign = Paint.Align.RIGHT; c.drawText(avgTxt, w.toFloat(), ly, lp)
            return b
        }
    }
}

/** VO2máx nos últimos 30 dias: testes guiados (linha branca) e estimativa diária pela razão de FC (pontos cinza). */
class Vo2Widget : ChartWidgetBase() {
    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, Vo2Widget::class.java)); if (ids.isEmpty()) return
            val s = summary(ctx)
            val now = System.currentTimeMillis(); val from = now - 30L * 86400000
            fun pts(a: JSONArray?): List<Pair<Long, Double>> {
                if (a == null) return emptyList()
                return (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { it.optLong(0) to it.optDouble(1) } }
                    .filter { it.first >= from && it.second in 15.0..90.0 }.sortedBy { it.first }
            }
            val tests = pts(s.optJSONArray("vo2t")); val uth = pts(s.optJSONArray("vo2u"))
            val cafSet = HashSet<Long>()
            s.optJSONArray("vo2t")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { if (it.optInt(2) == 1) cafSet.add(it.optLong(0)) } }
            val sub = when {
                tests.isNotEmpty() -> {
                    val last = tests.last().second
                    val d = if (tests.size >= 2) last - tests.first().second else null
                    "${num(last)} ml/kg/min" + (d?.let { " · ${if (it >= 0) "+" else ""}${num(it)}" } ?: "")
                }
                uth.isNotEmpty() -> "sem teste em 30 dias · razão FC ${num(uth.last().second)}"
                else -> "sem dados em 30 dias"
            }
            for (id in ids) {
                val (w, h) = chartSize(ctx, mgr, id)
                val v = RemoteViews(ctx.packageName, R.layout.widget_chart)
                v.setTextViewText(R.id.cTitle, "VO2máx · 30 dias")
                v.setTextViewText(R.id.cSub, sub)
                v.setImageViewBitmap(R.id.cImg, draw(ctx, w, h, tests, uth, from, now, cafSet))
                v.setOnClickPendingIntent(R.id.cRoot, openApp(ctx))
                mgr.updateAppWidget(id, v)
            }
        }

        private fun draw(ctx: Context, w: Int, h: Int, tests: List<Pair<Long, Double>>, uth: List<Pair<Long, Double>>, from: Long, to: Long, caf: Set<Long>): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            val dpx = ctx.resources.displayMetrics.density
            val small = paint(GREY, 10f, ctx)
            val legendH = small.textSize * 1.9f
            val axisH = small.textSize * 1.5f
            val padL = small.measureText("00") + 6 * dpx
            val top = 6 * dpx; val bot = h - legendH - axisH
            val all = tests.map { it.second } + uth.map { it.second }
            if (all.isEmpty()) {
                val p = paint(GREY, 12f, ctx).apply { textAlign = Paint.Align.CENTER }
                c.drawText("Faça um teste guiado ou medições de repouso", w / 2f, (top + bot) / 2, p)
                return b
            }
            var lo = Math.floor(all.minOrNull()!! - 1).toInt(); var hi = Math.ceil(all.maxOrNull()!! + 1).toInt()
            if (hi - lo < 4) { val m = (hi + lo) / 2; lo = m - 2; hi = m + 2 }
            fun x(t: Long) = padL + (t - from).toFloat() / (to - from) * (w - padL - 6 * dpx)
            fun y(v: Double) = top + ((hi - v) / (hi - lo)).toFloat() * (bot - top)
            val grid = Paint().apply { color = TRACK; strokeWidth = 1f }
            val step = max(1, ((hi - lo) / 3.0).roundToInt())
            var g = lo
            while (g <= hi) { val yy = y(g.toDouble()); c.drawLine(padL, yy, w.toFloat(), yy, grid); c.drawText(g.toString(), 0f, yy + small.textSize / 3, small); g += step }
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREY }
            for ((t, v) in uth) c.drawCircle(x(t), y(v), 2f * dpx, dot)
            if (tests.size >= 2) {
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 2 * dpx; style = Paint.Style.STROKE }
                val path = android.graphics.Path()
                tests.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
                c.drawPath(path, line)
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG }
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 2 * dpx; style = Paint.Style.STROKE }
            val cafRing = Paint(ring).apply { color = CAF }
            for ((t, v) in tests) { c.drawCircle(x(t), y(v), 4 * dpx, fill); c.drawCircle(x(t), y(v), 4 * dpx, if (t in caf) cafRing else ring) }
            // eixo de datas
            val f = Instant.ofEpochMilli(from).atZone(zone).toLocalDate(); val l = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
            c.drawText(dm(f), padL, bot + axisH * 0.85f, small)
            small.textAlign = Paint.Align.RIGHT; c.drawText(dm(l), w.toFloat(), bot + axisH * 0.85f, small); small.textAlign = Paint.Align.LEFT
            // legenda
            val ly = h - legendH * 0.3f
            val lp = paint(GREY, 11f, ctx)
            var lx = 4 * dpx
            c.drawCircle(lx, ly - lp.textSize / 3, 4 * dpx, fill); c.drawCircle(lx, ly - lp.textSize / 3, 4 * dpx, ring)
            lx += 9 * dpx; c.drawText("Teste guiado", lx, ly, lp); lx += lp.measureText("Teste guiado") + 14 * dpx
            c.drawCircle(lx, ly - lp.textSize / 3, 3 * dpx, dot); lx += 7 * dpx
            c.drawText("Razão de FC (repouso)", lx, ly, lp)
            if (tests.any { it.first in caf }) {
                lx += lp.measureText("Razão de FC (repouso)") + 14 * dpx
                c.drawCircle(lx, ly - lp.textSize / 3, 4 * dpx, fill); c.drawCircle(lx, ly - lp.textSize / 3, 4 * dpx, cafRing)
                lx += 9 * dpx; c.drawText("Cafeína", lx, ly, lp)
            }
            return b
        }
    }
}
