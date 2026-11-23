package br.h10plus.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Telinha flutuante sobre os outros apps durante o treino (só quando o app não está na tela). */
class BubbleOverlay(base: Context) {
    // Android 11+: janelas sobrepostas devem usar um contexto de janela do tipo overlay
    private val ctx: Context = if (android.os.Build.VERSION.SDK_INT >= 30) try {
        val dm = base.getSystemService(android.hardware.display.DisplayManager::class.java)
        base.createDisplayContext(dm.getDisplay(android.view.Display.DEFAULT_DISPLAY))
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } catch (_: Exception) { base } else base
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var expanded = false

    // compacta
    private lateinit var compact: LinearLayout
    private lateinit var cHr: TextView
    private lateinit var cSub: TextView
    // expandida
    private lateinit var card: LinearLayout
    private lateinit var eHead: TextView
    private lateinit var eHr: TextView
    private lateinit var eZone: TextView
    private lateinit var eBar: ImageView
    private lateinit var eL1: TextView
    private lateinit var eL2: TextView

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    private fun dpi(v: Int) = dp(v.toFloat()).toInt()

    private fun shouldShow(): Boolean {
        val why = when {
            !Settings.canDrawOverlays(ctx) -> "sem permissão"
            System.currentTimeMillis() < LiveHub.previewUntil -> null
            !LiveHub.active -> "sem treino ativo"
            LiveHub.appVisible -> "app na tela"
            LiveHub.bubbleDismissed -> "fechada no ✕"
            !LiveHub.bubbleOn(ctx) -> "desligada"
            else -> null
        }
        if (why != "app na tela") LiveHub.bgReason = why ?: "exibindo"
        return why == null
    }

    fun update() {
        if (!shouldShow()) { remove(); return }
        if (root == null) create()
        render()
    }

