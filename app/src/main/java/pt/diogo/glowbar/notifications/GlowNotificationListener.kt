package pt.diogo.glowbar.notifications

import android.app.Notification
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

    override fun onCreate() {
        super.onCreate()
        rules = AppRulesRepository(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        // Só se suprime com a Glowbar ativa; caso contrário ficarias sem
        // notificação e sem sinal luminoso nenhum.
        val glow = GlowController.active ?: return

        if (rules.isPrivate(sbn.packageName)) {
            glow.onPrivateNotification(rules.colorFor(sbn.packageName), sbn.packageName)
            rules.logEvent(sbn.packageName)
            if (sbn.isClearable) runCatching { cancelNotification(sbn.key) }
            return
        }

        val extras = sbn.notification.extras
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)

        if (max > 0 && !indeterminate) {
            val prog = extras.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max
            glow.onNotificationEvent(sbn.key, prog.coerceIn(0f, 1f))
        } else if (!sbn.isOngoing) {
            glow.onNotificationEvent(sbn.key, null)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        GlowController.active?.onNotificationGone(sbn.key)
    }
}
