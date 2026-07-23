package pt.diogo.glowbar

import android.content.Context

/**
 * Regras por aplicação.
 *
 * Uma app marcada como "privada" tem a notificação removida do sistema logo
 * após chegar; o utilizador só recebe o sinal luminoso da Glowbar.
 *
 * Do evento guarda-se apenas o nome do pacote e a hora — nunca o título,
 * o texto ou qualquer conteúdo da notificação.
 */
class AppRulesRepository(context: Context) {

    private val prefs = context.getSharedPreferences("glowbar_rules", Context.MODE_PRIVATE)

    companion object {
        /** Paleta de cores para o flash. O primeiro valor (0) significa "gradiente normal". */
        val PALETTE = listOf(
            0 to "Gradiente",
            0xFF34A853.toInt() to "Verde",
            0xFF4285F4.toInt() to "Azul",
            0xFFEA4335.toInt() to "Vermelho",
            0xFFFBBC05.toInt() to "Amarelo",
            0xFFAB47BC.toInt() to "Roxo",
            0xFFFFFFFF.toInt() to "Branco"
        )
        private const val MAX_EVENTS = 30
    }

    // ------------------------------------------------------------ apps privadas

    fun privatePackages(): Set<String> =
        prefs.getStringSet("private_pkgs", emptySet())?.toSet() ?: emptySet()

    fun isPrivate(pkg: String): Boolean = privatePackages().contains(pkg)

    fun setPrivate(pkg: String, enabled: Boolean) {
        val set = privatePackages().toMutableSet()
        if (enabled) set.add(pkg) else set.remove(pkg)
        prefs.edit().putStringSet("private_pkgs", set).apply()
    }

    /** Devolve null quando a app deve usar o gradiente normal. */
    fun colorFor(pkg: String): Int? {
        val c = prefs.getInt("color_$pkg", 0)
        return if (c == 0) null else c
    }

    fun setColor(pkg: String, color: Int) {
        prefs.edit().putInt("color_$pkg", color).apply()
    }

    // ----------------------------------------------------- registo sem conteúdo

    /** Guarda apenas pacote + timestamp. Nenhum conteúdo de notificação é lido. */
    fun logEvent(pkg: String) {
        val raw = prefs.getString("events", "") ?: ""
        val entries = raw.split(';').filter { it.isNotBlank() }.toMutableList()
        entries.add(0, pkg + "," + System.currentTimeMillis())
        while (entries.size > MAX_EVENTS) entries.removeAt(entries.size - 1)
        prefs.edit().putString("events", entries.joinToString(";")).apply()
    }

    fun recentEvents(): List<Pair<String, Long>> {
        val raw = prefs.getString("events", "") ?: ""
        return raw.split(';')
            .filter { it.isNotBlank() }
            .mapNotNull {
                val parts = it.split(',')
                val ts = parts.getOrNull(1)?.toLongOrNull()
                if (parts.size == 2 && ts != null) parts[0] to ts else null
            }
    }

    fun clearEvents() {
        prefs.edit().remove("events").apply()
    }
}