    fun remove() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        root = null
        LiveHub.bubbleShown = false
    }

    private fun tv(sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); includeFontPadding = false
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create() {
        compact = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(10), dpi(6), dpi(12), dpi(6))
        }
        cHr = tv(24f, Color.BLACK, true)
        cSub = tv(11f, Color.BLACK).apply { setPadding(dpi(6), 0, 0, 0) }
        compact.addView(cHr); compact.addView(cSub)

        card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(10), dpi(8), dpi(10), dpi(10))
            background = GradientDrawable().apply { cornerRadius = dp(18f); setColor(0xF2121418.toInt()) }
            visibility = View.GONE
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        eHead = tv(11f, 0xFF8A8F98.toInt())
        val close = tv(16f, 0xFFD0D4DA.toInt(), true).apply {
            text = "✕"; setPadding(dpi(12), dpi(2), dpi(2), dpi(2))
            setOnClickListener { LiveHub.bubbleDismissed = true; remove() }
        }
        head.addView(eHead, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(close)
        eHr = tv(36f, Color.WHITE, true).apply { setPadding(0, dpi(2), 0, dpi(2)) }
        eZone = tv(11f, Color.BLACK, true).apply { setPadding(dpi(6), dpi(1), dpi(6), dpi(1)) }
        eBar = ImageView(ctx)
        eL1 = tv(11f, 0xFFD0D4DA.toInt()); eL2 = tv(11f, 0xFFD0D4DA.toInt())
        card.addView(head, LinearLayout.LayoutParams(-1, -2))
        card.addView(eHr)
        card.addView(eZone, LinearLayout.LayoutParams(-2, -2))
        card.addView(eBar, LinearLayout.LayoutParams(-1, dpi(6)).apply { topMargin = dpi(8) })
        card.addView(eL1, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(6) })
        card.addView(eL2)

        val r = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        r.addView(compact); r.addView(card, LinearLayout.LayoutParams(dpi(180), -2))

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val sw = ctx.resources.displayMetrics.widthPixels
            x = LiveHub.prefs(ctx).getInt("bx", sw - dpi(110))
            y = LiveHub.prefs(ctx).getInt("by", dpi(160))
        }

        // arrastar / tocar
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        val touch = View.OnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; startX = p.x; startY = p.y; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!dragging && (abs(dx) > dp(8f) || abs(dy) > dp(8f))) dragging = true
                    if (dragging) { p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); try { wm.updateViewLayout(r, p) } catch (_: Exception) { } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) { expanded = !expanded; render() }
                    else snap(r, p)
                    true
                }
                else -> false
            }
        }
        compact.setOnTouchListener(touch); card.setOnTouchListener(touch)
        eHr.setOnTouchListener(touch); eBar.setOnTouchListener(touch); eL1.setOnTouchListener(touch); eL2.setOnTouchListener(touch); eZone.setOnTouchListener(touch)

        try { wm.addView(r, p); root = r; lp = p; LiveHub.bubbleShown = true }
        catch (e: Exception) { root = null; LiveHub.err("exibir bolha", e) }
    }

    /** Ao soltar, encosta na borda mais próxima. */
    private fun snap(r: View, p: WindowManager.LayoutParams) {
        val sw = ctx.resources.displayMetrics.widthPixels
        val sh = ctx.resources.displayMetrics.heightPixels
        val w = r.width
        p.x = if (p.x + w / 2 < sw / 2) dpi(6) else sw - w - dpi(6)
        p.y = p.y.coerceIn(dpi(30), sh - r.height - dpi(60))
        try { wm.updateViewLayout(r, p) } catch (_: Exception) { }
        LiveHub.prefs(ctx).edit().putInt("bx", p.x).putInt("by", p.y).apply()
    }

    private fun render() {
        val s = LiveHub.s
        val now = System.currentTimeMillis()
        val hr = LiveHub.hr(now)
        val z = LiveHub.zoneOf(hr)
        val el = LiveHub.fmtClock((now - s.optLong("start", now)) / 1000)
        val test = s.optJSONObject("test")
        val left = test?.optDouble("left", 0.0) ?: 0.0
        val alert = test != null && left <= 5.5 && !(test.optBoolean("vt2") && !test.optBoolean("max"))

        // compacta
        val bg = when { alert -> 0xFFF2C230.toInt(); z >= 0 -> LiveHub.ZONE[z]; else -> 0xFF30343B.toInt() }
        val fg = when { alert -> 0xFF3A2A00.toInt(); z >= 0 -> LiveHub.ZONE_DARK[z]; else -> Color.WHITE }
        compact.background = GradientDrawable().apply { cornerRadius = dp(28f); setColor(bg) }
        cHr.setTextColor(fg); cSub.setTextColor(fg)
        cHr.text = if (hr > 0) hr.toString() else "--"
        cSub.text = when {
            alert -> "→ ${test!!.optString("next")}\nem ${kotlin.math.ceil(left).toInt()} s"
            test != null -> "${test.optString("load")}\n${LiveHub.fmtClock(left.toLong())}"
            else -> (if (z >= 0) "Z${z + 1}" else "--") + "\n" + el
        }
        compact.visibility = if (expanded) View.GONE else View.VISIBLE

        // expandida
        card.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) {
            eHead.text = (if (test != null) "Teste · ${test.optString("ph")}" else s.optString("type", "Treino")) + " · " + el
            eHr.text = if (hr > 0) hr.toString() else "--"
            eHr.setTextColor(if (z >= 0) LiveHub.ZONE[z] else Color.WHITE)
            if (z >= 0) {
                eZone.text = LiveHub.ZONE_NAMES[z]; eZone.setTextColor(LiveHub.ZONE_DARK[z])
                eZone.background = GradientDrawable().apply { cornerRadius = dp(4f); setColor(LiveHub.ZONE[z]) }
            } else { eZone.text = if (hr > 0) "abaixo de Z1" else "sem sinal"; eZone.setTextColor(0xFF8A8F98.toInt()); eZone.background = null }
            if (test != null) {
                eBar.visibility = View.GONE
                eL1.text = "Agora: ${test.optString("load")} · faltam ${LiveHub.fmtClock(left.toLong())}"
                eL1.setTextColor(if (alert) 0xFFF2C230.toInt() else 0xFFD0D4DA.toInt())
                eL2.text = if (test.optBoolean("vt2") && !test.optBoolean("max")) "VT2 atingido: pode concluir" else "Próxima: ${test.optString("next")}"
            } else {
                eBar.visibility = View.VISIBLE
                eBar.setImageBitmap(LiveHub.zoneBar(dpi(160), dpi(6), dp(3f)))
                val avg = s.optDouble("avg", 0.0)
                eL1.setTextColor(0xFFD0D4DA.toInt())
                eL1.text = "Média ${if (avg > 0) avg.toInt() else "--"} · TRIMP ${s.optDouble("trimp", 0.0).toInt()}"
                val dist = s.optDouble("dist", 0.0); val spd = s.optDouble("spd", 0.0)
                eL2.text = if (dist > 0) "${LiveHub.num2(dist / 1000)} km" + (if (spd > 0) " · ${LiveHub.num1(spd)} km/h" else "") else "Máx ${s.optInt("max")}"
            }
        }
        root?.let { r -> lp?.let { p -> try { wm.updateViewLayout(r, p) } catch (_: Exception) { } } }
    }
}
