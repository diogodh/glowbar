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
    val pendingTimeoutMin: Int = 0   // 0 = nunca expira
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

    fun load(): GlowSettings = GlowSettings(
        enabled = prefs.getBoolean("enabled", false),
        shape = Shape.valueOf(prefs.getString("shape", Shape.BAR.name)!!),
        position = Position.valueOf(prefs.getString(g("position"), Position.TOP.name)!!),
        widthDp = prefs.getInt(g("widthDp"), 120),
        thicknessDp = prefs.getInt(g("thicknessDp"), 8),
        offsetAlongDp = prefs.getInt(g("offsetAlongDp"), 0),
        offsetFromEdgeDp = prefs.getInt(g("offsetFromEdgeDp"), 0),
        strokeDp = prefs.getInt(g("strokeDp"), 4),
        hScale = prefs.getFloat(g("hScale"), 1f),
        vScale = prefs.getFloat(g("vScale"), 1f),
        cutoutOffsetXDp = prefs.getInt(g("cutoutOffsetXDp"), 0),
        cutoutOffsetYDp = prefs.getInt(g("cutoutOffsetYDp"), 0),
        animMode = AnimMode.valueOf(prefs.getString("animMode", AnimMode.FLOW.name)!!),
        animSpeed = AnimSpeed.valueOf(prefs.getString("animSpeed", AnimSpeed.MEDIUM.name)!!),
        batteryIndicator = prefs.getBoolean("batteryIndicator", false),
        notificationGlow = prefs.getBoolean("notificationGlow", false),
        notificationProgress = prefs.getBoolean("notificationProgress", false),
        autoStart = prefs.getBoolean("autoStart", false),
        autoHideFullscreen = prefs.getBoolean("autoHideFullscreen", true),
        pendingUntilOpened = prefs.getBoolean("pendingUntilOpened", true),
        tapToOpen = prefs.getBoolean("tapToOpen", true),
        pendingTimeoutMin = prefs.getInt("pendingTimeoutMin", 0)
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
            putInt("pendingTimeoutMin", s.pendingTimeoutMin)
        }.apply()
    }

    fun setEnabled(v: Boolean) {
        prefs.edit().putBoolean("enabled", v).apply()
    }
}
