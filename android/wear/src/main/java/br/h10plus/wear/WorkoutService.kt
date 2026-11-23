package br.h10plus.wear

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.MessageClient
import androidx.wear.ongoing.Status
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Serviço em primeiro plano: mantém a conexão com o H10 e o GPS mesmo com a tela apagada,
 * grava FC, RR e trajeto, mede a recuperação de 60 s e envia a sessão ao celular.
 */
class WorkoutService : Service() {

    companion object {
        const val ACTION_CONNECT = "connect"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_SKIP_RECOVERY = "skip"
        const val ACTION_QUIT = "quit"
        const val EXTRA_SID = "sid"
        const val EXTRA_T = "t"
        const val EXTRA_REMOTE = "remote"
        private const val CHANNEL = "treino"
        private const val NOTIF_ID = 10
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var api: PolarBleApi? = null
    private var deviceId: String? = null
    private var hrJob: Job? = null
    private var scanJob: Job? = null
    private var recJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val hrSamples = ArrayList<LongArray>()     // [ms desde o início, bpm]
    private val rrSamples = ArrayList<LongArray>()     // [ms desde o início, rr ms]
    private val gps = ArrayList<DoubleArray>()         // [s, lat, lon, alt, precisão, velocidade]
    private var lastGood: Location? = null
    private val recent = ArrayDeque<Pair<Long, Double>>() // (ms, distância acumulada) para o ritmo
    private var peak = 0
    private var hrrHr60 = 0

    private val locationListener = LocationListener { onLocation(it) }
    private var liveJob: Job? = null

    // FC enviada pelo app do celular
    private val msgListener = MessageClient.OnMessageReceivedListener { ev ->
        if (ev.path == "/live") try {
            val o = JSONObject(String(ev.data))
            val now = System.currentTimeMillis()
            val h = o.optInt("hr", 0)
            if (h > 0) { WorkoutState.remoteHr = h; WorkoutState.remoteHrMs = now }
            o.optJSONObject("test")?.let { t ->
                WorkoutState.testPhase = t.optString("ph")
                WorkoutState.testLoad = t.optString("load")
                WorkoutState.testNext = t.optString("next")
                WorkoutState.testIdx = t.optInt("idx", 0)
                WorkoutState.testEndMs = now + (t.optDouble("left", 0.0) * 1000).toLong()
                WorkoutState.testHold = t.optBoolean("vt2") && !t.optBoolean("max")
                WorkoutState.testAt = now
            }
        } catch (_: Exception) { }
    }

    /** Abre o app no celular com o comando (h10plus://treino?acao=...). */
    private fun sendCmdToPhone(acao: String, params: Map<String, String>) {
        scope.launch {
            try {
                val b = Uri.Builder().scheme("h10plus").authority("treino").appendQueryParameter("acao", acao)
                params.forEach { (k, v) -> b.appendQueryParameter(k, v) }
                val intent = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(b.build())
                val helper = RemoteActivityHelper(this@WorkoutService)
                for (node in Wearable.getNodeClient(this@WorkoutService).connectedNodes.await()) helper.startRemoteActivity(intent, node.id)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    /** A cada 2 s envia FC, distância e ritmo ao celular. */
    private fun startLive() {
        liveJob?.cancel()
        liveJob = scope.launch {
            val client = Wearable.getMessageClient(this@WorkoutService)
            while (WorkoutState.phase == WorkoutState.Phase.RUNNING || WorkoutState.phase == WorkoutState.Phase.RECOVERY) {
                try {
                    val now = System.currentTimeMillis()
                    val o = JSONObject().put("sid", WorkoutState.sid)
                        .put("hr", if (now - WorkoutState.lastHrMs < 5000) WorkoutState.hr else 0)
                        .put("dist", WorkoutState.distanceM).put("pace", WorkoutState.paceSecKm)
                        .put("gps", WorkoutState.useGps).put("fix", WorkoutState.gpsFix)
                    val nodes = Wearable.getNodeClient(this@WorkoutService).connectedNodes.await()
                    for (n in nodes) client.sendMessage(n.id, "/live", o.toString().toByteArray()).await()
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                delay(2000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Treino", NotificationManager.IMPORTANCE_LOW))
        WorkoutState.loadConfig(this)
        try { Wearable.getMessageClient(this).addListener(msgListener) } catch (_: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(withLocation = WorkoutState.phase == WorkoutState.Phase.RUNNING && WorkoutState.useGps)
        when (intent?.action) {
            ACTION_CONNECT -> ensureConnected()
            ACTION_START -> startWorkout(intent)
            ACTION_STOP -> stopWorkout(intent.getBooleanExtra(EXTRA_REMOTE, false))
            ACTION_SKIP_RECOVERY -> { recJob?.cancel(); finishAndSend() }
            ACTION_QUIT -> quit()
        }
        return START_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, WearActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val txt = when (WorkoutState.phase) {
            WorkoutState.Phase.RUNNING -> "Treino em andamento · ${WorkoutState.hr} bpm"
            WorkoutState.Phase.RECOVERY -> "Medindo recuperação…"
            else -> "H10 ${WorkoutState.connState}"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Monitor H10")
            .setContentText(txt)
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        // "atividade em andamento": ícone no mostrador que leva de volta ao treino
        if (WorkoutState.phase == WorkoutState.Phase.RUNNING || WorkoutState.phase == WorkoutState.Phase.RECOVERY) {
            try {
                val zero = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - WorkoutState.startMs)
                val status = Status.Builder()
                    .addTemplate("#tipo# · #tempo#")
                    .addPart("tipo", Status.TextPart(if (WorkoutState.type == "forca") "Musculação" else if (WorkoutState.useGps) "Cardio outdoor" else "Cardio indoor"))
                    .addPart("tempo", Status.StopwatchPart(zero))
                    .build()
                OngoingActivity.Builder(applicationContext, NOTIF_ID, builder)
                    .setStaticIcon(R.mipmap.ic_launcher)
                    .setTouchIntent(open)
                    .setStatus(status)
                    .build()
                    .apply(applicationContext)
            } catch (_: Exception) { }
        }
        return builder.build()
    }

    private fun goForeground(withLocation: Boolean) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (withLocation && hasPerm(android.Manifest.permission.ACCESS_FINE_LOCATION)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
        } catch (e: Exception) {
            WorkoutState.message = "Falha ao iniciar serviço: ${e.message}"
        }
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---------------- H10 ----------------
    private fun polar(): PolarBleApi {
        api?.let { return it }
        val a = PolarBleApiDefaultImpl.defaultImplementation(
            applicationContext,
            setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_HR, PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO)
        )
        a.setAutomaticReconnection(true)
        a.setApiCallback(object : PolarBleApiCallback() {
            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) { WorkoutState.connState = "conectando" }
            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                deviceId = polarDeviceInfo.deviceId
                getSharedPreferences("h10", Context.MODE_PRIVATE).edit().putString("dev", polarDeviceInfo.deviceId).apply()
                WorkoutState.connState = "conectado"
                WorkoutState.message = ""
            }
            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                WorkoutState.connState = if (WorkoutState.phase == WorkoutState.Phase.RUNNING) "reconectando" else "desconectado"
                hrJob?.cancel()
            }
            override fun bleSdkFeatureReady(identifier: String, feature: PolarBleApi.PolarBleSdkFeature) {
                if (feature == PolarBleApi.PolarBleSdkFeature.FEATURE_HR) scope.launch { startHr(identifier) }
            }
            override fun batteryLevelReceived(identifier: String, level: Int) { WorkoutState.battery = level }
            override fun disInformationReceived(identifier: String, disInfo: DisInfo) {}
            override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) {}
        })
        api = a
        return a
    }

    private fun ensureConnected() {
        if (WorkoutState.connState == "conectado" || WorkoutState.connState == "conectando" || WorkoutState.connState == "procurando") return
        val a = polar()
        val saved = getSharedPreferences("h10", Context.MODE_PRIVATE).getString("dev", null)
        scanJob?.cancel()
        scanJob = scope.launch {
            try {
                if (saved != null) {
                    WorkoutState.connState = "conectando"
                    a.connectToDevice(saved)
                    delay(12000)
                    if (WorkoutState.connState == "conectado") return@launch
                    try { a.disconnectFromDevice(saved) } catch (_: Exception) { }
                }
                WorkoutState.connState = "procurando"
                val info = withTimeoutOrNull(25000) { a.searchForDevice().first { it.name.contains("H10", ignoreCase = true) } }
                if (info == null) {
                    WorkoutState.connState = "desconectado"
                    WorkoutState.message = "H10 não encontrado"
                } else a.connectToDevice(info.deviceId)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                WorkoutState.connState = "desconectado"
                WorkoutState.message = "Erro: ${e.message}"
            }
        }
    }

    private fun startHr(id: String) {
        hrJob?.cancel()
        hrJob = scope.launch {
            try {
                polar().startHrStreaming(id).collect { d ->
                    for (s in d.samples) {
                        val now = System.currentTimeMillis()
                        WorkoutState.hr = s.hr
                        WorkoutState.lastHrMs = now
                        WorkoutState.contact = if (s.contactStatusSupported) s.contactStatus else null
                        val ph = WorkoutState.phase
                        if (ph == WorkoutState.Phase.RUNNING || ph == WorkoutState.Phase.RECOVERY) {
                            val t = now - WorkoutState.startMs
                            if (s.hr > 0) hrSamples.add(longArrayOf(t, s.hr.toLong()))
                            for (r in s.rrsMs) rrSamples.add(longArrayOf(t, r.toLong()))
                        }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                WorkoutState.message = "FC interrompida: ${e.message}"
            }
        }
    }

    // ---------------- GPS ----------------
    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (!WorkoutState.useGps || !hasPerm(android.Manifest.permission.ACCESS_FINE_LOCATION)) return
        val lm = getSystemService(LocationManager::class.java)
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        } catch (e: Exception) {
            WorkoutState.message = "GPS indisponível: ${e.message}"
        }
    }

    private fun stopGps() {
        try { getSystemService(LocationManager::class.java).removeUpdates(locationListener) } catch (_: Exception) { }
    }

    private fun onLocation(loc: Location) {
        if (WorkoutState.phase != WorkoutState.Phase.RUNNING) return
        val t = (System.currentTimeMillis() - WorkoutState.startMs) / 1000.0
        gps.add(doubleArrayOf(t, loc.latitude, loc.longitude, loc.altitude, loc.accuracy.toDouble(), if (loc.hasSpeed()) loc.speed.toDouble() else -1.0))
        WorkoutState.gpsFix = loc.accuracy <= 25f
        if (loc.accuracy > 25f) return
        val prev = lastGood
        if (prev != null) {
            val d = prev.distanceTo(loc).toDouble()
            val dt = (loc.time - prev.time) / 1000.0
            // descarta saltos impossíveis (> 12 m/s, acima do ritmo de corrida de elite)
            if (dt > 0 && d / dt < 12.0) WorkoutState.distanceM += d
        }
        lastGood = loc
        val now = System.currentTimeMillis()
        recent.addLast(now to WorkoutState.distanceM)
        while (recent.size > 2 && now - recent.first().first > 30000) recent.removeFirst()
        val first = recent.first()
        val dd = WorkoutState.distanceM - first.second
        val ds = (now - first.first) / 1000.0
        WorkoutState.paceSecKm = if (dd > 5 && ds > 5) ds / dd * 1000.0 else 0.0
    }

    // ---------------- treino ----------------
    private fun startWorkout(intent: Intent) {
        if (WorkoutState.phase == WorkoutState.Phase.RUNNING) return
        val remote = intent.getBooleanExtra(EXTRA_REMOTE, false)
        hrSamples.clear(); rrSamples.clear(); gps.clear(); recent.clear(); lastGood = null
        val now = System.currentTimeMillis()
        val t = intent.getLongExtra(EXTRA_T, 0L)
        // iniciado pelo celular: usa o mesmo instante de início (se for recente) para os dois treinos baterem
        WorkoutState.startMs = if (remote && t > 0 && now - t in 0..120000) t else now
        WorkoutState.sid = intent.getStringExtra(EXTRA_SID) ?: "w${WorkoutState.startMs}"
        if (!remote) sendCmdToPhone("start", mapOf("sid" to WorkoutState.sid, "t" to WorkoutState.startMs.toString(), "tipo" to WorkoutState.type, "amb" to if (WorkoutState.useGps) "out" else "in"))
        WorkoutState.distanceM = 0.0; WorkoutState.paceSecKm = 0.0; WorkoutState.gpsFix = false
        WorkoutState.phase = WorkoutState.Phase.RUNNING
        WorkoutState.message = ""
        goForeground(withLocation = WorkoutState.useGps)
        @Suppress("DEPRECATION")
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "h10plus:treino").apply { acquire(6 * 3600 * 1000L) }
        ensureConnected()
        startGps()
        startLive()
    }

    private fun stopWorkout(remote: Boolean) {
        if (WorkoutState.phase != WorkoutState.Phase.RUNNING) return
        if (!remote) sendCmdToPhone("stop", mapOf("sid" to WorkoutState.sid))
        val end = System.currentTimeMillis()
        WorkoutState.endMs = end
        stopGps()
        val tEnd = end - WorkoutState.startMs
        peak = hrSamples.filter { it[0] >= tEnd - 30000 }.maxOfOrNull { it[1].toInt() } ?: 0
        WorkoutState.phase = WorkoutState.Phase.RECOVERY
        recJob?.cancel()
        recJob = scope.launch {
            for (left in 60 downTo 1) { WorkoutState.recoveryLeft = left; delay(1000) }
            WorkoutState.recoveryLeft = 0
            val target = tEnd + 60000
            hrrHr60 = hrSamples.filter { kotlin.math.abs(it[0] - target) <= 3000 }
                .minByOrNull { kotlin.math.abs(it[0] - target) }?.get(1)?.toInt() ?: 0
            finishAndSend()
        }
    }

    private fun finishAndSend() {
        if (WorkoutState.phase != WorkoutState.Phase.RECOVERY) return
        WorkoutState.phase = WorkoutState.Phase.SENDING
        val start = WorkoutState.startMs
        val end = WorkoutState.endMs
        val tEnd = end - start
        val o = JSONObject()
            .put("v", 1)
            .put("id", "ww$start")
            .put("sid", WorkoutState.sid)
            .put("type", WorkoutState.type)
            .put("amb", if (WorkoutState.type == "cardio" && WorkoutState.useGps) "out" else "in")
            .put("start", start).put("end", end)
            .put("dist", WorkoutState.distanceM)
            .put("hr", JSONArray().apply { hrSamples.filter { it[0] <= tEnd }.forEach { put(JSONArray().put(it[0]).put(it[1])) } })
            .put("rr", JSONArray().apply { rrSamples.filter { it[0] <= tEnd }.forEach { put(JSONArray().put(it[0]).put(it[1])) } })
            .put("gps", JSONArray().apply { gps.forEach { g -> put(JSONArray().apply { g.forEach { put(it) } }) } })
            .put("hrr", if (peak > 0 && hrrHr60 > 0) JSONObject().put("peak", peak).put("hr60", hrrHr60) else JSONObject.NULL)
        val json = o.toString()
        // cópia local de segurança
        try {
            val dir = File(filesDir, "sessions").apply { mkdirs() }
            File(dir, "ww$start.json").writeText(json)
            dir.listFiles()?.sortedBy { it.name }?.dropLast(20)?.forEach { it.delete() }
        } catch (_: Exception) { }
        scope.launch {
            try {
                val bos = ByteArrayOutputStream()
                GZIPOutputStream(bos).use { it.write(json.toByteArray()) }
                val req = PutDataMapRequest.create("/session/ww$start").apply {
                    dataMap.putAsset("data", Asset.createFromBytes(bos.toByteArray()))
                    dataMap.putLong("t", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(this@WorkoutService).putDataItem(req).await()
                WorkoutState.message = "Treino salvo. Abra o app no celular para importar."
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                WorkoutState.message = "Salvo no relógio; envio pendente (${e.message})."
            }
            WorkoutState.phase = WorkoutState.Phase.DONE
            releaseWake()
            hrJob?.cancel()
            deviceId?.let { try { api?.disconnectFromDevice(it) } catch (_: Exception) { } }
            WorkoutState.connState = "desconectado"
            ServiceCompat.stopForeground(this@WorkoutService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun releaseWake() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        wakeLock = null
    }

    private fun quit() {
        if (WorkoutState.phase == WorkoutState.Phase.RUNNING || WorkoutState.phase == WorkoutState.Phase.RECOVERY) return
        hrJob?.cancel(); scanJob?.cancel()
        deviceId?.let { try { api?.disconnectFromDevice(it) } catch (_: Exception) { } }
        WorkoutState.connState = "desconectado"
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { Wearable.getMessageClient(this).removeListener(msgListener) } catch (_: Exception) { }
        liveJob?.cancel()
        stopGps()
        releaseWake()
        scope.cancel()
        try { api?.shutDown() } catch (_: Exception) { }
        api = null
        super.onDestroy()
    }
}
