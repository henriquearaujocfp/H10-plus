package br.h10plus.app

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
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
import java.time.ZoneId
import java.time.ZoneOffset
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

    // gravação: treinos, FC, distância, velocidade, calorias, percurso, VO2máx, FC de repouso e HRV
    private val writeTypes: Map<String, KClass<out Record>> = linkedMapOf(
        "w_exercise" to ExerciseSessionRecord::class,
        "w_hr" to HeartRateRecord::class,
        "w_dist" to DistanceRecord::class,
        "w_speed" to SpeedRecord::class,
        "w_kcal" to TotalCaloriesBurnedRecord::class,
        "w_vo2" to Vo2MaxRecord::class,
        "w_rhr" to RestingHeartRateRecord::class,
        "w_hrv" to HeartRateVariabilityRmssdRecord::class
    )
    private val ROUTE_WRITE = "android.permission.health.WRITE_EXERCISE_ROUTE"

    val permissions: Set<String> =
        types.values.map { HealthPermission.getReadPermission(it) }.toSet() +
            writeTypes.values.map { HealthPermission.getWritePermission(it) }.toSet() +
            ROUTE_WRITE +
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
        writeTypes.forEach { (k, cls) -> if (HealthPermission.getWritePermission(cls) in granted) arr.put(k) }
        if (ROUTE_WRITE in granted) arr.put("w_route")
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

    // ---------------- gravação ----------------
    private fun off(t: Instant): ZoneOffset = ZoneId.systemDefault().rules.getOffset(t)
    private fun meta(id: String, version: Long) =
        Metadata.activelyRecorded(clientRecordId = "h10plus-$id", clientRecordVersion = version, device = Device(type = Device.TYPE_CHEST_STRAP))

    fun write(kind: String, json: String) {
        if (sdkStatus() != "available") return
        scope.launch {
            val client = HealthConnectClient.getOrCreate(act)
            val o = JSONObject(json)
            val id = o.getString("id")
            val ver = System.currentTimeMillis()
            val done = JSONArray(); val fail = JSONObject()
            suspend fun put(tag: String, recs: List<Record>) {
                if (recs.isEmpty()) return
                try { client.insertRecords(recs); done.put(tag) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { fail.put(tag, e.message ?: e.toString()) }
            }
            when (kind) {
                "workout" -> {
                    val st = Instant.ofEpochMilli(o.getLong("start")); val en = Instant.ofEpochMilli(o.getLong("end"))
                    val type = when (o.optString("exType")) {
                        "running" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                        "walking" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
                        "strength" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
                        "biking" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
                        else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
                    }
                    // percurso (GPS do relógio)
                    val gps = o.optJSONArray("gps")
                    val locs = ArrayList<ExerciseRoute.Location>()
                    val speeds = ArrayList<SpeedRecord.Sample>()
                    if (gps != null) for (i in 0 until gps.length()) {
                        val g = gps.getJSONArray(i)
                        val t = Instant.ofEpochMilli(g.getLong(0))
                        if (t.isBefore(st) || t.isAfter(en)) continue
                        val acc = g.optDouble(4, 99.0)
                        if (acc > 25) continue
                        locs.add(ExerciseRoute.Location(time = t, latitude = g.getDouble(1), longitude = g.getDouble(2),
                            horizontalAccuracy = Length.meters(acc), altitude = Length.meters(g.optDouble(3, 0.0))))
                        val sp = g.optDouble(5, -1.0)
                        if (sp in 0.0..12.0) speeds.add(SpeedRecord.Sample(time = t, speed = Velocity.metersPerSecond(sp)))
                    }
                    fun session(route: ExerciseRoute?) = ExerciseSessionRecord(
                        startTime = st, startZoneOffset = off(st), endTime = en, endZoneOffset = off(en),
                        metadata = meta("$id-sessao", ver), exerciseType = type,
                        title = o.optString("title", "Treino"), notes = o.optString("notes", ""),
                        exerciseRoute = route
                    )
                    val withRoute = locs.size >= 2
                    try {
                        client.insertRecords(listOf(session(if (withRoute) ExerciseRoute(locs) else null)))
                        done.put(if (withRoute) "sessao+percurso" else "sessao")
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        if (withRoute) put("sessao", listOf(session(null))) else fail.put("sessao", e.message ?: e.toString())
                    }
                    // FC (em blocos de 1 h)
                    val hr = o.optJSONArray("hr")
                    if (hr != null && hr.length() > 0) {
                        val samples = ArrayList<HeartRateRecord.Sample>()
                        for (i in 0 until hr.length()) {
                            val h = hr.getJSONArray(i); val t = Instant.ofEpochMilli(h.getLong(0))
                            if (!t.isBefore(st) && !t.isAfter(en)) samples.add(HeartRateRecord.Sample(time = t, beatsPerMinute = h.getLong(1)))
                        }
                        val recs = samples.chunked(3600).mapIndexed { k, c ->
                            HeartRateRecord(startTime = c.first().time, startZoneOffset = off(c.first().time),
                                endTime = c.last().time.plusMillis(1), endZoneOffset = off(c.last().time),
                                samples = c, metadata = meta("$id-fc-$k", ver))
                        }
                        put("fc", recs)
                    }
                    if (o.has("dist") && !o.isNull("dist") && o.getDouble("dist") > 0)
                        put("distancia", listOf(DistanceRecord(startTime = st, startZoneOffset = off(st), endTime = en, endZoneOffset = off(en),
                            distance = Length.meters(o.getDouble("dist")), metadata = meta("$id-dist", ver))))
                    if (speeds.size >= 2)
                        put("velocidade", listOf(SpeedRecord(startTime = speeds.first().time, startZoneOffset = off(st),
                            endTime = speeds.last().time.plusMillis(1), endZoneOffset = off(en), samples = speeds, metadata = meta("$id-vel", ver))))
                    if (o.has("kcal") && !o.isNull("kcal") && o.getDouble("kcal") > 0)
                        put("calorias", listOf(TotalCaloriesBurnedRecord(startTime = st, startZoneOffset = off(st), endTime = en, endZoneOffset = off(en),
                            energy = Energy.kilocalories(o.getDouble("kcal")), metadata = meta("$id-kcal", ver))))
                }
                "vo2" -> {
                    val t = Instant.ofEpochMilli(o.getLong("t"))
                    val method = if (o.optString("method") == "ratio") Vo2MaxRecord.MEASUREMENT_METHOD_HEART_RATE_RATIO else Vo2MaxRecord.MEASUREMENT_METHOD_OTHER
                    put("vo2max", listOf(Vo2MaxRecord(time = t, zoneOffset = off(t), vo2MillilitersPerMinuteKilogram = o.getDouble("value"),
                        measurementMethod = method, metadata = meta("$id-vo2", ver))))
                }
                "rest" -> {
                    val t = Instant.ofEpochMilli(o.getLong("t"))
                    put("fc_repouso", listOf(RestingHeartRateRecord(time = t, zoneOffset = off(t), beatsPerMinute = Math.round(o.getDouble("hr")),
                        metadata = meta("$id-rhr", ver))))
                    put("hrv", listOf(HeartRateVariabilityRmssdRecord(time = t, zoneOffset = off(t), heartRateVariabilityMillis = o.getDouble("rmssd"),
                        metadata = meta("$id-hrv", ver))))
                }
            }
            hc("written", JSONObject().put("id", id).put("what", kind).put("done", done).put("fail", fail))
        }
    }

    fun shutdown() = scope.cancel()
}
