package br.h10plus.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Serviço em primeiro plano durante o treino: notificação fixa com FC/zona/tempo (ou a etapa do teste guiado)
 * e a bolha flutuante. Também mantém o app vivo em segundo plano enquanto o treino está ativo.
 */
class WorkoutFgService : Service() {

    companion object {
        const val CH = "treino_ativo"
        const val NID = 4210
        const val ACTION_TOGGLE_BUBBLE = "br.h10plus.app.TOGGLE_BUBBLE"
    }

    private val ui = Handler(Looper.getMainLooper())
    private var tick = 0
    private lateinit var bubble: BubbleOverlay
    private val loop = object : Runnable {
        override fun run() {
            if (!LiveHub.active) { stopSelf(); return }
            bubble.update()
            if (tick++ % 2 == 0) try { getSystemService(NotificationManager::class.java).notify(NID, build()) } catch (_: Exception) { }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LiveHub.serviceRunning = true
        bubble = BubbleOverlay(this)
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Treino ativo", NotificationManager.IMPORTANCE_LOW).apply {
                description = "FC e tempo do treino em andamento"; setShowBadge(false)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build()
        try {
            ServiceCompat.startForeground(this, NID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } catch (_: Exception) {
            try { ServiceCompat.startForeground(this, NID, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) }
            catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        }
        if (intent?.action == ACTION_TOGGLE_BUBBLE) {
            val on = !LiveHub.bubbleOn(this)
            LiveHub.setBubble(this, on)
            if (on) LiveHub.bubbleDismissed = false
            bubble.update()
            getSystemService(NotificationManager::class.java).notify(NID, build())
        }
        ui.removeCallbacks(loop); ui.post(loop)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ui.removeCallbacks(loop)
        bubble.remove()
        LiveHub.serviceRunning = false
        super.onDestroy()
    }

    private fun pi(code: Int, i: Intent) = PendingIntent.getActivity(this, code, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun build(): Notification {
        val s = LiveHub.s
        val now = System.currentTimeMillis()
        val hr = LiveHub.hr(now)
        val z = LiveHub.zoneOf(hr)
        val start = s.optLong("start", now)
        val chronoBase = SystemClock.elapsedRealtime() - (now - start)
        val type = s.optString("type", "Treino")
        val test = s.optJSONObject("test")
        val dist = s.optDouble("dist", 0.0); val spd = s.optDouble("spd", 0.0)
        val gpsTxt = if (dist > 0) "${LiveHub.num2(dist / 1000)} km" + (if (spd > 0) " · ${LiveHub.num1(spd)} km/h" else "") else ""

        fun fill(v: RemoteViews, big: Boolean) {
            v.setTextViewText(R.id.nHr, if (hr > 0) hr.toString() else "--")
            if (z >= 0) {
                v.setTextViewText(R.id.nZone, LiveHub.ZONE_NAMES[z])
                v.setInt(R.id.nZone, "setBackgroundColor", LiveHub.ZONE[z])
                v.setTextColor(R.id.nZone, LiveHub.ZONE_DARK[z])
            } else {
                v.setTextViewText(R.id.nZone, if (hr > 0) "abaixo de Z1" else "sem sinal")
                v.setInt(R.id.nZone, "setBackgroundColor", 0x00000000)
                v.setTextColor(R.id.nZone, 0xFF8A8F98.toInt())
            }
            v.setChronometer(R.id.nChrono, chronoBase, null, true)
            v.setTextViewText(R.id.nType, if (test != null) "Teste guiado" else type)
            if (test != null) {
                val left = test.optDouble("left", 0.0)
                v.setTextViewText(R.id.nRight, "${test.optString("ph")} · ${test.optString("load")}")
                if (big) {
                    v.setViewVisibility(R.id.nTest, View.VISIBLE)
                    v.setViewVisibility(R.id.nStats, View.GONE)
                    v.setViewVisibility(R.id.nBar, View.GONE)
                    v.setTextViewText(R.id.nTestLoad, test.optString("load"))
                    v.setTextViewText(R.id.nTestLeft, "faltam ${LiveHub.fmtClock(left.toLong())}")
                    v.setTextViewText(R.id.nTestNext, if (test.optBoolean("vt2") && !test.optBoolean("max")) "VT2 atingido: pode concluir" else "Próxima: ${test.optString("next")}")
                    val stage = if (test.optInt("idx") == 0) test.optDouble("warm", 300.0) else test.optDouble("stage", 180.0)
                    v.setProgressBar(R.id.nTestProg, 1000, ((1 - left / stage).coerceIn(0.0, 1.0) * 1000).toInt(), false)
                }
            } else {
                v.setTextViewText(R.id.nRight, gpsTxt)
                if (big) {
                    v.setViewVisibility(R.id.nTest, View.GONE)
                    v.setViewVisibility(R.id.nStats, View.VISIBLE)
                    v.setViewVisibility(R.id.nBar, View.VISIBLE)
                    val d = resources.displayMetrics.density
                    v.setImageViewBitmap(R.id.nBar, LiveHub.zoneBar((260 * d).toInt(), (6 * d).toInt(), 3 * d))
                    val avg = s.optDouble("avg", 0.0)
                    v.setTextViewText(R.id.nAvg, if (avg > 0) avg.toInt().toString() else "--")
                    v.setTextViewText(R.id.nMax, if (s.optInt("max") > 0) s.optInt("max").toString() else "--")
                    v.setTextViewText(R.id.nTrimp, s.optDouble("trimp", 0.0).toInt().toString())
                    v.setTextViewText(R.id.nDist, if (dist > 0) "${LiveHub.num2(dist / 1000)} km" else "--")
                    v.setTextViewText(R.id.nSpd, if (spd > 0) "${LiveHub.num1(spd)} km/h" else "--")
                }
            }
        }
        val small = RemoteViews(packageName, R.layout.notif_small).also { fill(it, false) }
        val big = RemoteViews(packageName, R.layout.notif_big).also { fill(it, true) }

        val open = pi(20, Intent(this, MainActivity::class.java))
        val focus = pi(21, Intent(Intent.ACTION_VIEW, Uri.parse("h10plus://treino?acao=foco"), this, MainActivity::class.java))
        val bubbleAction = if (Settings.canDrawOverlays(this))
            PendingIntent.getService(this, 22, Intent(this, WorkoutFgService::class.java).setAction(ACTION_TOGGLE_BUBBLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        else pi(23, Intent(Intent.ACTION_VIEW, Uri.parse("h10plus://treino?acao=bolha"), this, MainActivity::class.java))
        val bubbleLbl = if (Settings.canDrawOverlays(this) && LiveHub.bubbleOn(this)) "Bolha: desligar" else "Bolha: ligar"

        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(if (hr > 0) "$hr bpm" else "Treino ativo")
            .setContentText(if (z >= 0) LiveHub.ZONE_NAMES[z] else type)
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory("workout")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Abrir", open)
            .addAction(0, "Tela de foco", focus)
            .addAction(0, bubbleLbl, bubbleAction)
            .build()
    }
}
