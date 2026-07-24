package pt.diogo.glowbar.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import pt.diogo.glowbar.MainActivity
import pt.diogo.glowbar.R
import pt.diogo.glowbar.SettingsRepository

/**
 * Modo clássico: foreground service + TYPE_APPLICATION_OVERLAY.
 * Tem sempre duas notificações associadas — a deste serviço e o aviso do
 * sistema sobre sobreposição. Para as eliminar, usa o modo de acessibilidade.
 */
class GlowOverlayService : Service() {

    companion object {
        const val ACTION_START = "pt.diogo.glowbar.START"
        const val ACTION_REFRESH = "pt.diogo.glowbar.REFRESH"
        const val ACTION_HIDE = "pt.diogo.glowbar.HIDE"
        const val ACTION_SHOW = "pt.diogo.glowbar.SHOW"
        const val ACTION_TURN_OFF = "pt.diogo.glowbar.TURN_OFF"
        /** Para o serviço sem alterar as definições (usado ao mudar de modo). */
        const val ACTION_STOP_ONLY = "pt.diogo.glowbar.STOP_ONLY"

        const val CHANNEL_ID = "glowbar"
        const val NOTIF_ID = 1
    }

    private var controller: GlowController? = null

    override fun onCreate() {
        super.onCreate()
        controller = GlowController(
            context = this,
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            onStopRequested = { stopSelf() }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = controller ?: return START_NOT_STICKY
        when (intent?.action) {
            ACTION_HIDE -> {
                startFg()
                if (GlowController.active !== c) c.start()
                c.hideTemporarily(true)
                startFg() // atualiza texto e ação para “Mostrar”
            }
            ACTION_SHOW -> {
                startFg()
                if (GlowController.active !== c) c.start()
                c.hideTemporarily(false)
                startFg() // atualiza texto e ação para “Esconder”
            }
            ACTION_TURN_OFF -> {
                SettingsRepository(this).setEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_ONLY -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startFg()
                if (GlowController.active !== c) c.start() else c.rebuild()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.rebuild()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller?.stop()
        controller = null
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Glowbar", NotificationManager.IMPORTANCE_LOW)
        )

        fun servicePi(action: String, req: Int): PendingIntent =
            PendingIntent.getService(
                this, req,
                Intent(this, GlowOverlayService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val hidden = controller?.isHiddenTemporarily() == true
        val toggleAction = if (hidden)
            Notification.Action.Builder(null as Icon?, "Mostrar", servicePi(ACTION_SHOW, 1)).build()
        else
            Notification.Action.Builder(null as Icon?, "Esconder", servicePi(ACTION_HIDE, 1)).build()

        val offAction =
            Notification.Action.Builder(null as Icon?, "Desligar", servicePi(ACTION_TURN_OFF, 2)).build()

        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_glow_notification)
            .setContentTitle("Glowbar ativa")
            .setContentText(
                if (hidden) "Temporariamente escondida"
                else "A brilhar por cima das tuas aplicações"
            )
            .setOngoing(true)
            .setContentIntent(contentPi)
            .addAction(toggleAction)
            .addAction(offAction)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
