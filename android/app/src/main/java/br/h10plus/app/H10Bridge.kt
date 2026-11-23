package br.h10plus.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.content.Intent
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.PolarH10OfflineExerciseApi
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarExerciseEntry
import com.polar.sdk.api.model.PolarHealthThermometerData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/**
 * Ponte entre o site (JavaScript) e o SDK da Polar.
 * JS -> nativo: métodos @JavascriptInterface (objeto window.H10Native).
 * Nativo -> JS: window.__h10n(tipo, dados).
 */
class H10Bridge(private val act: MainActivity, private val web: WebView) {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = act.getSharedPreferences("h10", Context.MODE_PRIVATE)
    val health = HealthBridge(act) { t, o -> emit(t, o) }

    private val api: PolarBleApi = PolarBleApiDefaultImpl.defaultImplementation(
        act.applicationContext,
        setOf(
            PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
            PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
            PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
            PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_H10_EXERCISE_RECORDING
        )
    )

    @Volatile private var deviceId: String? = null
    @Volatile private var connected = false
    private var pendingConnect = false
    private var hrJob: Job? = null
    private var ecgJob: Job? = null
    private var scanJob: Job? = null
    private var entries: List<PolarExerciseEntry> = emptyList()

    init {
        api.setAutomaticReconnection(true)
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                emit("ble", JSONObject().put("on", powered))
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                emit("status", JSONObject().put("state", "connecting").put("id", polarDeviceInfo.deviceId))
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                connected = true
                deviceId = polarDeviceInfo.deviceId
                prefs.edit().putString("dev", polarDeviceInfo.deviceId).apply()
                emit("status", JSONObject().put("state", "connected").put("id", polarDeviceInfo.deviceId).put("name", polarDeviceInfo.name))
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                H10Widget.live(act.applicationContext, 0, true)
                connected = false
                main.post { hrJob?.cancel(); ecgJob?.cancel() }
                emit("status", JSONObject().put("state", "disconnected").put("id", polarDeviceInfo.deviceId))
            }

            override fun bleSdkFeatureReady(identifier: String, feature: PolarBleApi.PolarBleSdkFeature) {
                emit("feature", JSONObject().put("f", feature.name))
                if (feature == PolarBleApi.PolarBleSdkFeature.FEATURE_HR) main.post { startHr(identifier) }
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                emit("battery", JSONObject().put("level", level))
            }

            override fun disInformationReceived(identifier: String, disInfo: DisInfo) {}

