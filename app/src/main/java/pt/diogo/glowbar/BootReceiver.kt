package pt.diogo.glowbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import pt.diogo.glowbar.overlay.GlowOverlayService

/** Repõe a Glowbar após reiniciar o telefone, se estava ligada e a opção está ativa. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val repo = SettingsRepository(context)
        val settings = repo.load()

        // "Arrancar após reiniciar" desligado significa começar efetivamente
        // desligada, inclusive no modo de acessibilidade.
        if (!settings.autoStart) {
            repo.setEnabled(false)
            return
        }

        if (!settings.enabled || settings.accessibilityMode) return
        if (!Settings.canDrawOverlays(context)) {
            repo.setEnabled(false)
            return
        }

        val serviceIntent = Intent(context, GlowOverlayService::class.java)
            .setAction(GlowOverlayService.ACTION_START)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
