package br.h10plus.app

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Leitura dos dados do Galaxy Watch que o Samsung Health sincroniza com o Health Connect:
 * sono (estágios), FC, FC de repouso, HRV, SpO2, frequência respiratória e temperatura da pele.
 * O resumo por noite é calculado no site (JS), para continuar se atualizando sem reinstalar o app.
 */
class HealthBridge(private val act: MainActivity, private val emit: (String, JSONObject) -> Unit) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val types: Map<String, KClass<out Record>> = linkedMapOf(
        "sleep" to SleepSessionRecord::class,
        "hr" to HeartRateRecord::class,
        "rhr" to RestingHeartRateRecord::class,
        "hrv" to HeartRateVariabilityRmssdRecord::class,
        "spo2" to OxygenSaturationRecord::class,
        "resp" to RespiratoryRateRecord::class,
        "skin" to SkinTemperatureRecord::class
    )

    val permissions: Set<String> =
        types.values.map { HealthPermission.getReadPermission(it) }.toSet() +
            "android.permission.health.READ_HEALTH_DATA_HISTORY"

    private fun sdkStatus(): String = when (HealthConnectClient.getSdkStatus(act)) {
        HealthConnectClient.SDK_AVAILABLE -> "available"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update"
        else -> "unavailable"
    }

    private fun hc(kind: String, o: JSONObject = JSONObject()) = emit("hc", o.put("kind", kind))

    private fun grantedKeys(granted: Set<String>): JSONArray {
        val arr = JSONArray()
        types.forEach { (k, cls) -> if (HealthPermission.getReadPermission(cls) in granted) arr.put(k) }
        if ("android.permission.health.READ_HEALTH_DATA_HISTORY" in granted) arr.put("history")
        return arr
    }

    fun status() {
        val st = sdkStatus()
        if (st != "available") { hc("status", JSONObject().put("sdk", st).put("granted", JSONArray())); return }
        scope.launch {
            try {
                val g = HealthConnectClient.getOrCreate(act).permissionController.getGrantedPermissions()
                hc("status", JSONObject().put("sdk", st).put("granted", grantedKeys(g)))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                hc("error", JSONObject().put("msg", "Health Connect: ${e.message}"))
            }
        }
    }

    fun request() {
        val st = sdkStatus()
        if (st != "available") { hc("status", JSONObject().put("sdk", st).put("granted", JSONArray())); return }
        act.requestHealthPermissions(permissions)
    }

    fun onPermissionResult(granted: Set<String>) {
        hc("perm", JSONObject().put("granted", grantedKeys(granted)))
    }

    private suspend fun <T : Record> readAll(client: HealthConnectClient, cls: KClass<T>, start: Instant, end: Instant): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(recordType = cls, timeRangeFilter = TimeRangeFilter.between(start, end), pageSize = 1000, pageToken = token)
            )
            out.addAll(resp.records)
            token = resp.pageToken
        } while (token != null)
        return out
    }

    fun read(days: Int) {
        if (sdkStatus() != "available") { status(); return }
        scope.launch {
            val client = HealthConnectClient.getOrCreate(act)
            val end = Instant.now()
            val start = end.minus(Duration.ofDays(days.coerceIn(1, 120).toLong()))
            val out = JSONObject().put("from", start.toEpochMilli()).put("to", end.toEpochMilli())
            val errors = JSONObject()
            val granted = try { client.permissionController.getGrantedPermissions() } catch (e: Exception) { emptySet() }
            fun ok(k: String) = HealthPermission.getReadPermission(types.getValue(k)) in granted

            // sono
            val windows = ArrayList<Pair<Long, Long>>()
            if (ok("sleep")) try {
                val arr = JSONArray()
                for (r in readAll(client, SleepSessionRecord::class, start, end)) {
                    val s = r.startTime.toEpochMilli(); val e = r.endTime.toEpochMilli()
                    windows.add(s to e)
                    val st = JSONArray()
                    for (g in r.stages) st.put(JSONArray().put(g.startTime.toEpochMilli()).put(g.endTime.toEpochMilli()).put(g.stage))
                    arr.put(JSONObject().put("s", s).put("e", e).put("st", st).put("src", r.metadata.dataOrigin.packageName))
                }
                out.put("sleep", arr)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { errors.put("sleep", e.message ?: e.toString()) }

            // FC: só as amostras dentro do sono (mantém o volume pequeno)
            if (ok("hr")) try {
                val arr = JSONArray()
                for (r in readAll(client, HeartRateRecord::class, start, end)) for (smp in r.samples) {
                    val t = smp.time.toEpochMilli()
                    if (windows.any { t >= it.first && t <= it.second }) arr.put(JSONArray().put(t).put(smp.beatsPerMinute))
                }
                out.put("hr", arr)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { errors.put("hr", e.message ?: e.toString()) }

            if (ok("rhr")) try {
                val arr = JSONArray()
                for (r in readAll(client, RestingHeartRateRecord::class, start, end)) arr.put(JSONArray().put(r.time.toEpochMilli()).put(r.beatsPerMinute))
                out.put("rhr", arr)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { errors.put("rhr", e.message ?: e.toString()) }

            if (ok("hrv")) try {
                val arr = JSONArray()
                for (r in readAll(client, HeartRateVariabilityRmssdRecord::class, start, end)) arr.put(JSONArray().put(r.time.toEpochMilli()).put(r.heartRateVariabilityMillis))
                out.put("hrv", arr)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { errors.put("hrv", e.message ?: e.toString()) }

            if (ok("spo2")) try {
                val arr = JSONArray()
                for (r in readAll(client, OxygenSaturationRecord::class, start, end)) arr.put(JSONArray().put(r.time.toEpochMilli()).put(r.percentage.value))
                out.put("spo2", arr)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { errors.put("spo2", e.message ?: e.toString()) }

            if (ok("resp")) try {
                val arr = JSONArray()
                for (r in readAll(client, RespiratoryRateRecord::class, start, end)) arr.put(JSONArray().put(r.time.toEpochMilli()).put(r.rate))
                out.put("resp", arr)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { errors.put("resp", e.message ?: e.toString()) }

            if (ok("skin")) try {
                val arr = JSONArray()
                for (r in readAll(client, SkinTemperatureRecord::class, start, end)) {
                    val d = JSONArray()
                    for (x in r.deltas) d.put(JSONArray().put(x.time.toEpochMilli()).put(x.delta.inCelsius))
                    arr.put(JSONObject().put("s", r.startTime.toEpochMilli()).put("e", r.endTime.toEpochMilli())
                        .put("base", r.baseline?.inCelsius ?: JSONObject.NULL).put("d", d))
                }
                out.put("skin", arr)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { errors.put("skin", e.message ?: e.toString()) }

            out.put("errors", errors).put("granted", grantedKeys(granted))
            hc("data", out)
        }
    }

    fun shutdown() = scope.cancel()
}
