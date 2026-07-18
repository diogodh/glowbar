package pt.diogo.glowbar.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import pt.diogo.glowbar.GlowSettings
import pt.diogo.glowbar.MainActivity
import pt.diogo.glowbar.Position
import pt.diogo.glowbar.R
import pt.diogo.glowbar.SettingsRepository
import pt.diogo.glowbar.Shape

class GlowOverlayService : Service() {

    companion object {
        @Volatile
        var instance: GlowOverlayService? = null

        const val ACTION_START = "pt.diogo.glowbar.START"
        const val ACTION_REFRESH = "pt.diogo.glowbar.REFRESH"
        const val ACTION_HIDE = "pt.diogo.glowbar.HIDE"
        const val ACTION_SHOW = "pt.diogo.glowbar.SHOW"
        const val ACTION_TURN_OFF = "pt.diogo.glowbar.TURN_OFF"

        const val CHANNEL_ID = "glowbar"
        const val NOTIF_ID = 1
    }

    private lateinit var wm: WindowManager
    private lateinit var repo: SettingsRepository
    private val mainHandler = Handler(Looper.getMainLooper())

    private var glowView: GlowView? = null
    private var settings: GlowSettings? = null

    // Máquina de estados de visibilidade
    private var hiddenTemp = false
    private var hiddenFullscreen = false

    // Progresso por notificação (a mais recente ganha)
    private val progressMap = LinkedHashMap<String, Float>()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            glowView?.batteryLevel = if (level >= 0 && scale > 0) level.toFloat() / scale else 1f
            glowView?.charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        repo = SettingsRepository(this)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                hiddenTemp = true
                updateVisibility()
                startFg()
            }
            ACTION_SHOW -> {
                hiddenTemp = false
                updateVisibility()
                startFg()
            }
            ACTION_TURN_OFF -> {
                repo.setEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> { // ACTION_START / ACTION_REFRESH / restart do sistema
                startFg()
                rebuildOverlay()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotação ou foldable aberto/fechado: recarrega as definições desse ecrã
        rebuildOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        glowView?.let { runCatching { wm.removeView(it) } }
        glowView = null
        runCatching { unregisterReceiver(batteryReceiver) }
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------- overlay

    private fun rebuildOverlay() {
        val s = repo.load()
        settings = s

        if (!s.enabled || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        glowView?.let { runCatching { wm.removeView(it) } }

        val v = GlowView(this)
        v.applySettings(s)
        glowView = v

        v.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 28) {
                val r = insets.displayCutout?.boundingRects?.firstOrNull()
                v.cutoutRect = r?.let { RectF(it) }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val fullscreen = !insets.isVisible(WindowInsets.Type.statusBars())
                val hide = fullscreen && (settings?.autoHideFullscreen == true)
                if (hide != hiddenFullscreen) {
                    hiddenFullscreen = hide
                    updateVisibility()
                }
            }
            insets
        }

        wm.addView(v, buildParams(s))
        updateVisibility()

        // Estado inicial da bateria (broadcast sticky)
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
            batteryReceiver.onReceive(this, it)
        }
    }

    private fun dpToPx(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildParams(s: GlowSettings): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val pad = dpToPx(GlowView.PAD_DP)

        val lp: WindowManager.LayoutParams
        if (s.shape == Shape.BAR) {
            val len = dpToPx(s.widthDp) + pad * 2
            val cross = (dpToPx(s.thicknessDp) * 2.5f).toInt() + pad * 2
            val horizontal = s.position == Position.TOP || s.position == Position.BOTTOM
            lp = WindowManager.LayoutParams(
                if (horizontal) len else cross,
                if (horizontal) cross else len,
                type, flags, PixelFormat.TRANSLUCENT
            )
            lp.gravity = when (s.position) {
                Position.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                Position.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                Position.LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                Position.RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
            }
            if (horizontal) {
                lp.x = dpToPx(s.offsetAlongDp)
                lp.y = dpToPx(s.offsetFromEdgeDp) - pad
            } else {
                lp.y = dpToPx(s.offsetAlongDp)
                lp.x = dpToPx(s.offsetFromEdgeDp) - pad
            }
        } else {
            // Cutout: janela a toda a largura no topo, para receber a posição do punch-hole
            lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                dpToPx(240),
                type, flags, PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP
        }

        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    private fun updateVisibility() {
        glowView?.visibility =
            if (hiddenTemp || hiddenFullscreen) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------- eventos de notificações

    fun onNotificationEvent(key: String, progress: Float?) {
        mainHandler.post {
            val s = settings ?: return@post
            if (progress != null) {
                if (!s.notificationProgress) return@post
                if (progress >= 1f) progressMap.remove(key) else progressMap[key] = progress
                glowView?.progress = progressMap.values.lastOrNull()
            } else {
                if (!s.notificationGlow) return@post
                glowView?.flash()
            }
        }
    }

    fun onNotificationGone(key: String) {
        mainHandler.post {
            progressMap.remove(key)
            glowView?.progress = progressMap.values.lastOrNull()
        }
    }

    // ------------------------------------------------------- notificação FG

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

        val toggleAction = if (hiddenTemp)
            Notification.Action.Builder(null as Icon?, "Mostrar", servicePi(ACTION_SHOW, 1)).build()
        else
            Notification.Action.Builder(null as Icon?, "Esconder", servicePi(ACTION_HIDE, 1)).build()

        val offAction =
            Notification.Action.Builder(null as Icon?, "Desligar", servicePi(ACTION_TURN_OFF, 2)).build()

        val notif = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_glow_notification)
            .setContentTitle("Glowbar ativa")
            .setContentText(
                if (hiddenTemp) "Temporariamente escondida"
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
