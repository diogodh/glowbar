package pt.diogo.glowbar.notifications

import android.app.Notification
import android.content.ComponentName
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import pt.diogo.glowbar.AppRulesRepository
import pt.diogo.glowbar.overlay.GlowController

/**
 * O conteúdo das notificações não é lido, guardado nem partilhado. Das apps
 * privadas regista-se apenas o nome do pacote e a hora.
 */
class GlowNotificationListener : NotificationListenerService() {

    private lateinit var rules: AppRulesRepository
    private val lastPrivateSignalAt = mutableMapOf<String, Long>()

    override fun onCreate() {
        super.onCreate()
        rules = AppRulesRepository(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        // Só se suprime com um overlay realmente ativo; caso contrário o
        // utilizador ficaria sem a notificação e sem qualquer sinal luminoso.
        val glow = GlowController.active ?: return
        val isGroupSummary =
            (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

        if (rules.isPrivate(sbn.packageName)) {
            // Summary e child podem chegar quase em simultâneo. Cancela ambos,
            // mas agrega o sinal por pacote durante uma janela muito curta para
            // não produzir dois flashes/registos para a mesma chegada.
            val now = SystemClock.elapsedRealtime()
            val last = lastPrivateSignalAt[sbn.packageName]
            if (last == null || now - last >= 750L) {
                lastPrivateSignalAt[sbn.packageName] = now
                glow.onPrivateNotification(rules.colorFor(sbn.packageName), sbn.packageName)
                rules.logEvent(sbn.packageName)
            }
            if (sbn.isClearable) runCatching { cancelNotification(sbn.key) }
            return
        }

        val extras = sbn.notification.extras
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)

        if (max > 0 && !indeterminate) {
            val prog = extras.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max
            glow.onNotificationEvent(sbn.key, prog.coerceIn(0f, 1f))
        } else if (!sbn.isOngoing && !isGroupSummary) {
            glow.onNotificationEvent(sbn.key, null)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        GlowController.active?.onNotificationGone(sbn.key)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationListenerService.requestRebind(
            ComponentName(this, GlowNotificationListener::class.java)
        )
    }
}
