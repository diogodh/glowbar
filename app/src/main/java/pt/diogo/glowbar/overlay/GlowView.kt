package pt.diogo.glowbar.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import pt.diogo.glowbar.AnimMode
import pt.diogo.glowbar.AnimSpeed
import pt.diogo.glowbar.GlowSettings
import pt.diogo.glowbar.Position
import pt.diogo.glowbar.SettingsRepository
import pt.diogo.glowbar.Shape
import kotlin.math.PI
import kotlin.math.sin

/**
 * View que desenha a Glowbar (barra ou anel do cutout) com:
 *  - gradiente azul -> verde -> amarelo -> vermelho
 *  - halo difuso (BlurMaskFilter)
 *  - animações Flow / Pulse / Breathe
 *  - indicador de bateria (vermelho a avançar; verde a carregar)
 *  - modo progresso (notificações com progress)
 *  - flash em novas notificações
 */
class GlowView(context: Context) : View(context) {

    var settings: GlowSettings = SettingsRepository(context).load()
        private set

    var batteryLevel: Float = 1f
        set(v) { field = v; invalidate() }
    var charging: Boolean = false
        set(v) { field = v; invalidate() }
    var progress: Float? = null
        set(v) { field = v; invalidate() }
    var cutoutRect: RectF? = null
        set(v) { field = v; invalidate() }

    /** Cores das apps com aviso por resolver. Vazio = sem pendências. */
    var pendingColors: List<Int?> = emptyList()
        set(v) { field = v; invalidate() }

    private var flashStart = 0L
    private var flashColor: Int? = null
    private var phase = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val extraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaderMatrix = Matrix()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        duration = 4000L
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    companion object {
        // Azul, verde, amarelo, vermelho e de novo azul para o loop ser contínuo
        val COLORS = intArrayOf(
            0xFF4285F4.toInt(),
            0xFF34A853.toInt(),
            0xFFFBBC05.toInt(),
            0xFFEA4335.toInt(),
            0xFF4285F4.toInt()
        )
        val GREEN = 0xFF34A853.toInt()
        val RED = 0xFFEA4335.toInt()
        const val PAD_DP = 20
        const val FLASH_MS = 650L
    }

