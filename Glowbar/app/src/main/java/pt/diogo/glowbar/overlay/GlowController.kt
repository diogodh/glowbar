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
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import pt.diogo.glowbar.AppRulesRepository
import pt.diogo.glowbar.GlowSettings
import pt.diogo.glowbar.Position
import pt.diogo.glowbar.SettingsRepository
import pt.diogo.glowbar.Shape
import kotlin.math.max

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

    private data class PendingAlert(
        val color: Int?,
        val receivedAt: Long
    )

    private data class ForegroundEvent(
        val pkg: String,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "GlowController"
        private const val USAGE_POLL_MS = 1500L
        private const val USAGE_QUERY_OVERLAP_MS = 1000L

        /** Controlador ativo, seja qual for o serviço que o hospeda. */
        @Volatile
        var active: GlowController? = null
            private set
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val repo = SettingsRepository(context)
    private val rulesRepo = AppRulesRepository(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var glowView: GlowView? = null
    var settings: GlowSettings? = null
        private set

    /** Quando o dono é o serviço de acessibilidade, ele próprio reporta a app em 1.º plano. */
    var foregroundFromAccessibility = false

    private var hiddenTemp = false
    private var hiddenFullscreen = false
    private var receiverRegistered = false

    private val progressMap = LinkedHashMap<String, Float>()
    private val pending = LinkedHashMap<String, PendingAlert>()
    private val pendingTimeouts = mutableMapOf<String, Runnable>()
    private var pendingLoaded = false
    private var pollScheduled = false
    private var lastUsageQueryAt = 0L

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
        val previous = active
        if (previous !== null && previous !== this) previous.stop()

        if (!receiverRegistered) {
            runCatching {
                context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                receiverRegistered = true
            }.onFailure { Log.w(TAG, "Não foi possível registar o estado da bateria", it) }
        }

        rebuild()
    }

    fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
        pendingTimeouts.clear()
        pollScheduled = false

        glowView?.let { view ->
            runCatching { wm.removeView(view) }
                .onFailure { Log.w(TAG, "Falha ao remover o overlay", it) }
        }
        glowView = null

        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(batteryReceiver) }
            receiverRegistered = false
        }

        if (active === this) active = null
    }

    fun hideTemporarily(hidden: Boolean) {
        hiddenTemp = hidden
        updateVisibility()
    }

    fun isHiddenTemporarily() = hiddenTemp

    fun rebuild() {
        val newSettings = repo.load()
        val oldSettings = settings
        settings = newSettings

        val needsOverlayPermission =
            windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        if (!newSettings.enabled ||
            (needsOverlayPermission && !Settings.canDrawOverlays(context))
        ) {
            if (active === this) active = null
            onStopRequested()
            return
        }

        syncPendingWithSettings(newSettings)

        val oldView = glowView
        val newView = GlowView(context).apply {
            applySettings(newSettings)
            progress = progressMap.values.lastOrNull()
        }

        newView.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 28) {
                val r = insets.displayCutout?.boundingRects?.firstOrNull()
                newView.cutoutRect = r?.let { RectF(it) }
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

        newView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && pending.isNotEmpty()) {
                openMostRecentPending()
            }
            true
        }

        // Mantém a vista antiga até sabermos que a nova foi adicionada com sucesso.
        // Assim uma alteração inválida não deixa a app a suprimir notificações sem glow.
        glowView = newView
        val added = runCatching {
            wm.addView(newView, buildParams(newSettings))
        }.onFailure {
            Log.e(TAG, "Não foi possível adicionar o overlay", it)
        }.isSuccess

        if (!added) {
            glowView = oldView
            settings = oldSettings
            if (oldView == null) {
                if (active === this) active = null
                onStopRequested()
            }
            return
        }

        oldView?.let { view ->
            runCatching { wm.removeView(view) }
                .onFailure { Log.w(TAG, "Falha ao substituir a vista antiga", it) }
        }

        active = this
        pushPending()
        updateVisibility()
        refreshBatteryState()

        if (pending.isNotEmpty() && !foregroundFromAccessibility) startPolling()
    }

    private fun refreshBatteryState() {
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
                windowType,
                flags,
                PixelFormat.TRANSLUCENT
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
                windowType,
                flags,
                PixelFormat.TRANSLUCENT
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
                progressMap.remove(key)
                if (progress < 1f) progressMap[key] = progress
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

    /** Pré-visualização manual, sem criar nem suprimir uma notificação. */
    fun previewNotification(color: Int?) {
        mainHandler.post { glowView?.flash(color) }
    }

    fun onPrivateNotification(color: Int?, pkg: String) {
        // NotificationListenerService entrega normalmente este callback na main
        // thread. Nesse caso persiste o aviso antes de a notificação ser cancelada,
        // reduzindo a janela em que uma recriação do processo faria perder o único
        // sinal visível. Se algum OEM chamar noutra thread, volta à main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handlePrivateNotification(color, pkg)
        } else {
            mainHandler.post { handlePrivateNotification(color, pkg) }
        }
    }

    private fun handlePrivateNotification(color: Int?, pkg: String) {
        val s = settings ?: return
        glowView?.flash(color)
        if (!s.pendingUntilOpened) return

        val now = System.currentTimeMillis()
        pendingTimeouts.remove(pkg)?.let { mainHandler.removeCallbacks(it) }
        pending.remove(pkg)
        pending[pkg] = PendingAlert(color = color, receivedAt = now)
        rulesRepo.upsertPending(pkg, now, color)

        if (lastUsageQueryAt == 0L) {
            lastUsageQueryAt = max(0L, now - USAGE_QUERY_OVERLAP_MS)
        }

        schedulePendingTimeout(pkg, s)
        pushPending()
        if (!foregroundFromAccessibility) startPolling()
    }

    fun updatePendingColor(pkg: String, color: Int?) {
        mainHandler.post {
            val current = pending[pkg] ?: return@post
            pending[pkg] = current.copy(color = color)
            rulesRepo.updatePendingColor(pkg, color)
            pushPending()
        }
    }

    private fun syncPendingWithSettings(s: GlowSettings) {
        if (!pendingLoaded) {
            val privatePackages = rulesRepo.privatePackages()
            rulesRepo.pendingAlerts()
                .sortedBy { it.receivedAt }
                .forEach { record ->
                    val stillValid = record.pkg in privatePackages &&
                        context.packageManager.getLaunchIntentForPackage(record.pkg) != null
                    if (stillValid) {
                        pending[record.pkg] = PendingAlert(record.color, record.receivedAt)
                    } else {
                        rulesRepo.removePending(record.pkg)
                    }
                }
            pendingLoaded = true
            pending.values.minOfOrNull { it.receivedAt }?.let {
                lastUsageQueryAt = max(0L, it - USAGE_QUERY_OVERLAP_MS)
            }
        }

        if (!s.pendingUntilOpened) {
            clearAllPendingInternal()
            return
        }

        pending.keys.toList().forEach { schedulePendingTimeout(it, s) }
    }

    private fun schedulePendingTimeout(pkg: String, s: GlowSettings) {
        pendingTimeouts.remove(pkg)?.let { mainHandler.removeCallbacks(it) }
        if (s.pendingTimeoutMin <= 0) return

        val alert = pending[pkg] ?: return
        val expiresAt = alert.receivedAt + s.pendingTimeoutMin * 60_000L
        val delay = expiresAt - System.currentTimeMillis()
        if (delay <= 0L) {
            removePendingInternal(pkg)
            return
        }

        val expectedReceivedAt = alert.receivedAt
        val runnable = Runnable {
            pendingTimeouts.remove(pkg)
            if (pending[pkg]?.receivedAt == expectedReceivedAt) {
                removePendingInternal(pkg)
            }
        }
        pendingTimeouts[pkg] = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun pushPending() {
        glowView?.pendingColors = pending.values.map { it.color }
        updateTouchable()
    }

    fun clearPending(pkg: String) {
        mainHandler.post { removePendingInternal(pkg) }
    }

    private fun removePendingInternal(pkg: String, push: Boolean = true): Boolean {
        if (!pending.containsKey(pkg)) return false
        pending.remove(pkg)
        pendingTimeouts.remove(pkg)?.let { mainHandler.removeCallbacks(it) }
        rulesRepo.removePending(pkg)
        if (push) pushPending()
        return true
    }

    private fun clearAllPendingInternal() {
        pendingTimeouts.values.forEach { mainHandler.removeCallbacks(it) }
        pendingTimeouts.clear()
        pending.clear()
        rulesRepo.clearPending()
        pushPending()
    }

    /** Chamado pelo serviço de acessibilidade quando muda a app em primeiro plano. */
    fun onForegroundApp(pkg: String) {
        mainHandler.post {
            val alert = pending[pkg] ?: return@post
            if (System.currentTimeMillis() >= alert.receivedAt) {
                removePendingInternal(pkg)
            }
        }
    }

    // ------------------------------- deteção da app em 1.º plano (modo clássico)

    private fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun foregroundEvents(from: Long, to: Long): List<ForegroundEvent> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val events = usm.queryEvents(from, to)
        val event = UsageEvents.Event()
        return buildList {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val movedToForeground =
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                val pkg = event.packageName
                if (movedToForeground && !pkg.isNullOrBlank()) {
                    add(ForegroundEvent(pkg, event.timeStamp))
                }
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollScheduled = false
            if (pending.isEmpty()) return

            if (hasUsageAccess()) {
                val now = System.currentTimeMillis()
                val oldestPending = pending.values.minOfOrNull { it.receivedAt } ?: now
                val from = max(
                    0L,
                    minOf(lastUsageQueryAt.takeIf { it > 0L } ?: oldestPending, oldestPending) -
                        USAGE_QUERY_OVERLAP_MS
                )

                foregroundEvents(from, now).forEach { event ->
                    val alert = pending[event.pkg]
                    // Só limpa se a app tiver passado para primeiro plano DEPOIS
                    // desta notificação; eventos antigos não podem apagar avisos novos.
                    if (alert != null && event.timestamp >= alert.receivedAt) {
                        removePendingInternal(event.pkg, push = false)
                    }
                }
                lastUsageQueryAt = now
                pushPending()
            }

            if (pending.isNotEmpty()) startPolling()
        }
    }

    private fun startPolling() {
        if (pollScheduled || pending.isEmpty()) return
        pollScheduled = true
        mainHandler.postDelayed(pollRunnable, USAGE_POLL_MS)
    }

    // -------------------------------------------------------- toque para abrir

    private fun updateTouchable() {
        val view = glowView ?: return
        val s = settings ?: return
        val wantTouch = s.tapToOpen && pending.isNotEmpty()
        val lp = (view.layoutParams as? WindowManager.LayoutParams) ?: return
        val isTouchable = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
        if (wantTouch == isTouchable) return

        lp.flags = if (wantTouch) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.updateViewLayout(view, lp) }
            .onFailure { Log.w(TAG, "Não foi possível atualizar o toque do overlay", it) }
    }

    private fun openMostRecentPending() {
        val pkg = pending.keys.lastOrNull() ?: return
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(launchIntent) }.isSuccess
        if (opened) removePendingInternal(pkg)
    }
}