            override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) {}
        })
    }

    // ---------------- envio de eventos para o JS ----------------
    private fun emit(type: String, data: JSONObject) {
        val js = "window.__h10n && window.__h10n(${JSONObject.quote(type)}, $data)"
        main.post { web.evaluateJavascript(js, null) }
    }

    fun emitError(msg: String) = emit("error", JSONObject().put("msg", msg))

    private fun rec(kind: String, data: JSONObject = JSONObject()) = emit("rec", data.put("kind", kind))

    // ---------------- conexão ----------------
    @JavascriptInterface
    fun version(): String = try {
        act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    @JavascriptInterface
    fun isConnected(): Boolean = connected

    @JavascriptInterface
    fun connect() = main.post {
        pendingConnect = true
        act.requestBlePermissions()
    }

    fun onPermissionsResult(ok: Boolean) {
        if (!ok) {
            pendingConnect = false
            emitError("Sem permissão de Bluetooth. Libere \"Dispositivos próximos\" para o app nas configurações do Android.")
            emit("status", JSONObject().put("state", "disconnected"))
            return
        }
        if (pendingConnect) { pendingConnect = false; doConnect() }
    }

    private fun doConnect() {
        val saved = prefs.getString("dev", null)
        if (saved != null) {
            emit("status", JSONObject().put("state", "connecting").put("id", saved))
            try { api.connectToDevice(saved) } catch (e: Exception) { emitError("Falha ao conectar: ${e.message}") }
            // se a cinta salva não responder, procura qualquer H10 por perto
            scanJob?.cancel()
            scanJob = scope.launch {
                delay(12000)
                if (!connected) {
                    try { api.disconnectFromDevice(saved) } catch (_: Exception) { }
                    scan()
                }
            }
        } else {
            scan()
        }
    }

    private fun scan() {
        scanJob?.cancel()
        emit("status", JSONObject().put("state", "scanning"))
        scanJob = scope.launch {
            try {
                val info = withTimeoutOrNull(20000) {
                    api.searchForDevice().first { it.name.contains("H10", ignoreCase = true) }
                }
                if (info == null) {
                    emitError("Nenhum Polar H10 encontrado. Umedeça os eletrodos, vista a cinta e tente de novo.")
                    emit("status", JSONObject().put("state", "disconnected"))
                } else {
                    api.connectToDevice(info.deviceId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emitError("Falha na busca: ${e.message}")
                emit("status", JSONObject().put("state", "disconnected"))
            }
        }
    }

    @JavascriptInterface
    fun disconnect() = main.post {
        scanJob?.cancel(); hrJob?.cancel(); ecgJob?.cancel()
        val id = deviceId ?: prefs.getString("dev", null)
        if (id != null) try { api.disconnectFromDevice(id) } catch (_: Exception) { }
        connected = false
        emit("status", JSONObject().put("state", "disconnected"))
    }

    @JavascriptInterface
    fun forgetDevice() = main.post { prefs.edit().remove("dev").apply() }

    // ---------------- FC + RR ----------------
    private fun startHr(id: String) {
        hrJob?.cancel()
        hrJob = scope.launch {
            try {
                api.startHrStreaming(id).collect { d ->
                    for (s in d.samples) {
                        val o = JSONObject()
                            .put("hr", s.hr)
                            .put("rr", JSONArray(s.rrsMs))
                            .put("contact", if (s.contactStatusSupported) s.contactStatus else JSONObject.NULL)
                        emit("hr", o)
                        H10Widget.live(act.applicationContext, s.hr)
                        LiveHub.nativeHr = s.hr; LiveHub.nativeHrAt = System.currentTimeMillis()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emitError("FC interrompida: ${e.message}")
            }
        }
    }

    // ---------------- ECG ----------------
    @JavascriptInterface
    fun startEcg() = main.post {
        val id = deviceId
        if (id == null || !connected) { emitError("Conecte o H10 antes de iniciar o ECG."); return@post }
        ecgJob?.cancel()
        ecgJob = scope.launch {
            try {
                val settings = api.requestStreamSettings(id, PolarBleApi.PolarDeviceDataType.ECG)
                emit("ecgState", JSONObject().put("on", true))
                api.startEcgStreaming(id, settings.maxSettings()).collect { d ->
                    val v = JSONArray()
                    var last = 0L
                    for (s in d.samples) if (s is EcgSample) { v.put(s.voltage); last = s.timeStamp }
                    if (v.length() > 0) emit("ecg", JSONObject().put("v", v).put("ts", last.toString()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emitError("ECG: ${e.message}")
            } finally {
                emit("ecgState", JSONObject().put("on", false))
            }
        }
    }

    @JavascriptInterface
    fun stopEcg() = main.post { ecgJob?.cancel() }

    // ---------------- gravação interna do H10 ----------------
    private fun withDevice(block: suspend (String) -> Unit) = main.post {
        val id = deviceId
        if (id == null || !connected) { rec("error", JSONObject().put("msg", "Conecte o H10 primeiro.")); return@post }
        scope.launch {
            try { block(id) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                rec("error", JSONObject().put("msg", e.message ?: e.toString()))
            }
        }
    }

    @JavascriptInterface
    fun recStatus() = withDevice { id ->
        val s = api.requestRecordingStatus(id)
        rec("status", JSONObject().put("ongoing", s.ongoing).put("entryId", s.entryId).put("supported", s.supported))
    }

    /** Inicia a gravação de intervalos RR na memória da cinta. */
    @JavascriptInterface
    fun recStart() = withDevice { id ->
        val exerciseId = "H10PLUS_" + System.currentTimeMillis()
        api.startRecording(id, exerciseId, PolarH10OfflineExerciseApi.RecordingInterval.INTERVAL_1S, PolarH10OfflineExerciseApi.SampleType.RR)
        rec("started", JSONObject().put("entryId", exerciseId))
        val s = api.requestRecordingStatus(id)
        rec("status", JSONObject().put("ongoing", s.ongoing).put("entryId", s.entryId).put("supported", s.supported))
    }

    @JavascriptInterface
    fun recStop() = withDevice { id ->
        api.stopRecording(id)
        rec("stopped")
        val s = api.requestRecordingStatus(id)
        rec("status", JSONObject().put("ongoing", s.ongoing).put("entryId", s.entryId).put("supported", s.supported))
    }

    @JavascriptInterface
    fun recList() = withDevice { id ->
        entries = api.listExercises(id).toList()
        val arr = JSONArray()
        entries.forEachIndexed { i, e -> arr.put(JSONObject().put("i", i).put("date", e.date.toString()).put("path", e.path)) }
        rec("list", JSONObject().put("items", arr))
    }

    @JavascriptInterface
    fun recFetch(index: Int) = withDevice { id ->
        val e = entries.getOrNull(index) ?: throw IllegalStateException("Gravação não encontrada; liste de novo.")
        val data = api.fetchExercise(id, e)
        rec("data", JSONObject()
            .put("i", index)
            .put("date", e.date.toString())
            .put("interval", data.recordingInterval)
            .put("samples", JSONArray(data.hrSamples)))
    }

    @JavascriptInterface
    fun recRemove(index: Int) = withDevice { id ->
        val e = entries.getOrNull(index) ?: throw IllegalStateException("Gravação não encontrada; liste de novo.")
        api.removeExercise(id, e)
        rec("removed", JSONObject().put("i", index))
    }

    // ---------------- utilidades da casca ----------------
    @JavascriptInterface
    fun keepScreenOn(on: Boolean) = act.setKeepScreenOn(on)

    @JavascriptInterface
    fun setImmersive(on: Boolean) = act.setImmersiveMode(on)

    @JavascriptInterface
    fun shareFiles(json: String) = act.shareFiles(json)

    @JavascriptInterface
    fun openUrl(url: String) = act.openExternal(Uri.parse(url))

    // ---------------- Health Connect (dados do relógio via Samsung Health) ----------------
    @JavascriptInterface
    fun hcStatus() = main.post { health.status() }

    @JavascriptInterface
    fun hcRequest() = main.post { health.request() }

    @JavascriptInterface
    fun hcRead(days: Int) = main.post { health.read(days) }

    // ---------------- treino ativo: notificação fixa e bolha ----------------
    @JavascriptInterface
    fun liveState(json: String) = main.post {
        if (json.contains("\"active\":true") && !LiveHub.active) act.requestNotifPermission()
        LiveHub.update(act.applicationContext, json)
    }

    @JavascriptInterface
    fun overlayStatus(): String = JSONObject()
        .put("perm", android.provider.Settings.canDrawOverlays(act))
        .put("on", LiveHub.bubbleOn(act)).toString()

    @JavascriptInterface
    fun setBubble(on: Boolean) { LiveHub.setBubble(act, on); if (on) LiveHub.bubbleDismissed = false }

    @JavascriptInterface
    fun openOverlayPerm() = main.post {
        try {
            act.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + act.packageName)))
        } catch (_: Exception) { }
    }

    @JavascriptInterface
    fun widgetUpdate(json: String) = main.post { H10Widget.setSummary(act.applicationContext, json) }

    @JavascriptInterface
    fun hcWrite(kind: String, json: String) = main.post { health.write(kind, json) }

    // ---------------- relógio (Wearable Data Layer) ----------------
    private val wearUris = HashMap<String, android.net.Uri>()
    @Volatile private var pendingCmd: String? = null

    // dados ao vivo vindos do relógio (FC, distância, ritmo)
    private val msgListener = MessageClient.OnMessageReceivedListener { ev ->
        if (ev.path == "/live") {
            try { emit("wear", JSONObject(String(ev.data)).put("kind", "live")) } catch (_: Exception) { }
        }
    }
    init { try { Wearable.getMessageClient(act).addListener(msgListener) } catch (_: Exception) { } }

    /** Comando vindo do relógio (h10plus://treino?acao=start|stop&...). */
    fun onDeepLink(uri: android.net.Uri) {
        if (uri.scheme != "h10plus") return
        val o = JSONObject()
        for (k in uri.queryParameterNames) o.put(k, uri.getQueryParameter(k))
        pendingCmd = o.toString()
        emit("wear", JSONObject().put("kind", "cmd").put("cmd", o))
    }

    /** O site pergunta, ao carregar, se o app foi aberto por um comando do relógio. */
    @JavascriptInterface
    fun takePendingCmd(): String { val c = pendingCmd ?: ""; pendingCmd = null; return c }

    /** Inicia/encerra o treino no relógio (abre o app de lá se estiver fechado). */
    @JavascriptInterface
    fun wearCmd(acao: String, json: String) = main.post {
        scope.launch {
            try {
                val o = JSONObject(json)
                val b = android.net.Uri.Builder().scheme("h10plus").authority("treino").appendQueryParameter("acao", acao)
                o.keys().forEach { k -> b.appendQueryParameter(k, o.get(k).toString()) }
                val intent = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(b.build())
                val helper = RemoteActivityHelper(act)
                for (node in Wearable.getNodeClient(act).connectedNodes.await()) helper.startRemoteActivity(intent, node.id)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    /** Envia a FC do celular ao relógio (usada lá se o relógio perder a cinta). */
    @JavascriptInterface
    fun wearLive(json: String) = main.post {
        scope.launch {
            try {
                val client = Wearable.getMessageClient(act)
                for (node in Wearable.getNodeClient(act).connectedNodes.await()) client.sendMessage(node.id, "/live", json.toByteArray()).await()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    @JavascriptInterface
    fun wearStatus() = main.post {
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(act).connectedNodes.await()
                val cap = Wearable.getCapabilityClient(act).getCapability("h10plus_wear", CapabilityClient.FILTER_REACHABLE).await()
                emit("wear", JSONObject().put("kind", "status").put("nodes", nodes.size)
                    .put("names", JSONArray(nodes.map { it.displayName })).put("app", cap.nodes.isNotEmpty()))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                emit("wear", JSONObject().put("kind", "status").put("nodes", 0).put("app", false).put("err", e.message ?: e.toString()))
            }
        }
    }

    /** Lê os treinos que o relógio deixou na Data Layer e entrega ao site. */
    @JavascriptInterface
    fun wearSync() = main.post {
        scope.launch {
            try {
                val client = Wearable.getDataClient(act)
                val buf = client.dataItems.await()
                var n = 0
                try {
                    for (item in buf) {
                        val path = item.uri.path ?: continue
                        if (!path.startsWith("/session/")) continue
                        val asset = DataMapItem.fromDataItem(item).dataMap.getAsset("data") ?: continue
                        val fd = client.getFdForAsset(asset).await()
                        val json = GZIPInputStream(fd.inputStream).bufferedReader().use { it.readText() }
                        wearUris[path] = item.uri
                        emit("wear", JSONObject().put("kind", "session").put("path", path).put("json", json))
                        n++
                    }
                } finally { buf.release() }
                emit("wear", JSONObject().put("kind", "synced").put("count", n))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                emit("wear", JSONObject().put("kind", "error").put("msg", e.message ?: e.toString()))
            }
        }
    }

    /** Depois de importado no site, remove o treino da fila do relógio. */
    @JavascriptInterface
    fun wearAck(path: String) = main.post {
        val uri = wearUris.remove(path) ?: return@post
        scope.launch { try { Wearable.getDataClient(act).deleteDataItems(uri).await() } catch (_: Exception) { } }
    }

    /** Envia zonas e FCmáx atuais ao relógio. */
    @JavascriptInterface
    fun wearConfig(json: String) = main.post {
        scope.launch {
            try {
                val req = PutDataMapRequest.create("/config").apply {
                    dataMap.putString("json", json)
                    dataMap.putLong("t", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(act).putDataItem(req).await()
            } catch (_: Exception) { }
        }
    }

    // ---------------- voz (teste guiado) ----------------
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private val audio by lazy { act.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    private val speechAttrs = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focusReq by lazy {
        android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttrs).build()
    }

    private fun initTts() {
        tts = android.speech.tts.TextToSpeech(act.applicationContext) { st ->
            main.post {
                if (st != android.speech.tts.TextToSpeech.SUCCESS) return@post
                val t = tts ?: return@post
                t.language = java.util.Locale("pt", "BR")
                t.setAudioAttributes(speechAttrs)
                t.setSpeechRate(1.05f)
                t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { try { audio.abandonAudioFocusRequest(focusReq) } catch (_: Exception) { } }
                    @Deprecated("") override fun onError(id: String?) { try { audio.abandonAudioFocusRequest(focusReq) } catch (_: Exception) { } }
                })
                ttsReady = true
                pendingSpeech?.let { pendingSpeech = null; say(it) }
            }
        }
    }

    private fun say(text: String) {
        val t = tts ?: return
        try { audio.requestAudioFocus(focusReq) } catch (_: Exception) { }
        t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "h10-" + System.currentTimeMillis())
    }

    /** Fala o texto em português (abaixa a música enquanto fala). */
    @JavascriptInterface
    fun speak(text: String) = main.post {
        if (tts == null) initTts()
        if (ttsReady) say(text) else pendingSpeech = text
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Exception) { }
        try { Wearable.getMessageClient(act).removeListener(msgListener) } catch (_: Exception) { }
        health.shutdown()
        scope.cancel()
        try { api.shutDown() } catch (_: Exception) { }
    }
}
