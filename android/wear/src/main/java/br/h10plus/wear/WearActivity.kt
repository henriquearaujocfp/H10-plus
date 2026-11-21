package br.h10plus.wear

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Tela do relógio: escolha do tipo, início, FC grande com zona e encerramento segurando 5 s. */
class WearActivity : ComponentActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvHr: TextView
    private lateinit var tvZone: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvDist: TextView
    private lateinit var tvMsg: TextView
    private lateinit var idleBox: LinearLayout
    private lateinit var btnCardio: TextView
    private lateinit var btnForca: TextView
    private lateinit var btnGps: TextView
    private lateinit var btnStart: TextView
    private lateinit var holdBox: FrameLayout
    private lateinit var holdFill: View
    private lateinit var holdLbl: TextView
    private lateinit var btnSkip: TextView
    private var holdAnim: ValueAnimator? = null

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

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkoutState.loadConfig(this)
        val inset = (resources.displayMetrics.widthPixels * 0.12).toInt()

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(inset, dp(18), inset, dp(28))
        }
        tvStatus = TextView(this).apply { textSize = 11f; setTextColor(0xFFC9D1D9.toInt()); gravity = Gravity.CENTER }
        tvHr = TextView(this).apply { textSize = 58f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(Typeface.DEFAULT, Typeface.BOLD); text = "--" }
        tvZone = pill("--", 0xFF21262D.toInt(), size = 13f)
        tvClock = TextView(this).apply { textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        tvDist = TextView(this).apply { textSize = 13f; setTextColor(0xFFC9D1D9.toInt()); gravity = Gravity.CENTER }
        tvMsg = TextView(this).apply { textSize = 11f; setTextColor(0xFFFFD28A.toInt()); gravity = Gravity.CENTER }

        // controles de início
        idleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
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
        idleBox.addView(typeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        idleBox.addView(btnGps, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        idleBox.addView(btnStart, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // botão de encerrar (segurar 5 s)
        holdBox = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xFF3A1D20.toInt()) }
            clipToOutline = true
        }
        holdFill = View(this).apply { setBackgroundColor(0xFFE5484D.toInt()) }
        holdLbl = TextView(this).apply {
            text = "■ Segure 5 s"; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            setTypeface(Typeface.DEFAULT, Typeface.BOLD); setPadding(0, dp(12), 0, dp(12))
        }
        holdBox.addView(holdFill, FrameLayout.LayoutParams(0, -1))
        holdBox.addView(holdLbl, FrameLayout.LayoutParams(-1, -2))
        holdBox.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startHold(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { cancelHold(); true }
                else -> true
            }
        }
        btnSkip = pill("Pular recuperação", 0xFF21262D.toInt(), size = 12f)
        btnSkip.setOnClickListener { service(WorkoutService.ACTION_SKIP_RECOVERY) }

        col.addView(tvStatus)
        col.addView(tvHr)
        col.addView(tvZone, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(tvClock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        col.addView(tvDist)
        col.addView(idleBox, LinearLayout.LayoutParams(-1, -2))
        col.addView(holdBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(btnSkip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        col.addView(tvMsg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        scroll.addView(col)
        root.addView(scroll)
        setContentView(root)

        loadPhoneConfig()
        requestPermsAndConnect()
    }

    override fun onResume() { super.onResume(); ui.post(tick) }

    override fun onDestroy() {
        val ph = WorkoutState.phase
        if (ph == WorkoutState.Phase.IDLE || ph == WorkoutState.Phase.DONE) {
            try { stopService(Intent(this, WorkoutService::class.java)) } catch (_: Exception) { }
            WorkoutState.connState = "desconectado"
        }
        super.onDestroy()
    }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

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

    /** Zonas e FCmáx enviadas pelo app do celular. */
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

    private fun startHold() {
        holdAnim?.cancel()
        vibrate(25)
        holdAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5000
            interpolator = LinearInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                holdFill.layoutParams = holdFill.layoutParams.apply { width = (holdBox.width * p).toInt() }
                holdFill.requestLayout()
                holdLbl.text = if (p < 1f) "Segurando… ${kotlin.math.ceil((1 - p) * 5).toInt()} s" else "Encerrando"
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) { vibrate(300); service(WorkoutService.ACTION_STOP) }
                    resetHold()
                }
            })
            start()
        }
    }

    private fun cancelHold() { holdAnim?.cancel(); holdAnim = null; resetHold() }

    private fun resetHold() {
        holdFill.layoutParams = holdFill.layoutParams.apply { width = 0 }
        holdFill.requestLayout()
        holdLbl.text = "■ Segure 5 s"
    }

    private fun vibrate(ms: Long) {
        try {
            val v = getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }

    private fun fmt(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun render() {
        val st = WorkoutState
        val now = System.currentTimeMillis()
        val fresh = now - st.lastHrMs < 5000
        val hr = if (fresh) st.hr else 0
        tvHr.text = if (hr > 0) hr.toString() else "--"
        val z = if (hr > 0) st.zoneOf(hr) else -1
        tvZone.text = if (z >= 0) st.zoneNames[z] else if (hr > 0) "Abaixo de Z1" else "Sem sinal"
        (tvZone.background as GradientDrawable).setColor(if (z >= 0) st.zoneColors[z] else 0xFF21262D.toInt())
        tvZone.setTextColor(if (z >= 0) Color.BLACK else Color.WHITE)
        root.setBackgroundColor(if (z >= 0) (st.zoneColors[z] and 0x00FFFFFF) or 0x33000000 else Color.BLACK)

        val bat = if (st.battery >= 0) " · bat. ${st.battery}%" else ""
        val contact = if (st.contact == false) " · sem contato" else ""
        tvStatus.text = "H10 ${st.connState}$bat$contact"

        val running = st.phase == WorkoutState.Phase.RUNNING
        val recovery = st.phase == WorkoutState.Phase.RECOVERY
        idleBox.visibility = if (st.phase == WorkoutState.Phase.IDLE || st.phase == WorkoutState.Phase.DONE) View.VISIBLE else View.GONE
        holdBox.visibility = if (running) View.VISIBLE else View.GONE
        btnSkip.visibility = if (recovery) View.VISIBLE else View.GONE

        when (st.phase) {
            WorkoutState.Phase.RUNNING -> {
                tvClock.text = "${if (st.type == "forca") "Musculação" else "Cardio"} · ${fmt((now - st.startMs) / 1000)}"
                tvDist.text = if (st.useGps) {
                    val km = "%.2f km".format(st.distanceM / 1000)
                    val pace = if (st.paceSecKm > 0 && st.paceSecKm < 1800) " · %d:%02d/km".format((st.paceSecKm / 60).toInt(), (st.paceSecKm % 60).toInt()) else ""
                    if (st.gpsFix || st.distanceM > 0) km + pace else "Aguardando GPS…"
                } else ""
            }
            WorkoutState.Phase.RECOVERY -> { tvClock.text = "Recuperação: ${st.recoveryLeft} s"; tvDist.text = "Fique parado ou caminhe devagar" }
            WorkoutState.Phase.SENDING -> { tvClock.text = "Salvando…"; tvDist.text = "" }
            else -> { tvClock.text = ""; tvDist.text = "" }
        }
        window.let { w ->
            if (running || recovery) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val cardio = st.type == "cardio"
        (btnCardio.background as GradientDrawable).setColor(if (cardio) 0xFF58A6FF.toInt() else 0xFF21262D.toInt())
        btnCardio.setTextColor(if (cardio) Color.BLACK else Color.WHITE)
        (btnForca.background as GradientDrawable).setColor(if (!cardio) 0xFF58A6FF.toInt() else 0xFF21262D.toInt())
        btnForca.setTextColor(if (!cardio) Color.BLACK else Color.WHITE)
        btnGps.text = if (st.useGps) "GPS ligado" else "GPS desligado"
        btnStart.alpha = if (st.connState == "conectado") 1f else 0.6f
        tvMsg.text = st.message
    }
}
