package br.h10plus.wear

import android.content.Context
import org.json.JSONObject

/** Estado compartilhado entre o serviço (que grava) e a tela (que só mostra). */
object WorkoutState {
    enum class Phase { IDLE, RUNNING, RECOVERY, SENDING, DONE }

    @Volatile var phase = Phase.IDLE
    @Volatile var connState = "desconectado"   // desconectado, procurando, conectando, conectado
    @Volatile var hr = 0
    @Volatile var lastHrMs = 0L
    @Volatile var contact: Boolean? = null
    @Volatile var battery = -1

    @Volatile var type = "cardio"
    @Volatile var useGps = true
    @Volatile var startMs = 0L
    @Volatile var endMs = 0L
    @Volatile var distanceM = 0.0
    @Volatile var paceSecKm = 0.0
    @Volatile var gpsFix = false
    @Volatile var recoveryLeft = 0
    @Volatile var message = ""

    // zonas (limites inferiores em bpm) recebidas do celular
    @Volatile var zb = intArrayOf(90, 107, 125, 143, 161)
    @Volatile var fcmax = 179
    val zoneNames = arrayOf("Z1 Recuperação", "Z2 Aeróbico", "Z3 Tempo", "Z4 Limiar", "Z5 Máximo")
    val zoneColors = intArrayOf(0xFF4C8BF5.toInt(), 0xFF2FBF71.toInt(), 0xFFF2C230.toInt(), 0xFFF2803A.toInt(), 0xFFE5484D.toInt())

    fun zoneOf(h: Int): Int {
        for (k in 4 downTo 0) if (h >= zb[k]) return k
        return -1
    }

    fun loadConfig(ctx: Context) {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        p.getString("json", null)?.let { applyConfig(it) }
    }

    fun applyConfig(json: String) {
        try {
            val o = JSONObject(json)
            val a = o.getJSONArray("zb")
            if (a.length() == 5) zb = IntArray(5) { a.getInt(it) }
            fcmax = o.optInt("fcmax", fcmax)
        } catch (_: Exception) { }
    }
}
