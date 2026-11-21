package br.h10plus.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.PolarH10OfflineExerciseApi
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarExerciseEntry
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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ponte entre o site (JavaScript) e o SDK da Polar.
 * JS -> nativo: métodos @JavascriptInterface (objeto window.H10Native).
 * Nativo -> JS: window.__h10n(tipo, dados).
 */
class H10Bridge(private val act: MainActivity, private val web: WebView) {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = act.getSharedPreferences("h10", Context.MODE_PRIVATE)

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
    fun setImmersive(on: Boolean) = act.setImmersive(on)

    @JavascriptInterface
    fun shareFiles(json: String) = act.shareFiles(json)

    @JavascriptInterface
    fun openUrl(url: String) = act.openExternal(Uri.parse(url))

    fun shutdown() {
        scope.cancel()
        try { api.shutDown() } catch (_: Exception) { }
    }
}
