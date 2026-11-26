package br.h10plus.app

import android.content.Context
import com.polar.sdk.api.PolarBleApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Polar Loop / Polar 360: pareia, conecta só durante a sincronização e baixa
 * sono, Nightly Recharge, temperatura da pele do sono, FC 24/7 e intervalos pulso a pulso (PPI) 24/7.
 */
class LoopBridge(
    private val ctx: Context,
    private val api: PolarBleApi,
    private val scope: CoroutineScope,
    private val emit: (String, JSONObject) -> Unit
) {
    private val prefs = ctx.getSharedPreferences("h10", Context.MODE_PRIVATE)
    @Volatile var loopId: String? = prefs.getString("loop", null); private set
    @Volatile private var connected = false
    private var connectedWait = CompletableDeferred<Unit>()
    private val ready = HashMap<PolarBleApi.PolarBleSdkFeature, CompletableDeferred<Unit>>()
    private var job: Job? = null

    fun isLoop(id: String?) = id != null && id == loopId

    private fun ev(kind: String, o: JSONObject = JSONObject()) = emit("loop", o.put("kind", kind))
    private fun waitFor(f: PolarBleApi.PolarBleSdkFeature) = synchronized(ready) { ready.getOrPut(f) { CompletableDeferred() } }

    fun onConnected() { connected = true; connectedWait.complete(Unit); ev("status", JSONObject().put("state", "connected")) }
    fun onDisconnected() {
        connected = false
        connectedWait = CompletableDeferred()
        synchronized(ready) { ready.clear() }
        ev("status", JSONObject().put("state", "disconnected"))
    }
    fun onFeature(f: PolarBleApi.PolarBleSdkFeature) { waitFor(f).complete(Unit) }

    fun status(): String = JSONObject().put("id", loopId ?: JSONObject.NULL).put("connected", connected).toString()

    fun forget() { loopId = null; prefs.edit().remove("loop").apply(); ev("status", JSONObject().put("state", "forgotten")) }

    /** Procura uma Polar Loop/360 por perto e guarda o identificador. */
    fun pair() {
        job?.cancel()
        job = scope.launch {
            ev("status", JSONObject().put("state", "scanning"))
            try {
                val info = withTimeoutOrNull(25000) {
                    api.searchForDevice().first { val n = it.name.lowercase(); n.contains("loop") || n.contains("360") }
                }
                if (info == null) { ev("error", JSONObject().put("msg", "Nenhuma Polar Loop encontrada. Deixe a pulseira perto do celular (fora do carregador) e tente de novo.")); return@launch }
                loopId = info.deviceId
                prefs.edit().putString("loop", info.deviceId).apply()
                ev("paired", JSONObject().put("id", info.deviceId).put("name", info.name))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                ev("error", JSONObject().put("msg", "Falha na busca: ${e.message}"))
            }
        }
    }

    /** Conecta, baixa os últimos [days] dias e desconecta. */
    fun sync(days: Int) {
        val id = loopId ?: run { ev("error", JSONObject().put("msg", "Pareie a Polar Loop primeiro.")); return }
        job?.cancel()
        job = scope.launch {
            val wasConnected = connected
            try {
                ev("status", JSONObject().put("state", "syncing"))
                if (!connected) {
                    api.connectToDevice(id)
                    withTimeoutOrNull(30000) { connectedWait.await() } ?: run {
                        ev("error", JSONObject().put("msg", "A Polar Loop não respondeu. Ela pode estar conectada ao app Polar Flow agora; feche-o e tente de novo."))
                        try { api.disconnectFromDevice(id) } catch (_: Exception) { }
                        return@launch
                    }
                }
                withTimeoutOrNull(20000) { waitFor(PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ACTIVITY_DATA).await() }
                withTimeoutOrNull(5000) { waitFor(PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_SLEEP_DATA).await() }
                val to = LocalDate.now()
                val from = to.minusDays((days - 1).coerceAtLeast(0).toLong())
                val zone = ZoneId.systemDefault()
                val out = JSONObject().put("from", from.toString()).put("to", to.toString())
                val errs = JSONObject()

                // sono (com fases e temperatura da pele)
                try {
                    val arr = JSONArray()
                    for (s in api.getSleep(id, from, to)) {
                        val r = s.result ?: continue
                        val st = r.sleepStartTime ?: continue
                        val en = r.sleepEndTime ?: continue
                        val ph = JSONArray()
                        r.sleepWakePhases?.forEach { p -> ph.put(JSONArray().put(p.secondsFromSleepStart).put(p.state.name)) }
                        val o = JSONObject().put("date", (r.sleepResultDate ?: s.date)?.toString())
                            .put("s", st.toInstant().toEpochMilli()).put("e", en.toInstant().toEpochMilli()).put("phases", ph)
                        r.sleepSkinTemperatureResult?.let { t -> o.put("temp", t.sleepSkinTemperatureCelsius.toDouble()).put("tempDev", t.deviationFromBaseLine.toDouble()) }
                        arr.put(o)
                    }
                    out.put("sleep", arr)
                } catch (e: CancellationException) { throw e } catch (e: Exception) { errs.put("sono", e.message ?: e.toString()) }

                // Nightly Recharge (HRV, RRI e respiração médias da noite e da base da própria Polar)
                try {
                    val arr = JSONArray()
                    for (n in api.getNightlyRecharge(id, from, to)) {
                        arr.put(JSONObject().put("date", n.sleepResultDate?.toString())
                            .put("ans", n.ansStatus?.toDouble() ?: JSONObject.NULL)
                            .put("recovery", n.recoveryIndicator ?: JSONObject.NULL)
                            .put("rmssd", n.meanNightlyRecoveryRMSSD ?: JSONObject.NULL)
                            .put("rri", n.meanNightlyRecoveryRRI ?: JSONObject.NULL)
                            .put("respInt", n.meanNightlyRecoveryRespirationInterval ?: JSONObject.NULL)
                            .put("bRmssd", n.meanBaselineRMSSD ?: JSONObject.NULL).put("bRmssdSd", n.sdBaselineRMSSD ?: JSONObject.NULL)
                            .put("bRri", n.meanBaselineRRI ?: JSONObject.NULL).put("bRriSd", n.sdBaselineRRI ?: JSONObject.NULL))
                    }
                    out.put("recharge", arr)
                } catch (e: CancellationException) { throw e } catch (e: Exception) { errs.put("recharge", e.message ?: e.toString()) }

                // PPI 24/7 (intervalos pulso a pulso, com estimativa de erro)
                try {
                    val arr = JSONArray()
                    for (d in api.get247PPiSamples(id, from, to)) {
                        val smp = d.samples
                        val base = d.date.atTime(smp.startTime).atZone(zone).toInstant().toEpochMilli()
                        arr.put(JSONObject().put("t", base).put("ppi", JSONArray(smp.ppiValueList)).put("err", JSONArray(smp.ppiErrorEstimateList)))
                    }
                    out.put("ppi", arr)
                } catch (e: CancellationException) { throw e } catch (e: Exception) { errs.put("ppi", e.message ?: e.toString()) }

                // FC 24/7 (últimos 2 dias, para não pesar)
                try {
                    val arr = JSONArray()
                    for (d in api.get247HrSamples(id, to.minusDays(1), to)) for (s in d.samples) {
                        val base = d.date.atTime(s.startTime).atZone(zone).toInstant().toEpochMilli()
                        arr.put(JSONObject().put("t", base).put("hr", JSONArray(s.hrSamples)))
                    }
                    out.put("hr", arr)
                } catch (e: CancellationException) { throw e } catch (e: Exception) { errs.put("fc", e.message ?: e.toString()) }

                out.put("errors", errs)
                ev("data", out)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                ev("error", JSONObject().put("msg", "Falha na sincronização: ${e.message}"))
            } finally {
                if (!wasConnected) { delay(500); try { api.disconnectFromDevice(id) } catch (_: Exception) { } }
            }
        }
    }
}
