package br.h10plus.wear

import android.content.Context

/** Fonte dos dados noturnos do relógio (implementada com o SDK de sensores da Samsung quando ele está no build). */
interface NightSensors {
    /** Recebe lotes: intervalos entre batimentos [t, ibi, status], FC [t, bpm], temperatura [t, pele, ambiente]. */
    interface Sink {
        fun ibi(t: Long, ibiMs: Int, status: Int)
        fun hr(t: Long, bpm: Int)
        fun temp(t: Long, skin: Float, ambient: Float)
        fun error(msg: String)
    }
    val available: Boolean
    fun start(ctx: Context, sink: Sink)
    fun stop()
}

object NightSensorsProvider {
    fun create(): NightSensors = SamsungNightSensors()
}
