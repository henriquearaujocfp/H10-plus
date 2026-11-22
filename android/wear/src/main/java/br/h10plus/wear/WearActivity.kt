package br.h10plus.wear

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Tela do relógio.
 * - Antes do treino: tipo (cardio/musculação), GPS e Iniciar.
 * - Durante o treino: fundo na cor da zona, anel de zonas na borda, FC grande, toque BLOQUEADO.
 *   Encerrar = segurar o botão Voltar por 5 s (o mesmo botão que encerra exercícios no Samsung Health).
 */
class WearActivity : ComponentActivity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var idleView: ScrollView
    private lateinit var runView: FrameLayout

    private lateinit var ring: ZoneRingView
    private lateinit var tvTop: TextView
    private lateinit var tvZone: TextView
    private lateinit var tvHr: TextView
    private lateinit var tvBottom: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnSkip: TextView
    private lateinit var btnHoldTouch: TextView
    private lateinit var btnResume: TextView

    private lateinit var tvStatus: TextView
    private lateinit var tvIdleHr: TextView
    private lateinit var btnCardio: TextView
    private lateinit var btnForca: TextView
    private lateinit var btnGps: TextView
    private lateinit var btnStart: TextView
    private lateinit var tvMsg: TextView

    private var holdAnim: ValueAnimator? = null
    private var touchFallbackUntil = 0L
    private var lockToastUntil = 0L
    @Volatile private var ambient = false

    // se o relógio escurecer a tela (pulso abaixado, mão sobre a tela), o app continua visível em modo econômico
    private val ambientObserver by lazy {
        AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) { ambient = true; render() }
            override fun onExitAmbient() { ambient = false; render() }
            override fun onUpdateAmbient() { render() }
        })
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasBle()) service(WorkoutService.ACTION_CONNECT)
        else WorkoutState.message = "Permita Bluetooth (Dispositivos próximos) nas configurações do app."
    }

    private val tick = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 500) }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun pill(text: String, bg: Int, fg: Int = Color.WHITE, size: Float = 14f) = TextView(this).apply {
        this.text = text
        setTextColor(fg)
        textSize = size
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(bg) }
    }

    private fun label(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        includeFontPadding = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkoutState.loadConfig(this)
        val sw = resources.displayMetrics.widthPixels
        val inset = (sw * 0.12).toInt()

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // ---------- tela de treino ----------
        runView = FrameLayout(this)
        ring = ZoneRingView(this)
        runView.addView(ring, FrameLayout.LayoutParams(-1, -1))
        val runCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        tvTop = label(14f, bold = true)
        tvZone = label(17f, bold = true)
        tvHr = label(10f, bold = true).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sw * 0.37f)
            letterSpacing = -0.03f
        }
        tvBottom = label(16f, bold = true).apply { setPadding(dp(8), dp(2), dp(8), dp(3)) }
        tvHint = label(10f)
        btnSkip = pill("Pular", 0x66000000, size = 12f)
        btnSkip.setOnClickListener { service(WorkoutService.ACTION_SKIP_RECOVERY) }
        val holdSize = (sw * 0.40f).toInt()
        btnHoldTouch = TextView(this).apply {
            text = "SEGURE\npara encerrar"
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE5484D.toInt()) }
        }
        btnHoldTouch.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> startHold()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHold()
            }
            true
        }
        runCol.addView(tvTop)
        runCol.addView(tvZone, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        runCol.addView(tvHr, LinearLayout.LayoutParams(-2, -2).apply { topMargin = -dp(4) })
        runCol.addView(tvBottom, LinearLayout.LayoutParams(-2, -2).apply { topMargin = -dp(9) })
        runCol.addView(tvHint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        runCol.addView(btnSkip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        runCol.addView(btnHoldTouch, LinearLayout.LayoutParams(holdSize, holdSize).apply { topMargin = dp(6); gravity = Gravity.CENTER_HORIZONTAL })
        btnResume = pill("Continuar", 0xCC21262D.toInt(), size = 12f)
        btnResume.setOnClickListener { touchFallbackUntil = 0; cancelHold(); render() }
        runCol.addView(btnResume, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6); gravity = Gravity.CENTER_HORIZONTAL })
        runView.addView(runCol, FrameLayout.LayoutParams(-1, -1).apply { setMargins(inset, inset / 2, inset, inset / 2) })

        // ---------- tela inicial ----------
        idleView = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(inset, dp(22), inset, dp(30))
        }
        tvStatus = label(12f, 0xFFC9D1D9.toInt())
        tvIdleHr = label(40f, bold = true)
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnCardio = pill("Cardio", 0xFF58A6FF.toInt(), Color.BLACK, 12f)
        btnForca = pill("Musculação", 0xFF21262D.toInt(), size = 12f)
        btnCardio.setOnClickListener { WorkoutState.type = "cardio"; WorkoutState.useGps = true; render() }
        btnForca.setOnClickListener { WorkoutState.type = "forca"; WorkoutState.useGps = false; render() }
        typeRow.addView(btnCardio, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(3) })
        typeRow.addView(btnForca, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3) })
        btnGps = pill("GPS ligado", 0xFF21262D.toInt(), size = 12f)
        btnGps.setOnClickListener { WorkoutState.useGps = !WorkoutState.useGps; render() }
        btnStart = pill("▶ Iniciar", 0xFF2FBF71.toInt(), Color.BLACK, 16f)
        btnStart.setOnClickListener { startWorkout() }
        tvMsg = label(11f, 0xFFFFD28A.toInt())
        val tvInfo = label(10f, 0xFF8B949E.toInt()).apply {
            text = "No treino a tela fica bloqueada. Para encerrar: Voltar e segure o botão vermelho."
        }
        col.addView(tvStatus)
        col.addView(tvIdleHr, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        col.addView(typeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(btnGps, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        col.addView(btnStart, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(tvMsg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        col.addView(tvInfo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        idleView.addView(col)

        root.addView(idleView, FrameLayout.LayoutParams(-1, -1))
        root.addView(runView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // Voltar por gesto/sistema: durante o treino não sai do app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inWorkout()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        lifecycle.addObserver(ambientObserver)
        loadPhoneConfig()
        requestPermsAndConnect()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** h10plus://treino?acao=start|stop&sid=...&t=...&tipo=... enviado pelo app do celular. */
    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "h10plus") return
        val sid = uri.getQueryParameter("sid") ?: ""
        when (uri.getQueryParameter("acao")) {
            "start" -> {
                if (inWorkout()) return
                WorkoutState.type = if (uri.getQueryParameter("tipo") == "forca") "forca" else "cardio"
                WorkoutState.useGps = WorkoutState.type == "cardio"
                if (!hasBle()) { requestPermsAndConnect(); return }
                vibrate(120)
                val i = Intent(this, WorkoutService::class.java).setAction(WorkoutService.ACTION_START)
                    .putExtra(WorkoutService.EXTRA_REMOTE, true)
                    .putExtra(WorkoutService.EXTRA_SID, sid)
                    .putExtra(WorkoutService.EXTRA_T, uri.getQueryParameter("t")?.toLongOrNull() ?: 0L)
                try { ContextCompat.startForegroundService(this, i) } catch (e: Exception) { WorkoutState.message = "Erro: ${e.message}" }
            }
            "stop" -> {
                if (!running()) return
                if (sid.isNotEmpty() && WorkoutState.sid.isNotEmpty() && sid != WorkoutState.sid) return
                vibrate(400)
                val i = Intent(this, WorkoutService::class.java).setAction(WorkoutService.ACTION_STOP)
                    .putExtra(WorkoutService.EXTRA_REMOTE, true)
                try { ContextCompat.startForegroundService(this, i) } catch (_: Exception) { }
            }
        }
        setIntent(Intent())
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    override fun onDestroy() {
        val ph = WorkoutState.phase
        if (ph == WorkoutState.Phase.IDLE || ph == WorkoutState.Phase.DONE) {
            try { stopService(Intent(this, WorkoutService::class.java)) } catch (_: Exception) { }
            WorkoutState.connState = "desconectado"
        }
        super.onDestroy()
    }

    private fun running() = WorkoutState.phase == WorkoutState.Phase.RUNNING
    private fun inWorkout() = WorkoutState.phase == WorkoutState.Phase.RUNNING ||
        WorkoutState.phase == WorkoutState.Phase.RECOVERY || WorkoutState.phase == WorkoutState.Phase.SENDING

    // ---------- bloqueio de toque durante o treino ----------
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (running()) {
            if (SystemClock.uptimeMillis() < touchFallbackUntil || holdAnim != null) return super.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) lockToastUntil = SystemClock.uptimeMillis() + 2500
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (running()) return true   // ignora o bezel giratório durante o treino
        return super.dispatchGenericMotionEvent(ev)
    }

    // ---------- botão Voltar ----------
    // No Galaxy Watch, "segurar Voltar" é reservado ao sistema (abre a Carteira/Samsung Pay) e não chega ao app.
    // Por isso: um toque no Voltar abre, por 10 s, o painel de encerramento; nele, segurar o botão vermelho 5 s encerra.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && inWorkout()) {
            if (running() && event.action == KeyEvent.ACTION_UP) openEndPanel()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openEndPanel() {
        touchFallbackUntil = SystemClock.uptimeMillis() + 10000
        vibrate(60)
        render()
    }

    private fun startHold() {
        holdAnim?.cancel()
        vibrate(25)
        holdAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5000
            interpolator = LinearInterpolator()
            addUpdateListener { ring.holdProgress = it.animatedValue as Float; ring.invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    ring.holdProgress = 0f; ring.invalidate()
                    if (!cancelled) { vibrate(400); touchFallbackUntil = 0; service(WorkoutService.ACTION_STOP) }
                    else touchFallbackUntil = maxOf(touchFallbackUntil, SystemClock.uptimeMillis() + 3000)
                }
            })
            start()
        }
    }

    private fun cancelHold() {
        holdAnim?.cancel(); holdAnim = null
        ring.holdProgress = 0f; ring.invalidate()
    }

    // ---------- conexão / serviço ----------
    private fun hasBle() = Build.VERSION.SDK_INT < 31 ||
        (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)

    private fun requestPermsAndConnect() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) { perms += Manifest.permission.BLUETOOTH_SCAN; perms += Manifest.permission.BLUETOOTH_CONNECT }
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) service(WorkoutService.ACTION_CONNECT) else permLauncher.launch(missing.toTypedArray())
    }

    private fun service(action: String) {
        try {
            ContextCompat.startForegroundService(this, Intent(this, WorkoutService::class.java).setAction(action))
        } catch (e: Exception) {
            WorkoutState.message = "Erro: ${e.message}"
        }
    }

    private fun loadPhoneConfig() {
        lifecycleScope.launch {
            try {
                val buf = Wearable.getDataClient(this@WearActivity).dataItems.await()
                try {
                    for (item in buf) {
                        if (item.uri.path == "/config") {
                            val json = DataMapItem.fromDataItem(item).dataMap.getString("json") ?: continue
                            getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("json", json).apply()
                            WorkoutState.applyConfig(json)
                        }
                    }
                } finally { buf.release() }
            } catch (_: Exception) { }
        }
    }

    private fun startWorkout() {
        if (!hasBle()) { requestPermsAndConnect(); return }
        vibrate(80)
        service(WorkoutService.ACTION_START)
    }

    private fun vibrate(ms: Long) {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }

    private fun fmt(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private var shownTestIdx = -1
    private var alertedIdx = -1

    private fun render() {
        val st = WorkoutState
        val now = System.currentTimeMillis()
        val hr = st.displayHr(now)
        val workout = inWorkout()

        idleView.visibility = if (workout) View.GONE else View.VISIBLE
        runView.visibility = if (workout) View.VISIBLE else View.GONE
        // tela sempre ligada enquanto o app estiver aberto
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!workout) {
            root.setBackgroundColor(Color.BLACK)
            val bat = if (st.battery >= 0) " · bat. ${st.battery}%" else ""
            tvStatus.text = "H10 ${st.connState}$bat"
            tvIdleHr.text = if (hr > 0) "$hr bpm" else "--"
            val cardio = st.type == "cardio"
            (btnCardio.background as GradientDrawable).setColor(if (cardio) 0xFF58A6FF.toInt() else 0xFF21262D.toInt())
            btnCardio.setTextColor(if (cardio) Color.BLACK else Color.WHITE)
            (btnForca.background as GradientDrawable).setColor(if (!cardio) 0xFF58A6FF.toInt() else 0xFF21262D.toInt())
            btnForca.setTextColor(if (!cardio) Color.BLACK else Color.WHITE)
            btnGps.text = if (st.useGps) "GPS ligado" else "GPS desligado"
            btnStart.alpha = if (st.connState == "conectado") 1f else 0.6f
            tvMsg.text = st.message
            return
        }

        val z = if (hr > 0) st.zoneOf(hr) else -1
        // no modo econômico (ambient) o fundo fica preto e a zona aparece na cor do texto
        val bg = if (ambient) Color.BLACK else if (z >= 0) st.zoneColors[z] else 0xFF161B22.toInt()
        root.setBackgroundColor(bg)
        ring.alpha = if (ambient) 0.45f else 1f
        val fg = if (ambient) (if (z >= 0) st.zoneColors[z] else Color.WHITE)
                 else if (ColorUtils.calculateLuminance(bg) > 0.35) Color.BLACK else Color.WHITE
        val fgSoft = ColorUtils.setAlphaComponent(fg, 200)
        listOf(tvTop, tvBottom, tvHint).forEach { it.setTextColor(fgSoft) }
        tvZone.setTextColor(fg); tvHr.setTextColor(fg)

        ring.hr = hr; ring.invalidate()
        tvHr.text = if (hr > 0) hr.toString() else "--"
        tvZone.text = when {
            hr <= 0 -> if (st.contact == false) "SEM CONTATO" else "SEM SINAL"
            z < 0 -> "ABAIXO DE Z1"
            else -> "ZONA ${z + 1} · ${st.zoneNames[z].substringAfter(' ').uppercase()}"
        }

        val bat = if (st.battery in 0..20) " · bat. cinta ${st.battery}%" else ""
        when (st.phase) {
            WorkoutState.Phase.RUNNING -> {
                tvTop.text = fmt((now - st.startMs) / 1000) + bat
                tvBottom.text = if (st.useGps) {
                    val km = "%.2f km".format(st.distanceM / 1000).replace('.', ',')
                    val spd = if (st.paceSecKm > 0 && st.paceSecKm < 1800) "  " + "%.1f km/h".format(3600.0 / st.paceSecKm).replace('.', ',') else ""
                    if (st.gpsFix || st.distanceM > 0) km + spd else "Aguardando GPS…"
                } else "Musculação"
                tvBottom.background = null
                // teste guiado: etapa, carga atual e aviso 5 s antes da troca
                if (st.testActive(now)) {
                    if (st.testIdx != shownTestIdx) {
                        if (shownTestIdx >= 0) vibrate(450)
                        shownTestIdx = st.testIdx
                    }
                    val left = kotlin.math.max(0L, st.testEndMs - now)
                    val leftS = ((left + 999) / 1000).toInt()
                    tvTop.text = "${st.testPhase} · ${fmt(leftS.toLong())}"
                    if (leftS in 1..5 && !st.testHold) {
                        if (alertedIdx != st.testIdx) { alertedIdx = st.testIdx; vibrate(150) }
                        tvBottom.text = "→ ${st.testNext}  $leftS"
                        tvBottom.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xF0000000.toInt()) }
                        tvBottom.setTextColor(0xFFF2C230.toInt())
                    } else {
                        tvBottom.text = st.testLoad
                    }
                } else { shownTestIdx = -1; alertedIdx = -1 }
                val panel = SystemClock.uptimeMillis() < touchFallbackUntil || ring.holdProgress > 0f
                tvHint.visibility = View.GONE
                if (SystemClock.uptimeMillis() < lockToastUntil && !panel) tvBottom.text = "🔒 Voltar para encerrar"
                if (ring.holdProgress > 0f) btnHoldTouch.text = kotlin.math.ceil((1 - ring.holdProgress) * 5).toInt().toString()
                else btnHoldTouch.text = "SEGURE\npara encerrar"
                btnHoldTouch.visibility = if (panel) View.VISIBLE else View.GONE
                btnResume.visibility = if (panel && ring.holdProgress == 0f) View.VISIBLE else View.GONE
                tvHr.visibility = if (panel) View.GONE else View.VISIBLE
                tvBottom.visibility = if (panel) View.GONE else View.VISIBLE
                btnSkip.visibility = View.GONE
            }
            WorkoutState.Phase.RECOVERY -> {
                tvTop.text = "Recuperação ${st.recoveryLeft} s"
                tvBottom.text = "parado ou caminhando"
                tvHint.visibility = View.GONE; tvHr.visibility = View.VISIBLE
                btnSkip.visibility = View.VISIBLE
                btnHoldTouch.visibility = View.GONE; btnResume.visibility = View.GONE; tvBottom.visibility = View.VISIBLE
            }
            else -> {
                tvTop.text = "Salvando…"; tvBottom.text = ""; tvHint.visibility = View.GONE; tvHr.visibility = View.VISIBLE
                btnSkip.visibility = View.GONE; btnHoldTouch.visibility = View.GONE; btnResume.visibility = View.GONE
            }
        }
    }
}
