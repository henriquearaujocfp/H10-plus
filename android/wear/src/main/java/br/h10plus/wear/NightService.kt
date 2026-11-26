package br.h10plus.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/** Modo noite: grava IBI, FC e temperatura da pele e envia ao celular ao terminar. */
class NightService : Service(), NightSensors.Sink {
    companion object {
        const val ACTION_START = "night.start"
        const val ACTION_STOP = "night.stop"
        const val MAX_MS = 12L * 3600 * 1000
        @Volatile var running = false
        @Volatile var startMs = 0L
        @Volatile var ibiCount = 0
        @Volatile var lastHr = 0
        @Volatile var lastMsg = ""
    }

    private val sensors = NightSensorsProvider.create()
    private val ibi = StringBuilder()     // "t,ibi,status;"
    private val hr = StringBuilder()
    private val temp = StringBuilder()
    private var lastHrT = 0L
    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val autoStop = Runnable { stopAndSend() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAndSend(); return START_NOT_STICKY }
            else -> if (!running) begin()
        }
        return START_STICKY
    }

    private fun begin() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("noite") == null)
            nm.createNotificationChannel(NotificationChannel("noite", "Modo noite", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, WearActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "noite").setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Modo noite").setContentText("Gravando FC, intervalos e temperatura")
            .setOngoing(true).setContentIntent(open).build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
        try { ServiceCompat.startForeground(this, 77, n, type) }
        catch (e: Exception) { lastMsg = "Não foi possível iniciar: ${e.message}"; stopSelf(); return }
        running = true; startMs = System.currentTimeMillis(); ibiCount = 0; lastMsg = ""
        getSharedPreferences("noite", Context.MODE_PRIVATE).edit().putLong("start", startMs).apply()
        if (!sensors.available) { lastMsg = "SDK da Samsung ausente neste build."; }
        sensors.start(this, this)
        ui.postDelayed(autoStop, MAX_MS)
    }

    @Synchronized override fun ibi(t: Long, ibiMs: Int, status: Int) { ibi.append(t).append(',').append(ibiMs).append(',').append(status).append(';'); ibiCount++ }
    @Synchronized override fun hr(t: Long, bpm: Int) {
        lastHr = bpm
        if (t - lastHrT >= 5000) { hr.append(t).append(',').append(bpm).append(';'); lastHrT = t }
    }
    @Synchronized override fun temp(t: Long, skin: Float, ambient: Float) { temp.append(t).append(',').append(skin).append(',').append(ambient).append(';') }
    override fun error(msg: String) { lastMsg = msg }

    private fun parse(sb: StringBuilder): JSONArray {
        val arr = JSONArray()
        for (row in sb.split(';')) if (row.isNotEmpty()) {
            val a = JSONArray(); for (v in row.split(',')) a.put(v.toDoubleOrNull() ?: 0.0); arr.put(a)
        }
        return arr
    }

    @Synchronized private fun stopAndSend() {
        if (!running) { stopSelf(); return }
        running = false
        ui.removeCallbacks(autoStop)
        sensors.stop()
        val end = System.currentTimeMillis()
        val json = JSONObject().put("v", 1).put("id", "nw$startMs").put("kind", "night").put("start", startMs).put("end", end)
            .put("ibi", parse(ibi)).put("hr", parse(hr)).put("temp", parse(temp)).put("msg", lastMsg).toString()
        try {
            val dir = File(filesDir, "nights").apply { mkdirs() }
            File(dir, "nw$startMs.json").writeText(json)
            dir.listFiles()?.sortedBy { it.name }?.dropLast(10)?.forEach { it.delete() }
        } catch (_: Exception) { }
        val st = startMs
        scope.launch {
            try {
                val bos = ByteArrayOutputStream()
                GZIPOutputStream(bos).use { it.write(json.toByteArray()) }
                val req = PutDataMapRequest.create("/night/nw$st").apply {
                    dataMap.putAsset("data", Asset.createFromBytes(bos.toByteArray()))
                    dataMap.putLong("t", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(this@NightService).putDataItem(req).await()
                lastMsg = "Noite enviada ao celular."
            } catch (e: Exception) { lastMsg = "Noite salva no relógio; envio pendente (${e.message})." }
            ServiceCompat.stopForeground(this@NightService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() { if (running) stopAndSend(); super.onDestroy() }
}
