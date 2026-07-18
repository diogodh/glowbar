package pt.diogo.glowbar.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import pt.diogo.glowbar.overlay.GlowOverlayService

/**
 * Reage a notificações do sistema:
 *  - com progresso -> a barra torna-se um indicador de progresso
 *  - novas notificações -> flash breve (Notification Glow)
 *
 * O conteúdo das notificações não é lido, guardado nem partilhado;
 * apenas se usa o campo de progresso e o evento em si.
 */
class GlowNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val svc = GlowOverlayService.instance ?: return

        val extras = sbn.notification.extras
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)

        if (max > 0 && !indeterminate) {
            val prog = extras.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max
            svc.onNotificationEvent(sbn.key, prog.coerceIn(0f, 1f))
        } else if (!sbn.isOngoing) {
            svc.onNotificationEvent(sbn.key, null)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        GlowOverlayService.instance?.onNotificationGone(sbn.key)
    }
}
