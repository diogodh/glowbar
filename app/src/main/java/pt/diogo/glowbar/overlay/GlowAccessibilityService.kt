package pt.diogo.glowbar.overlay

import android.accessibilityservice.AccessibilityService
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import pt.diogo.glowbar.SettingsRepository

/**
 * Modo alternativo, sem qualquer notificação.
 *
 * Um serviço de acessibilidade pode criar janelas TYPE_ACCESSIBILITY_OVERLAY
 * sem a permissão SYSTEM_ALERT_WINDOW — e é essa permissão que faz o Android
 * mostrar o aviso "está a sobrepor-se a outras apps". Como também não é preciso
 * um foreground service, desaparecem as duas notificações.
 *
 * Bónus: o próprio serviço reporta a app em primeiro plano, pelo que deixa de
 * ser necessária a permissão de acesso ao histórico de utilização.
 */
class GlowAccessibilityService : AccessibilityService() {

    private var controller: GlowController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val c = GlowController(
            context = this,
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            onStopRequested = { stopOverlay() }
        )
        c.foregroundFromAccessibility = true
        controller = c
        if (SettingsRepository(this).load().enabled) c.start()
    }

    /** Chamado pela MainActivity quando as definições mudam. */
    fun refresh() {
        val c = controller ?: return
        if (SettingsRepository(this).load().enabled) {
            if (GlowController.active !== c) c.start() else c.rebuild()
        } else {
            stopOverlay()
        }
    }

    private fun stopOverlay() {
        controller?.stop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        GlowController.active?.onForegroundApp(pkg)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        stopOverlay()
        controller = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: GlowAccessibilityService? = null
    }
}
