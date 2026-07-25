package pt.diogo.glowbar.notifications

import android.app.Notification
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import pt.diogo.glowbar.AppRulesRepository
import pt.diogo.glowbar.overlay.GlowController

/**
 * O conteúdo das notificações não é lido, guardado nem partilhado. Das apps
 * privadas regista-se apenas o nome do pacote e a hora.
 */
class GlowNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "GlowNotifListener"
        private const val PRIVATE_DEDUP_MS = 750L
        private const val CANCEL_RETRY_MS = 180L
        private const val MAX_DEDUP_ENTRIES = 128
    }

    private lateinit var rules: AppRulesRepository
    private val lastPrivateSignalAt = LinkedHashMap<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())

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
            handlePrivateNotification(glow, sbn)
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

    private fun handlePrivateNotification(
        glow: GlowController,
        sbn: StatusBarNotification
    ) {
        if (!glow.canSafelySuppressPrivateNotification()) {
            Log.w(
                TAG,
                "Notificação privada mantida porque o glow não está visível nem persistente: " +
                    sbn.packageName
            )
            return
        }

        if (!sbn.isClearable) {
            Log.w(TAG, "Notificação não dispensável; não pode ser suprimida: ${sbn.packageName}")
            return
        }

        // Primeiro confirma que o pedido de cancelamento foi aceite. Assim,
        // uma exceção não cria um aviso pendente em duplicado com a notificação
        // original ainda visível.
        if (!cancelWithVerification(sbn)) return

        // Summary e child podem chegar quase em simultâneo. Tenta cancelar
        // ambos, mas agrega o sinal por pacote durante uma janela curta.
        val now = SystemClock.elapsedRealtime()
        val last = lastPrivateSignalAt[sbn.packageName]
        if (last == null || now - last >= PRIVATE_DEDUP_MS) {
            lastPrivateSignalAt[sbn.packageName] = now
            pruneDedupMap()
            glow.onPrivateNotification(rules.colorFor(sbn.packageName), sbn.packageName)
            rules.logEvent(sbn.packageName)
        }
    }

    private fun cancelWithVerification(sbn: StatusBarNotification): Boolean {
        val firstAttempt = runCatching { cancelNotification(sbn.key) }
        if (firstAttempt.isFailure) {
            Log.e(TAG, "Falha ao cancelar ${sbn.packageName}", firstAttempt.exceptionOrNull())
            return false
        }

        // Alguns fabricantes reapresentam a mesma key imediatamente. Confirma
        // uma vez e repete o cancelamento, sem criar loops permanentes.
        mainHandler.postDelayed({
            val stillActive = runCatching {
                activeNotifications?.any { it.key == sbn.key } == true
            }.onFailure {
                Log.w(TAG, "Não foi possível confirmar o cancelamento", it)
            }.getOrDefault(false)

            if (stillActive) {
                runCatching { cancelNotification(sbn.key) }
                    .onFailure { Log.e(TAG, "Segunda tentativa de cancelamento falhou", it) }
            }
        }, CANCEL_RETRY_MS)
        return true
    }

    private fun pruneDedupMap() {
        while (lastPrivateSignalAt.size > MAX_DEDUP_ENTRIES) {
            val first = lastPrivateSignalAt.entries.firstOrNull()?.key ?: return
            lastPrivateSignalAt.remove(first)
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

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