    init {
        // Necessário para o BlurMaskFilter funcionar
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun applySettings(s: GlowSettings) {
        settings = s
        animator.duration = when (s.animSpeed) {
            AnimSpeed.SLOW -> 8000L
            AnimSpeed.MEDIUM -> 4000L
            AnimSpeed.FAST -> 2000L
        }
        invalidate()
    }

    /** color = null usa o gradiente normal; apps privadas podem ter cor própria. */
    fun flash(color: Int? = null) {
        flashColor = color
        flashStart = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int) = v * resources.displayMetrics.density
    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun wave() = (sin(phase * 2.0 * PI).toFloat() + 1f) / 2f

    /** Em repouso (sem pendências nem progresso) a barra pode ficar quase apagada. */
    private fun idleDimmed(): Boolean =
        settings.idleDim && pendingColors.isEmpty() && progress == null

    private fun idleAlpha(): Int =
        (255 * (settings.idleAlphaPct.coerceIn(0, 100) / 100f)).toInt()

    /** Respiração desfasada, para os segmentos não pulsarem todos em uníssono. */
    private fun waveAt(offset: Float) =
        (sin((phase + offset) * 2.0 * PI).toFloat() + 1f) / 2f

    private fun flashLevel(): Float {
        if (flashStart == 0L) return 0f
        val elapsed = SystemClock.uptimeMillis() - flashStart
        return if (elapsed >= FLASH_MS) 0f else 1f - elapsed.toFloat() / FLASH_MS
    }

    override fun onDraw(canvas: Canvas) {
        if (settings.shape == Shape.BAR) drawBar(canvas) else drawRing(canvas)
    }

    // ------------------------------------------------------------------ BARRA

    private fun drawBar(canvas: Canvas) {
        val s = settings
        val horizontal = s.position == Position.TOP || s.position == Position.BOTTOM
        val len = dp(s.widthDp)
        var thick = dp(s.thicknessDp)
        var alpha = 255

        when (s.animMode) {
            AnimMode.PULSE -> alpha = (255 * (0.25f + 0.75f * wave())).toInt()
            AnimMode.BREATHE -> thick *= 0.55f + 0.9f * wave()
            else -> {}
        }

        val pad = dp(PAD_DP)
        val maxThick = dp(s.thicknessDp) * 1.5f
        val rect: RectF = if (horizontal) {
            val cy = if (s.position == Position.TOP) pad + maxThick / 2f
                     else height - pad - maxThick / 2f
            val cx = width / 2f
            RectF(cx - len / 2f, cy - thick / 2f, cx + len / 2f, cy + thick / 2f)
        } else {
            val cx = if (s.position == Position.LEFT) pad + maxThick / 2f
                     else width - pad - maxThick / 2f
            val cy = height / 2f
            RectF(cx - thick / 2f, cy - len / 2f, cx + thick / 2f, cy + len / 2f)
        }

        val p = progress
        val batteryMode = s.batteryIndicator && p == null

        // Escolha do shader/cor base
        var solidColor: Int? = null
        var gradient: Shader? = null
        when {
            batteryMode && charging -> solidColor = GREEN
            batteryMode && batteryLevel <= 0.15f -> solidColor = RED
            else -> {
                val g = LinearGradient(
                    0f, 0f,
                    if (horizontal) len else 0f,
                    if (horizontal) 0f else len,
                    COLORS, null, Shader.TileMode.REPEAT
                )
                val shift = if (s.animMode == AnimMode.FLOW) phase * len else 0f
                shaderMatrix.reset()
                if (horizontal) shaderMatrix.setTranslate(rect.left + shift, 0f)
                else shaderMatrix.setTranslate(0f, rect.top + shift)
                g.setLocalMatrix(shaderMatrix)
                gradient = g
            }
        }

        val radius = thick / 2f

        fun preparePaint(target: Paint, blur: Float?, paintAlpha: Int) {
            target.reset()
            target.isAntiAlias = true
            target.style = Paint.Style.FILL
            if (gradient != null) target.shader = gradient else target.color = solidColor ?: RED
            if (blur != null) target.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            target.alpha = paintAlpha
        }

        fun prepareAlertPaint(
            target: Paint,
            color: Int?,
            area: RectF,
            blur: Float?,
            paintAlpha: Int
        ) {
            target.reset()
            target.isAntiAlias = true
            target.style = Paint.Style.FILL
            if (color != null) {
                target.color = color
            } else {
                val alertGradient = LinearGradient(
                    area.left,
                    area.top,
                    if (horizontal) area.right else area.left,
                    if (horizontal) area.top else area.bottom,
                    COLORS,
                    null,
                    Shader.TileMode.REPEAT
                )
                if (s.animMode == AnimMode.FLOW) {
                    shaderMatrix.reset()
                    val shift = phase * (if (horizontal) area.width() else area.height())
                    if (horizontal) shaderMatrix.setTranslate(shift, 0f)
                    else shaderMatrix.setTranslate(0f, shift)
                    alertGradient.setLocalMatrix(shaderMatrix)
                }
                target.shader = alertGradient
            }
            if (blur != null) target.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            target.alpha = paintAlpha.coerceIn(0, 255)
        }

        if (pendingColors.isNotEmpty()) {
            // ------------------- avisos por resolver -------------------
            val n = pendingColors.size
            val total = if (horizontal) rect.width() else rect.height()
            val gap = if (n > 1) minOf(dp(3f), total / (n * 3f)) else 0f
            val segLen = ((total - gap * (n - 1)) / n).coerceAtLeast(dp(1f))

            pendingColors.forEachIndexed { i, c ->
                val start = i * (segLen + gap)
                val seg = if (horizontal)
                    RectF(rect.left + start, rect.top, rect.left + start + segLen, rect.bottom)
                else
                    RectF(rect.left, rect.top + start, rect.right, rect.top + start + segLen)

                val breath = 0.35f + 0.65f * waveAt(i.toFloat() / n)
                val segAlpha = (255 * breath).toInt()

                prepareAlertPaint(extraPaint, c, seg, thick * 1.6f, segAlpha / 2)
                canvas.drawRoundRect(seg, radius, radius, extraPaint)

                prepareAlertPaint(extraPaint, c, seg, null, segAlpha)
                canvas.drawRoundRect(seg, radius, radius, extraPaint)
            }
        } else if (p != null) {
            // ------------------- modo progresso -------------------
            // Trilho esbatido
            extraPaint.reset()
            extraPaint.isAntiAlias = true
            extraPaint.color = 0x33FFFFFF
            canvas.drawRoundRect(rect, radius, radius, extraPaint)

            // Porção preenchida
            val frac = p.coerceIn(0f, 1f)
            val filled = RectF(rect)
            if (horizontal) filled.right = rect.left + rect.width() * frac
            else filled.bottom = rect.top + rect.height() * frac

            preparePaint(glowPaint, thick * 1.4f, 140)
            canvas.drawRoundRect(filled, radius, radius, glowPaint)
            preparePaint(paint, null, 255)
            canvas.drawRoundRect(filled, radius, radius, paint)
        } else if (idleDimmed()) {
            // ------------------- repouso apagado -------------------
            // Sem halo: cinzento discreto, quase transparente.
            extraPaint.reset()
            extraPaint.isAntiAlias = true
            extraPaint.color = 0xFFBDBDBD.toInt()
            extraPaint.alpha = (idleAlpha() * (alpha / 255f)).toInt()
            canvas.drawRoundRect(rect, radius, radius, extraPaint)
        } else {
            // ------------------- modo normal / bateria -------------------
            preparePaint(glowPaint, thick * 1.4f, alpha / 2)
            canvas.drawRoundRect(rect, radius, radius, glowPaint)
            preparePaint(paint, null, alpha)
            canvas.drawRoundRect(rect, radius, radius, paint)

            // Vermelho a avançar com o consumo da bateria
            if (batteryMode && !charging && batteryLevel > 0.15f && batteryLevel < 1f) {
                val drain = 1f - batteryLevel
                val redRect = RectF(rect)
                if (horizontal) redRect.left = rect.right - rect.width() * drain
                else redRect.top = rect.bottom - rect.height() * drain
                extraPaint.reset()
                extraPaint.isAntiAlias = true
                extraPaint.color = RED
                extraPaint.alpha = alpha
                canvas.drawRoundRect(redRect, radius, radius, extraPaint)
            }
        }

        // Flash de notificação: desenha halo E corpo colorido. No modo de
        // repouso transparente, isto garante que a barra ganha realmente cor.
        val f = flashLevel()
        if (f > 0f) {
            val flashRect = RectF(rect)
            flashRect.inset(-dp(2f), -dp(2f))
            prepareAlertPaint(
                extraPaint,
                flashColor,
                flashRect,
                thick * 1.2f + dp(4f),
                (210 * f).toInt()
            )
            canvas.drawRoundRect(flashRect, radius, radius, extraPaint)

            prepareAlertPaint(
                extraPaint,
                flashColor,
                rect,
                null,
                (255 * f).toInt()
            )
            canvas.drawRoundRect(rect, radius, radius, extraPaint)
        }
    }

    // ------------------------------------------------------------------ ANEL

    private fun drawRing(canvas: Canvas) {
        val s = settings

        // Se o sistema não reportar o cutout, usa um círculo por defeito ao centro/topo
        val base = cutoutRect ?: RectF(
            width / 2f - dp(22f), dp(18f),
            width / 2f + dp(22f), dp(18f) + dp(44f)
        )

        val cx = base.centerX() + dp(s.cutoutOffsetXDp)
        val cy = base.centerY() + dp(s.cutoutOffsetYDp)
        var stroke = dp(s.strokeDp)
        var alpha = 255

        when (s.animMode) {
            AnimMode.PULSE -> alpha = (255 * (0.25f + 0.75f * wave())).toInt()
            AnimMode.BREATHE -> stroke *= 0.55f + 0.9f * wave()
            else -> {}
        }

        // Usa só a largura como referência do raio base. O Android por vezes
        // reporta o boundingRect do punch-hole com largura e altura ligeiramente
        // diferentes mesmo para câmaras circulares; se rx e ry partissem de
        // width/height respetivamente, 1.00x/1.00x podia sair uma oval em vez
        // de um círculo. hScale/vScale continuam a servir para ajustar formas
        // realmente ovais (pill) ou fazer correções finas.
        val margin = stroke / 2f + dp(2f)
        val baseRadius = base.width() / 2f + margin
        val rx = baseRadius * s.hScale
        val ry = baseRadius * s.vScale
        val oval = RectF(cx - rx, cy - ry, cx + rx, cy + ry)

        val p = progress
        val batteryMode = s.batteryIndicator && p == null

        var solidColor: Int? = null
        var gradient: Shader? = null
        when {
            batteryMode && charging -> solidColor = GREEN
            batteryMode && batteryLevel <= 0.15f -> solidColor = RED
            else -> {
                val g = SweepGradient(cx, cy, COLORS, null)
                shaderMatrix.reset()
                if (s.animMode == AnimMode.FLOW) {
                    shaderMatrix.setRotate(phase * 360f, cx, cy)
                }
                g.setLocalMatrix(shaderMatrix)
                gradient = g
            }
        }

        fun prepareStroke(target: Paint, blur: Float?, paintAlpha: Int, width: Float) {
            target.reset()
            target.isAntiAlias = true
            target.style = Paint.Style.STROKE
            target.strokeWidth = width
            target.strokeCap = Paint.Cap.ROUND
            if (gradient != null) target.shader = gradient else target.color = solidColor ?: RED
            if (blur != null) target.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            target.alpha = paintAlpha
        }

        fun prepareAlertStroke(
            target: Paint,
            color: Int?,
            blur: Float?,
            paintAlpha: Int,
            width: Float
        ) {
            target.reset()
            target.isAntiAlias = true
            target.style = Paint.Style.STROKE
            target.strokeWidth = width
            target.strokeCap = Paint.Cap.ROUND
            if (color != null) {
                target.color = color
            } else {
                val alertGradient = SweepGradient(cx, cy, COLORS, null)
                if (s.animMode == AnimMode.FLOW) {
                    shaderMatrix.reset()
                    shaderMatrix.setRotate(phase * 360f, cx, cy)
                    alertGradient.setLocalMatrix(shaderMatrix)
                }
                target.shader = alertGradient
            }
            if (blur != null) target.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            target.alpha = paintAlpha.coerceIn(0, 255)
        }

        if (pendingColors.isNotEmpty()) {
            // Avisos por resolver: um arco por app, com respiração desfasada
            val n = pendingColors.size
            val gapDeg = if (n > 1) minOf(6f, 180f / n) else 0f
            val sweep = ((360f - gapDeg * n) / n).coerceAtLeast(1f)

            pendingColors.forEachIndexed { i, c ->
                val startAngle = -90f + i * (sweep + gapDeg)
                val breath = 0.35f + 0.65f * waveAt(i.toFloat() / n)
                val segAlpha = (255 * breath).toInt()

                prepareAlertStroke(
                    extraPaint,
                    c,
                    stroke * 1.8f,
                    segAlpha / 2,
                    stroke
                )
                canvas.drawArc(oval, startAngle, sweep, false, extraPaint)

                prepareAlertStroke(extraPaint, c, null, segAlpha, stroke)
                canvas.drawArc(oval, startAngle, sweep, false, extraPaint)
            }
        } else if (p != null) {
            // Trilho esbatido + arco de progresso
            extraPaint.reset()
            extraPaint.isAntiAlias = true
            extraPaint.style = Paint.Style.STROKE
            extraPaint.strokeWidth = stroke
            extraPaint.color = 0x33FFFFFF
            canvas.drawOval(oval, extraPaint)

            prepareStroke(glowPaint, stroke * 1.6f, 140, stroke)
            canvas.drawArc(oval, -90f, 360f * p.coerceIn(0f, 1f), false, glowPaint)
            prepareStroke(paint, null, 255, stroke)
            canvas.drawArc(oval, -90f, 360f * p.coerceIn(0f, 1f), false, paint)
        } else if (idleDimmed()) {
            extraPaint.reset()
            extraPaint.isAntiAlias = true
            extraPaint.style = Paint.Style.STROKE
            extraPaint.strokeWidth = stroke
            extraPaint.color = 0xFFBDBDBD.toInt()
            extraPaint.alpha = (idleAlpha() * (alpha / 255f)).toInt()
            canvas.drawOval(oval, extraPaint)
        } else {
            prepareStroke(glowPaint, stroke * 1.6f, alpha / 2, stroke)
            canvas.drawOval(oval, glowPaint)
            prepareStroke(paint, null, alpha, stroke)
            canvas.drawOval(oval, paint)

            // Arco vermelho proporcional ao consumo da bateria
            if (batteryMode && !charging && batteryLevel > 0.15f && batteryLevel < 1f) {
                val drain = 1f - batteryLevel
                extraPaint.reset()
                extraPaint.isAntiAlias = true
                extraPaint.style = Paint.Style.STROKE
                extraPaint.strokeWidth = stroke
                extraPaint.strokeCap = Paint.Cap.ROUND
                extraPaint.color = RED
                extraPaint.alpha = alpha
                canvas.drawArc(oval, -90f, 360f * drain, false, extraPaint)
            }
        }

        // Flash de notificação: halo e arco sólido/gradiente.
        val f = flashLevel()
        if (f > 0f) {
            prepareAlertStroke(
                extraPaint,
                flashColor,
                stroke * 1.4f + dp(4f),
                (210 * f).toInt(),
                stroke + dp(2f)
            )
            canvas.drawOval(oval, extraPaint)

            prepareAlertStroke(
                extraPaint,
                flashColor,
                null,
                (255 * f).toInt(),
                stroke
            )
            canvas.drawOval(oval, extraPaint)
        }
    }
}
