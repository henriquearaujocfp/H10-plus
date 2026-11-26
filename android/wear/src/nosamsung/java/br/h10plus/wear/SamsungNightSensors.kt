package br.h10plus.wear

import android.content.Context

/** Build sem o SDK de sensores da Samsung: o modo noite fica indisponível. */
class SamsungNightSensors : NightSensors {
    override val available = false
    override fun start(ctx: Context, sink: NightSensors.Sink) {
        sink.error("Este build não inclui o SDK de sensores da Samsung.")
    }
    override fun stop() {}
}
