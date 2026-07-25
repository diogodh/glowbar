package pt.diogo.glowbar

import android.content.Context
import android.content.SharedPreferences

enum class Shape { BAR, CUTOUT }
enum class Position { TOP, BOTTOM, LEFT, RIGHT }
enum class AnimMode { NONE, FLOW, PULSE, BREATHE }
enum class AnimSpeed { SLOW, MEDIUM, FAST }

data class GlowSettings(
    val enabled: Boolean = false,
    val shape: Shape = Shape.BAR,

    // Barra
    val position: Position = Position.TOP,
    val widthDp: Int = 120,
    val thicknessDp: Int = 8,
    val offsetAlongDp: Int = 0,
    val offsetFromEdgeDp: Int = 0,

    // Cutout
    val strokeDp: Int = 4,
    val hScale: Float = 1f,
    val vScale: Float = 1f,
    val cutoutOffsetXDp: Int = 0,
    val cutoutOffsetYDp: Int = 0,

    // Animação
    val animMode: AnimMode = AnimMode.FLOW,
    val animSpeed: AnimSpeed = AnimSpeed.MEDIUM,

    // Funcionalidades
    val batteryIndicator: Boolean = false,
    val notificationGlow: Boolean = false,
    val notificationProgress: Boolean = false,
    val autoStart: Boolean = false,
    val autoHideFullscreen: Boolean = true,

    // Avisos persistentes das apps privadas
    val pendingUntilOpened: Boolean = true,
    val tapToOpen: Boolean = true,
    val pendingTimeoutSec: Int = 0,  // 0 = nunca expira

    // Acordar o ecrã quando chega aviso de uma app privada
    val wakeScreen: Boolean = false,
    val wakeScreenSec: Int = 5,

    // Aparência em repouso
    val idleDim: Boolean = false,
    val idleAlphaPct: Int = 15,      // 0-100

    // Modo sem notificações (serviço de acessibilidade)
    val accessibilityMode: Boolean = false
)

/**
 * Persistência em SharedPreferences.
 * As chaves geométricas incluem o smallestScreenWidthDp, para que um foldable
 * guarde configurações diferentes com o ecrã aberto e fechado.
 */
class SettingsRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("glowbar", Context.MODE_PRIVATE)

    private val sw: Int
        get() = context.resources.configuration.smallestScreenWidthDp

    private fun g(name: String) = name + "_sw" + sw

    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T =
        runCatching {
            enumValueOf<T>(prefs.getString(key, default.name) ?: default.name)
        }.getOrDefault(default)

    /**
     * Versões anteriores guardavam o tempo em minutos. Se ainda não existir a
     * chave em segundos, converte o valor antigo para não perder a definição.
     */
    private fun readTimeoutSec(): Int {
        if (prefs.contains("pendingTimeoutSec")) {
            return prefs.getInt("pendingTimeoutSec", 0).coerceIn(0, 7200)
        }
        val legacyMin = prefs.getInt("pendingTimeoutMin", 0).coerceIn(0, 120)
        return legacyMin * 60
    }

    fun load(): GlowSettings = GlowSettings(
        enabled = prefs.getBoolean("enabled", false),
        shape = enumValue("shape", Shape.BAR),
        position = enumValue(g("position"), Position.TOP),
        widthDp = prefs.getInt(g("widthDp"), 120).coerceIn(40, 400),
        thicknessDp = prefs.getInt(g("thicknessDp"), 8).coerceIn(2, 24),
        offsetAlongDp = prefs.getInt(g("offsetAlongDp"), 0).coerceIn(-1000, 1000),
        offsetFromEdgeDp = prefs.getInt(g("offsetFromEdgeDp"), 0).coerceIn(-100, 1000),
        strokeDp = prefs.getInt(g("strokeDp"), 4).coerceIn(2, 12),
        hScale = prefs.getFloat(g("hScale"), 1f).coerceIn(0.5f, 2f),
        vScale = prefs.getFloat(g("vScale"), 1f).coerceIn(0.5f, 2f),
        cutoutOffsetXDp = prefs.getInt(g("cutoutOffsetXDp"), 0).coerceIn(-1000, 1000),
        cutoutOffsetYDp = prefs.getInt(g("cutoutOffsetYDp"), 0).coerceIn(-1000, 1000),
        animMode = enumValue("animMode", AnimMode.FLOW),
        animSpeed = enumValue("animSpeed", AnimSpeed.MEDIUM),
        batteryIndicator = prefs.getBoolean("batteryIndicator", false),
        notificationGlow = prefs.getBoolean("notificationGlow", false),
        notificationProgress = prefs.getBoolean("notificationProgress", false),
        autoStart = prefs.getBoolean("autoStart", false),
        autoHideFullscreen = prefs.getBoolean("autoHideFullscreen", true),
        pendingUntilOpened = prefs.getBoolean("pendingUntilOpened", true),
        tapToOpen = prefs.getBoolean("tapToOpen", true),
        pendingTimeoutSec = readTimeoutSec(),
        wakeScreen = prefs.getBoolean("wakeScreen", false),
        wakeScreenSec = prefs.getInt("wakeScreenSec", 5).coerceIn(2, 30),
        idleDim = prefs.getBoolean("idleDim", false),
        idleAlphaPct = prefs.getInt("idleAlphaPct", 15).coerceIn(0, 60),
        accessibilityMode = prefs.getBoolean("accessibilityMode", false)
    )

    fun save(s: GlowSettings) {
        prefs.edit().apply {
            putBoolean("enabled", s.enabled)
            putString("shape", s.shape.name)
            putString(g("position"), s.position.name)
            putInt(g("widthDp"), s.widthDp)
            putInt(g("thicknessDp"), s.thicknessDp)
            putInt(g("offsetAlongDp"), s.offsetAlongDp)
            putInt(g("offsetFromEdgeDp"), s.offsetFromEdgeDp)
            putInt(g("strokeDp"), s.strokeDp)
            putFloat(g("hScale"), s.hScale)
            putFloat(g("vScale"), s.vScale)
            putInt(g("cutoutOffsetXDp"), s.cutoutOffsetXDp)
            putInt(g("cutoutOffsetYDp"), s.cutoutOffsetYDp)
            putString("animMode", s.animMode.name)
            putString("animSpeed", s.animSpeed.name)
            putBoolean("batteryIndicator", s.batteryIndicator)
            putBoolean("notificationGlow", s.notificationGlow)
            putBoolean("notificationProgress", s.notificationProgress)
            putBoolean("autoStart", s.autoStart)
            putBoolean("autoHideFullscreen", s.autoHideFullscreen)
            putBoolean("pendingUntilOpened", s.pendingUntilOpened)
            putBoolean("tapToOpen", s.tapToOpen)
            putInt("pendingTimeoutSec", s.pendingTimeoutSec)
            putBoolean("wakeScreen", s.wakeScreen)
            putInt("wakeScreenSec", s.wakeScreenSec)
            putBoolean("idleDim", s.idleDim)
            putInt("idleAlphaPct", s.idleAlphaPct)
            putBoolean("accessibilityMode", s.accessibilityMode)
        }.apply()
    }

    fun setEnabled(v: Boolean) {
        prefs.edit().putBoolean("enabled", v).apply()
    }
}
