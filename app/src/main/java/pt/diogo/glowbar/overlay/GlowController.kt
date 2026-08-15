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
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.Surface
import androidx.core.content.ContextCompat
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
    private var touchView: View? = null
    private var detectedCutoutRect: RectF? = null

    /**
     * Geometria do punch-hole em coordenadas normalizadas da orientação natural
     * (portrait). Em landscape, o Android pode devolver um boundingRect de
     * letterboxing em vez do pequeno cutout físico; nesse caso reutilizamos a
     * geometria física e apenas rodamos a sua posição.
     */
    private var cachedCutoutWidthPx: Float? = null
    private var cachedCutoutCenterXNorm: Float? = null
    private var cachedCutoutCenterYNorm: Float? = null

    @Volatile
    private var screenOn = true

    @Volatile
    private var currentForegroundPackage: String? = null

    private var wakeLock: PowerManager.WakeLock? = null

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

    /**
     * Contagem para expirar um aviso. Só corre com o ecrã ligado: com o
     * telefone pousado, um aviso curto expiraria sem o utilizador ter tido
     * qualquer hipótese de o ver — e a notificação original já foi suprimida.
     *
     * `startedAt` é o instante (elapsedRealtime) em que a contagem retomou;
     * 0 significa pausada.
     */
    private data class TimeoutState(
        val receivedAt: Long,
        var remainingMs: Long,
        var startedAt: Long = 0L
    )

    private val timeoutStates = mutableMapOf<String, TimeoutState>()
    private var pendingLoaded = false
    private var pollScheduled = false
    private var lastUsageQueryAt = 0L

    /**
     * Com o ecrã apagado nada do overlay é visível, por isso não faz sentido
     * manter o animador a desenhar. Isto elimina o consumo de CPU durante a
     * maior parte do dia, que é quando o telefone está pousado.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    glowView?.setScreenOn(true)
                    resumePendingTimeouts()
                    updateTouchOverlay()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    glowView?.setScreenOn(false)
                    pausePendingTimeouts()
                    updateTouchOverlay()
                }
            }
        }
    }

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
                ContextCompat.registerReceiver(
                    context,
                    batteryReceiver,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                ContextCompat.registerReceiver(
                    context,
                    screenReceiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered = true
            }.onFailure { Log.w(TAG, "Não foi possível registar os eventos do sistema", it) }
        }

        rebuild()
    }

    fun stop() {
        mainHandler.removeCallbacksAndMessages(null)
        pendingTimeouts.clear()
        timeoutStates.clear()
        pollScheduled = false

        removeTouchOverlay()

        glowView?.let { view ->
            runCatching { wm.removeView(view) }
                .onFailure { Log.w(TAG, "Falha ao remover o overlay", it) }
        }
        glowView = null
        detectedCutoutRect = null
        cachedCutoutWidthPx = null
        cachedCutoutCenterXNorm = null
        cachedCutoutCenterYNorm = null
        currentForegroundPackage = null
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null

        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(screenReceiver) }
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

    /**
     * Aceita apenas retângulos compatíveis com um punch-hole físico. Os limites
     * são deliberadamente largos: a dimensão deve ser pequena relativamente ao
     * ecrã, mas não assumimos um diâmetro fixo para todos os equipamentos.
     */
    private fun plausibleCutout(rect: RectF): Boolean {
        val density = context.resources.displayMetrics.density
        val wDp = rect.width() / density
        val hDp = rect.height() / density
        return wDp in 8f..90f && hDp in 8f..90f
    }

    /**
     * Converte a posição do punch-hole guardada em portrait para a orientação
     * atual. A largura/altura atuais são as da superfície já rodada.
     */
    private fun cachedCutoutCenterForCurrentRotation(): Pair<Float, Float>? {
        val cx = cachedCutoutCenterXNorm ?: return null
        val cy = cachedCutoutCenterYNorm ?: return null
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val rotation = wm.defaultDisplay.rotation

        val (nx, ny) = when (rotation) {
            Surface.ROTATION_0 -> cx to cy
            Surface.ROTATION_90 -> (1f - cy) to cx
            Surface.ROTATION_180 -> (1f - cx) to (1f - cy)
            Surface.ROTATION_270 -> cy to (1f - cx)
            else -> cx to cy
        }
        return nx * w to ny * h
    }

    private fun updateCutoutGeometry(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT < 28) return

        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val rotation = wm.defaultDisplay.rotation

        val isLandscape =
            rotation == Surface.ROTATION_90 ||
            rotation == Surface.ROTATION_270

        if (!isLandscape) {
            /*
            * Só em portrait aceitamos a boundingRect fornecida pelo Android.
            *
            * Em landscape, alguns dispositivos/versões do Android podem
            * representar o display cutout através de uma área de letterboxing
            * em vez do pequeno punch-hole físico. Por isso nunca usamos uma
            * boundingRect de landscape para determinar o tamanho do anel.
            */
            val candidate = insets.displayCutout
                ?.boundingRects
                ?.firstOrNull()
                ?.let { RectF(it) }

            if (candidate != null && plausibleCutout(candidate)) {
                val currentX = candidate.centerX() / w
                val currentY = candidate.centerY() / h

                /*
                * Guardamos a posição na orientação natural (portrait), para
                * podermos reconstruí-la corretamente depois da rotação.
                */
                val (naturalX, naturalY) = when (rotation) {
                    Surface.ROTATION_0 ->
                        currentX to currentY

                    Surface.ROTATION_180 ->
                        (1f - currentX) to (1f - currentY)

                    else ->
                        currentX to currentY
                }

                /*
                * O tamanho físico do punch-hole fica congelado aqui.
                * Nunca será substituído por uma boundingRect de landscape.
                */
                cachedCutoutWidthPx = maxOf(
                    candidate.width(),
                    candidate.height()
                )

                cachedCutoutCenterXNorm = naturalX
                cachedCutoutCenterYNorm = naturalY

                detectedCutoutRect = candidate
            }
        }

        /*
        * Landscape:
        *
        * Não usamos insets.displayCutout.boundingRects.
        * Reutilizamos exatamente o tamanho físico aprendido em portrait
        * e apenas transformamos a posição.
        */
        if (isLandscape && cachedCutoutWidthPx != null) {
            val center = cachedCutoutCenterForCurrentRotation()

            if (center != null) {
                val half = cachedCutoutWidthPx!! / 2f

                detectedCutoutRect = RectF(
                    center.first - half,
                    center.second - half,
                    center.first + half,
                    center.second + half
                )
            }
        }

        glowView?.cutoutRect = detectedCutoutRect
        updateTouchOverlay()
    }
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
            updateCutoutGeometry(insets)
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


        // Mantém a vista antiga até sabermos que a nova foi adicionada com sucesso.
        // Assim uma alteração inválida não deixa a app a suprimir notificações sem glow.
        glowView = newView
        // Uma vista nova tem de nascer a saber se o ecrã está ligado.
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm != null) {
                screenOn = pm.isInteractive
                newView.setScreenOn(screenOn)
            }
        }

        removeTouchOverlay()
        val added = runCatching {
            wm.addView(newView, buildVisualParams(newSettings))
        }.onFailure {
            Log.e(TAG, "Não foi possível adicionar o overlay", it)
        }.isSuccess

        if (!added) {
            glowView = oldView
            settings = oldSettings
            if (oldView == null) {
                if (active === this) active = null
                onStopRequested()
            } else {
                updateTouchOverlay()
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

    private fun buildVisualParams(s: GlowSettings): WindowManager.LayoutParams {
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
            // Dimensiona a janela para incluir também deslocamentos verticais
            // positivos e escalas grandes, evitando cortar o anel aos 240 dp.
            val estimatedRadiusDp = 36f * maxOf(s.hScale, s.vScale) +
                s.strokeDp + GlowView.PAD_DP
            val requiredHeightDp = maxOf(
                240,
                (80f + s.cutoutOffsetYDp.coerceAtLeast(0) + estimatedRadiusDp).toInt()
            )
            val rotation = wm.defaultDisplay.rotation
            val landscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
            lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                if (landscape) WindowManager.LayoutParams.MATCH_PARENT else dpToPx(requiredHeightDp),
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
        updateTouchOverlay()
    }

    /**
     * Só permite suprimir uma notificação privada quando existe uma forma
     * fiável de o utilizador ver o aviso. Com avisos persistentes, o estado é
     * gravado antes do cancelamento e pode reaparecer quando o overlay voltar a
     * ficar visível. Sem persistência, exige ecrã ligado e glow visível.
     */
    fun canSafelySuppressPrivateNotification(): Boolean {
        val s = settings ?: return false
        val view = glowView ?: return false
        if (active !== this || !s.enabled) return false
        if (s.pendingUntilOpened) return true
        return screenOn && !hiddenTemp && !hiddenFullscreen && view.visibility == View.VISIBLE
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
        wakeScreenIfNeeded(s)
        if (!s.pendingUntilOpened) return

        // Se a própria app já está em primeiro plano, não cria um aviso que
        // nunca receberia um novo evento de abertura para ser limpo.
        if (isPackageCurrentlyForeground(pkg)) return

        val now = System.currentTimeMillis()
        pendingTimeouts.remove(pkg)?.let { mainHandler.removeCallbacks(it) }
        timeoutStates.remove(pkg)
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

    /**
     * Acorda o ecrã por alguns segundos, para o sinal luminoso ser visível com
     * o telefone pousado. Não faz nada se o ecrã já estiver ligado.
     *
     * Nota honesta: se o overlay fica ou não visível por cima do ecrã de
     * bloqueio depende do fabricante — nalguns aparece, noutros o keyguard
     * tapa-o. Não há forma de o garantir a partir da app.
     */
    @Suppress("DEPRECATION")
    private fun wakeScreenIfNeeded(s: GlowSettings) {
        if (!s.wakeScreen) return
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        if (pm.isInteractive) return

        runCatching {
            val lock = wakeLock ?: pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "glowbar:private-alert"
            ).apply {
                setReferenceCounted(false)
                wakeLock = this
            }
            if (lock.isHeld) lock.release()
            // acquire(timeout) liberta-se sozinho, evitando ficar preso.
            lock.acquire(s.wakeScreenSec.coerceIn(2, 30) * 1000L)
        }.onFailure { Log.w(TAG, "Não foi possível acordar o ecrã", it) }
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

        if (s.pendingTimeoutSec <= 0) {
            timeoutStates.remove(pkg)
            return
        }

        val alert = pending[pkg] ?: run {
            timeoutStates.remove(pkg)
            return
        }

        val total = s.pendingTimeoutSec * 1000L
        val existing = timeoutStates[pkg]
        // Uma notificação nova da mesma app (receivedAt diferente) reinicia a
        // contagem; caso contrário retoma-se o que faltava.
        val remaining = if (existing != null && existing.receivedAt == alert.receivedAt) {
            existing.remainingMs.coerceAtMost(total)
        } else {
            total
        }

        val state = TimeoutState(alert.receivedAt, remaining)
        timeoutStates[pkg] = state

        // Ecrã apagado: fica em pausa e só retoma quando o ecrã ligar.
        if (!screenOn) return

        if (remaining <= 0L) {
            removePendingInternal(pkg)
            return
        }

        state.startedAt = SystemClock.elapsedRealtime()
        val expectedReceivedAt = alert.receivedAt
        val runnable = Runnable {
            pendingTimeouts.remove(pkg)
            timeoutStates.remove(pkg)
            if (pending[pkg]?.receivedAt == expectedReceivedAt) {
                removePendingInternal(pkg)
            }
        }
        pendingTimeouts[pkg] = runnable
        mainHandler.postDelayed(runnable, remaining)
    }

    /** Congela o que falta a cada aviso e cancela os agendamentos. */
    private fun pausePendingTimeouts() {
        val now = SystemClock.elapsedRealtime()
        timeoutStates.forEach { (pkg, state) ->
            if (state.startedAt > 0L) {
                val elapsed = now - state.startedAt
                state.remainingMs = (state.remainingMs - elapsed).coerceAtLeast(0L)
                state.startedAt = 0L
            }
            pendingTimeouts.remove(pkg)?.let { mainHandler.removeCallbacks(it) }
        }
    }

    /** Retoma as contagens quando o ecrã volta a ligar. */
    private fun resumePendingTimeouts() {
        val s = settings ?: return
        pending.keys.toList().forEach { schedulePendingTimeout(it, s) }
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
        timeoutStates.remove(pkg)
        pendingTimeouts.remove(pkg)?.let { mainHandler.removeCallbacks(it) }
        rulesRepo.removePending(pkg)
        if (push) pushPending()
        return true
    }

    private fun clearAllPendingInternal() {
        pendingTimeouts.values.forEach { mainHandler.removeCallbacks(it) }
        pendingTimeouts.clear()
        timeoutStates.clear()
        pending.clear()
        rulesRepo.clearPending()
        pushPending()
    }

    /** Chamado pelo serviço de acessibilidade quando muda a app em primeiro plano. */
    fun onForegroundApp(pkg: String) {
        mainHandler.post {
            currentForegroundPackage = pkg
            val alert = pending[pkg] ?: return@post
            if (System.currentTimeMillis() >= alert.receivedAt) {
                removePendingInternal(pkg)
            }
        }
    }

    // ------------------------------- deteção da app em 1.º plano (modo clássico)

    private fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun isPackageCurrentlyForeground(pkg: String): Boolean {
        if (currentForegroundPackage == pkg) return true
        if (foregroundFromAccessibility || !hasUsageAccess()) return false

        val now = System.currentTimeMillis()
        val latest = foregroundEvents(max(0L, now - 6 * 60 * 60 * 1000L), now)
            .maxByOrNull { it.timestamp }
            ?.pkg
        currentForegroundPackage = latest
        return latest == pkg
    }

    private fun foregroundEvents(from: Long, to: Long): List<ForegroundEvent> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val events = usm.queryEvents(from, to)
        val event = UsageEvents.Event()
        return buildList {
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val movedToForeground =
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
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
                    currentForegroundPackage = event.pkg
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
        updateTouchOverlay()
    }

    private fun updateTouchOverlay() {
        val s = settings
        val shouldShow = s != null && s.tapToOpen && pending.isNotEmpty() &&
            screenOn && !hiddenTemp && !hiddenFullscreen &&
            glowView?.visibility == View.VISIBLE && active === this

        if (!shouldShow || s == null) {
            removeTouchOverlay()
            return
        }

        val existing = touchView
        if (existing == null) {
            val view = View(context).apply {
                isClickable = true
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP && pending.isNotEmpty()) {
                        openMostRecentPending()
                    }
                    true
                }
            }
            val added = runCatching {
                wm.addView(view, buildTouchParams(s))
            }.onFailure {
                Log.w(TAG, "Não foi possível criar a área de toque", it)
            }.isSuccess
            if (added) touchView = view
        } else {
            runCatching { wm.updateViewLayout(existing, buildTouchParams(s)) }
                .onFailure { Log.w(TAG, "Não foi possível atualizar a área de toque", it) }
        }
    }

    private fun removeTouchOverlay() {
        val view = touchView ?: return
        touchView = null
        runCatching { wm.removeView(view) }
            .onFailure { Log.w(TAG, "Não foi possível remover a área de toque", it) }
    }

    /**
     * A janela visual é sempre intocável. Esta segunda janela transparente é
     * pequena e existe apenas enquanto há avisos pendentes, evitando bloquear
     * toda a faixa superior no modo cutout.
     */
    private fun buildTouchParams(s: GlowSettings): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val pad = dpToPx(12)

        if (s.shape == Shape.BAR) {
            val horizontal = s.position == Position.TOP || s.position == Position.BOTTOM
            val len = dpToPx(s.widthDp) + pad * 2
            val cross = dpToPx(maxOf(s.thicknessDp + 20, 36))
            return WindowManager.LayoutParams(
                if (horizontal) len else cross,
                if (horizontal) cross else len,
                windowType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = when (s.position) {
                    Position.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    Position.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    Position.LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                    Position.RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
                }
                if (horizontal) {
                    x = dpToPx(s.offsetAlongDp)
                    y = dpToPx(s.offsetFromEdgeDp)
                } else {
                    y = dpToPx(s.offsetAlongDp)
                    x = dpToPx(s.offsetFromEdgeDp)
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        val metrics = context.resources.displayMetrics
        val base = detectedCutoutRect ?: RectF(
            metrics.widthPixels / 2f - dpToPx(22),
            dpToPx(18).toFloat(),
            metrics.widthPixels / 2f + dpToPx(22),
            dpToPx(62).toFloat()
        )
        val stroke = dpToPx(s.strokeDp).toFloat()
        val baseRadius = base.width() / 2f + stroke / 2f + dpToPx(10)
        val halfWidth = baseRadius * s.hScale + pad
        val halfHeight = baseRadius * s.vScale + pad
        val width = (halfWidth * 2f).toInt().coerceAtLeast(dpToPx(48))
        val height = (halfHeight * 2f).toInt().coerceAtLeast(dpToPx(48))
        val centerX = base.centerX() + dpToPx(s.cutoutOffsetXDp)
        val centerY = base.centerY() + dpToPx(s.cutoutOffsetYDp)

        return WindowManager.LayoutParams(
            width,
            height,
            windowType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (centerX - width / 2f).toInt()
            y = (centerY - height / 2f).toInt()
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun openMostRecentPending() {
        val pkg = pending.keys.lastOrNull() ?: return
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(launchIntent) }.isSuccess
        if (opened) removePendingInternal(pkg)
    }
}
