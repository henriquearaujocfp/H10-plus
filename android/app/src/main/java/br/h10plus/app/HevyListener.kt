package br.h10plus.app

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Quando o Hevy mostra a notificação fixa de treino em andamento, abre o Monitor H10 iniciando
 * um treino de musculação; quando essa notificação some (treino terminado no Hevy), encerra.
 * Só age se a opção "Iniciar com o Hevy" estiver ligada no app.
 */
class HevyListener : NotificationListenerService() {

    companion object {
        const val HEVY = "com.hevy"

        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, HevyListener::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }

    private fun enabled() = getSharedPreferences("h10", Context.MODE_PRIVATE).getBoolean("hevyAuto", false)

    private fun isWorkoutNotif(sbn: StatusBarNotification): Boolean =
        sbn.packageName == HEVY && (sbn.isOngoing || (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)

    private fun send(acao: String) {
        val uri = Uri.Builder().scheme("h10plus").authority("treino")
            .appendQueryParameter("acao", acao)
            .appendQueryParameter("tipo", "forca")
            .appendQueryParameter("origem", "hevy")
            .appendQueryParameter("t", System.currentTimeMillis().toString())
            .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri, this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!enabled() || !isWorkoutNotif(sbn)) return
        val p = getSharedPreferences("h10", Context.MODE_PRIVATE)
        if (p.getString("hevyKey", null) != null) return           // já iniciado para esta notificação
        p.edit().putString("hevyKey", sbn.key).apply()
        send("start")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != HEVY) return
        val p = getSharedPreferences("h10", Context.MODE_PRIVATE)
        val key = p.getString("hevyKey", null) ?: return
        if (sbn.key != key) return
        p.edit().remove("hevyKey").apply()
        if (enabled()) send("stop")
    }

    override fun onListenerConnected() {
        // se a notificação do Hevy não existe mais (ex.: celular reiniciado), limpa o estado
        val p = getSharedPreferences("h10", Context.MODE_PRIVATE)
        val key = p.getString("hevyKey", null) ?: return
        val still = try { activeNotifications?.any { it.key == key } ?: false } catch (_: Exception) { false }
        if (!still) p.edit().remove("hevyKey").apply()
    }
}
