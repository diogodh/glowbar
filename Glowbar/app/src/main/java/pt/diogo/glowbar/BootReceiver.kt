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
        val s = SettingsRepository(context).load()
        if (s.enabled && s.autoStart && Settings.canDrawOverlays(context)) {
            val i = Intent(context, GlowOverlayService::class.java)
                .setAction(GlowOverlayService.ACTION_START)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
