package br.h10plus.wear

import android.content.Context
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey

/**
 * Samsung Health Sensor SDK: FC contínua com intervalos entre batimentos (IBI) e temperatura da pele contínua.
 * Requer o modo desenvolvedor da Health Platform (uso pessoal, sem distribuição).
 */
class SamsungNightSensors : NightSensors {
    override val available = true
    private var service: HealthTrackingService? = null
    private var hrTracker: HealthTracker? = null
    private var tempTracker: HealthTracker? = null

    override fun start(ctx: Context, sink: NightSensors.Sink) {
        val listener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                val svc = service ?: return
                val types = try { svc.trackingCapability.supportHealthTrackerTypes } catch (e: Exception) { emptyList<HealthTrackerType>() }
                try {
                    hrTracker = svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS).also { tr ->
                        tr.setEventListener(object : HealthTracker.TrackerEventListener {
                            override fun onDataReceived(list: List<DataPoint>) {
                                for (dp in list) {
                                    val t = dp.timestamp
                                    try {
                                        val bpm = dp.getValue(ValueKey.HeartRateSet.HEART_RATE)
                                        if (bpm != null && bpm > 0) sink.hr(t, bpm)
                                    } catch (_: Exception) { }
                                    try {
                                        val ibis = dp.getValue(ValueKey.HeartRateSet.IBI_LIST)
                                        val sts = try { dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) } catch (_: Exception) { null }
                                        if (ibis != null) for (i in ibis.indices) {
                                            val v = ibis[i]
                                            if (v > 0) sink.ibi(t, v, sts?.getOrNull(i) ?: 0)
                                        }
                                    } catch (_: Exception) { }
                                }
                            }
                            override fun onFlushCompleted() {}
                            override fun onError(e: HealthTracker.TrackerError) { sink.error("FC: $e") }
                        })
                    }
                } catch (e: Exception) { sink.error("FC contínua indisponível: ${e.message}") }
                if (types.contains(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)) try {
                    tempTracker = svc.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS).also { tr ->
                        tr.setEventListener(object : HealthTracker.TrackerEventListener {
                            override fun onDataReceived(list: List<DataPoint>) {
                                for (dp in list) try {
                                    val skin = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                                    val amb = dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
                                    if (skin != null) sink.temp(dp.timestamp, skin, amb ?: 0f)
                                } catch (_: Exception) { }
                            }
                            override fun onFlushCompleted() {}
                            override fun onError(e: HealthTracker.TrackerError) { sink.error("Temperatura: $e") }
                        })
                    }
                } catch (e: Exception) { sink.error("Temperatura da pele indisponível: ${e.message}") }
            }
            override fun onConnectionEnded() {}
            override fun onConnectionFailed(e: HealthTrackerException) {
                sink.error("Health Platform recusou a conexão (código ${e.errorCode}). Ative o modo desenvolvedor da Health Platform.")
            }
        }
        service = HealthTrackingService(listener, ctx.applicationContext).also { it.connectService() }
    }

    override fun stop() {
        try { hrTracker?.unsetEventListener() } catch (_: Exception) { }
        try { tempTracker?.unsetEventListener() } catch (_: Exception) { }
        try { service?.disconnectService() } catch (_: Exception) { }
        hrTracker = null; tempTracker = null; service = null
    }
}
