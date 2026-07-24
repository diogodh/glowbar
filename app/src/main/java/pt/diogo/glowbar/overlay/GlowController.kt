package pt.diogo.glowbar.overlay

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import pt.diogo.glowbar.GlowSettings
import pt.diogo.glowbar.Position
import pt.diogo.glowbar.SettingsRepository
import pt.diogo.glowbar.Shape

/**
 * Toda a lógica do overlay, independente de quem o hospeda.
 *
 * É usado por dois donos possíveis:
 *  - GlowOverlayService (foreground service + TYPE_APPLICATION_OVERLAY)
 *  - GlowAccessibilityService (TYPE_ACCESSIBILITY_OVERLAY, sem notificações)
 */
class GlowController(
    private val context: Context,
    private val windowType: Int,
    private val onStopRequested: () -> Unit
) {

    companion object {
        /** Controlador ativo, seja qual for o serviço que o hospeda. */
        @Volatile
        var active: GlowController? = null
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val repo = SettingsRepository(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var glowView: GlowView? = null
    var settings: GlowSettings? = null
        private set

    /** Quando o dono é o serviço de acessibilidade, ele próprio reporta a app em 1.º plano. */
    var foregroundFromAccessibility = false

    private var hiddenTemp = false
    private var hiddenFullscreen = false

    private val progressMap = LinkedHashMap<String, Float>()
    private val pending = LinkedHashMap<String, Int>()
    private var pollScheduled = false

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

    // ------------------------------------------------------------ ciclo de vida

    fun start() {
        active = this
        runCatching {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        rebuild()
    }

    fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
        glowView?.let { runCatching { wm.removeView(it) } }
        glowView = null
        runCatching { context.unregisterReceiver(batteryReceiver) }
        if (active === this) active = null
    }

    fun hideTemporarily(hidden: Boolean) {
        hiddenTemp = hidden
        updateVisibility()
    }

    fun isHiddenTemporarily() = hiddenTemp

    fun rebuild() {
        val s = repo.load()
        settings = s

        // Com TYPE_APPLICATION_OVERLAY é preciso a permissão; com acessibilidade não.
        val needsOverlayPermission = windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        if (!s.enabled || (needsOverlayPermission && !Settings.canDrawOverlays(context))) {
            onStopRequested()
            return
        }

        glowView?.let { runCatching { wm.removeView(it) } }

        val v = GlowView(context)
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

        v.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && pending.isNotEmpty()) {
                openMostRecentPending()
            }
            true
        }

        runCatching { wm.addView(v, buildParams(s)) }
        pushPending()
        updateVisibility()

        runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()?.let { batteryReceiver.onReceive(context, it) }
    }

    // ------------------------------------------------------------------ janela

    private fun dpToPx(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun buildParams(s: GlowSettings): WindowManager.LayoutParams {
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
                windowType, flags, PixelFormat.TRANSLUCENT
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
            lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                dpToPx(240),
                windowType, flags, PixelFormat.TRANSLUCENT
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

    // -------------------------------------------------------------- pendências

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

    fun onPrivateNotification(color: Int?, pkg: String) {
        mainHandler.post {
            val s = settings ?: return@post
            glowView?.flash(color)
            if (!s.pendingUntilOpened) return@post

            pending[pkg] = color ?: 0xFFFFFFFF.toInt()
            pushPending()
            if (!foregroundFromAccessibility) startPolling()

            if (s.pendingTimeoutMin > 0) {
                mainHandler.postDelayed({ clearPending(pkg) }, s.pendingTimeoutMin * 60_000L)
            }
        }
    }

    private fun pushPending() {
        glowView?.pendingColors = pending.values.toList()
        updateTouchable()
    }

    fun clearPending(pkg: String) {
        mainHandler.post {
            if (pending.remove(pkg) != null) pushPending()
        }
    }

    /** Chamado pelo serviço de acessibilidade sempre que muda a app em primeiro plano. */
    fun onForegroundApp(pkg: String) {
        mainHandler.post {
            if (pending.remove(pkg) != null) pushPending()
        }
    }

    // ------------------------------- deteção da app em 1.º plano (modo clássico)

    private fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    private fun foregroundPackage(): String? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10_000L, now)
        val ev = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = ev.packageName
        }
        return last
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollScheduled = false
            if (pending.isEmpty()) return
            if (hasUsageAccess()) {
                foregroundPackage()?.let { fg ->
                    if (pending.remove(fg) != null) pushPending()
                }
            }
            if (pending.isNotEmpty()) startPolling()
        }
    }

    private fun startPolling() {
        if (pollScheduled || pending.isEmpty()) return
        pollScheduled = true
        mainHandler.postDelayed(pollRunnable, 1500L)
    }

    // -------------------------------------------------------- toque para abrir

    private fun updateTouchable() {
        val v = glowView ?: return
        val s = settings ?: return
        val wantTouch = s.tapToOpen && pending.isNotEmpty()
        val lp = (v.layoutParams as? WindowManager.LayoutParams) ?: return
        val isTouchable = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
        if (wantTouch == isTouchable) return

        lp.flags = if (wantTouch) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun openMostRecentPending() {
        val pkg = pending.keys.lastOrNull() ?: return
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(it) }
        }
        pending.remove(pkg)
        pushPending()
    }
}
