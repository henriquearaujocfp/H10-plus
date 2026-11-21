package br.h10plus.app

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Tela exigida pelo Health Connect para explicar o uso dos dados de saúde. */
class PrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            setPadding(pad, pad, pad, pad)
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 16f
            text = """
                Monitor H10 — uso dos dados de saúde

                O app lê do Health Connect: sono (estágios), frequência cardíaca, frequência cardíaca de repouso, variabilidade da frequência cardíaca, saturação de oxigênio, frequência respiratória e temperatura da pele.

                Os dados são usados só para mostrar resumos noturnos e tendências no próprio app. Ficam armazenados apenas neste celular e não são enviados para nenhum servidor.

                Você pode revogar o acesso a qualquer momento nas configurações do Health Connect.
            """.trimIndent()
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF0D1117.toInt()); addView(tv) })
    }
}
