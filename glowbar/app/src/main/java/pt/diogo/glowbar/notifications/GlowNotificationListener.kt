package pt.diogo.glowbar.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import pt.diogo.glowbar.AppRulesRepository
import pt.diogo.glowbar.overlay.GlowOverlayService

/**
 * Reage a notificações do sistema:
 *  - apps marcadas como privadas -> flash (com cor própria) e remoção imediata
 *    da notificação do sistema
 *  - notificações com progresso -> a barra torna-se indicador de progresso
 *  - restantes notificações -> flash breve (Notification Glow)
 *
 * O conteúdo das notificações não é lido, guardado nem partilhado. Das apps
 * privadas regista-se apenas o nome do pacote e a hora, para poderes ver
 * depois na aplicação o que reclamou a tua atenção.
 */
class GlowNotificationListener : NotificationListenerService() {

    private lateinit var rules: AppRulesRepository

    override fun onCreate() {
        super.onCreate()
        rules = AppRulesRepository(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        // ---------------- apps privadas: só glow, sem sistema ----------------
        // Só se suprime quando a Glowbar está mesmo ativa; caso contrário
        // ficarias sem notificação e sem sinal luminoso nenhum.
        val running = GlowOverlayService.instance
        if (running != null && rules.isPrivate(sbn.packageName)) {
            running.onPrivateNotification(rules.colorFor(sbn.packageName), sbn.packageName)
            rules.logEvent(sbn.packageName)
            if (sbn.isClearable) {
                runCatching { cancelNotification(sbn.key) }
            }
            return
        }

        val svc = running ?: return
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
