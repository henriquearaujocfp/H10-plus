package br.h10plus.wear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Anel na borda da tela redonda: as 5 zonas em arcos coloridos, a zona atual mais grossa,
 * um marcador na posição exata da FC e, por cima, o progresso do "segure para encerrar".
 */
class ZoneRingView(ctx: Context) : View(ctx) {

    var hr = 0
    var holdProgress = 0f   // 0..1

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hold = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
    private val rect = RectF()

    // arco de 300°, começando embaixo à esquerda e terminando embaixo à direita
    private val startAngle = 120f
    private val sweepTotal = 300f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val base = minOf(w, h)
        val thin = base * 0.035f
        val thick = base * 0.07f
        val pad = thick / 2 + base * 0.012f
        rect.set(pad, pad, w - pad, h - pad)

        val zb = WorkoutState.zb
        val lo = (zb[0] - 15).coerceAtLeast(40).toFloat()
        val hi = maxOf(WorkoutState.fcmax, zb[4] + 10).toFloat()
        fun ang(v: Float) = startAngle + sweepTotal * ((v.coerceIn(lo, hi) - lo) / (hi - lo))

        val cur = if (hr > 0) WorkoutState.zoneOf(hr) else -1
        val gap = 1.5f
        for (k in 0..4) {
            val a0 = ang(zb[k].toFloat())
            val a1 = ang(if (k == 4) hi else zb[k + 1].toFloat())
            arc.color = WorkoutState.zoneColors[k]
            arc.strokeWidth = if (k == cur) thick else thin
            arc.alpha = if (cur < 0 || k == cur) 255 else 150
            canvas.drawArc(rect, a0 + gap / 2, (a1 - a0 - gap).coerceAtLeast(0.5f), false, arc)
        }
        // trecho abaixo da Z1
        arc.color = 0xFF484F58.toInt(); arc.strokeWidth = thin; arc.alpha = 255
        canvas.drawArc(rect, startAngle, (ang(zb[0].toFloat()) - startAngle - gap / 2).coerceAtLeast(0f), false, arc)

        // marcador da FC atual
        if (hr > 0) {
            val a = Math.toRadians(ang(hr.toFloat()).toDouble())
            val r = (rect.width() / 2)
            val cx = rect.centerX() + r * Math.cos(a).toFloat()
            val cy = rect.centerY() + r * Math.sin(a).toFloat()
            dot.color = Color.BLACK; canvas.drawCircle(cx, cy, thick * 0.85f, dot)
            dot.color = Color.WHITE; canvas.drawCircle(cx, cy, thick * 0.6f, dot)
        }

        // progresso do encerramento (volta inteira em 5 s)
        if (holdProgress > 0f) {
            hold.strokeWidth = thick * 0.8f
            val inset = thick * 1.6f
            rect.inset(inset, inset)
            canvas.drawArc(rect, -90f, 360f * holdProgress, false, hold)
        }
    }
}
