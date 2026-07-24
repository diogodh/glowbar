package pt.diogo.glowbar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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

    data class PendingAlertRecord(
        val pkg: String,
        val receivedAt: Long,
        val color: Int?
    )

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
        private const val KEY_PENDING = "pending_alerts_v2"
        private val lock = Any()
    }

    // ------------------------------------------------------------ apps privadas

    fun privatePackages(): Set<String> =
        prefs.getStringSet("private_pkgs", emptySet())?.toSet() ?: emptySet()

    fun isPrivate(pkg: String): Boolean = privatePackages().contains(pkg)

    fun setPrivate(pkg: String, enabled: Boolean) {
        synchronized(lock) {
            val set = privatePackages().toMutableSet()
            if (enabled) {
                set.add(pkg)
            } else {
                set.remove(pkg)
            }
            prefs.edit().putStringSet("private_pkgs", set).apply()
            if (!enabled) removePending(pkg)
        }
    }

    /** Devolve null quando a app deve usar o gradiente normal. */
    fun colorFor(pkg: String): Int? {
        val c = prefs.getInt("color_$pkg", 0)
        return if (c == 0) null else c
    }

    fun setColor(pkg: String, color: Int) {
        prefs.edit().putInt("color_$pkg", color).apply()
        updatePendingColor(pkg, if (color == 0) null else color)
    }

    // ----------------------------------------------------- registo sem conteúdo

    /** Guarda apenas pacote + timestamp. Nenhum conteúdo de notificação é lido. */
    fun logEvent(pkg: String) {
        synchronized(lock) {
            val raw = prefs.getString("events", "") ?: ""
            val entries = raw.split(';').filter { it.isNotBlank() }.toMutableList()
            entries.add(0, pkg + "," + System.currentTimeMillis())
            while (entries.size > MAX_EVENTS) entries.removeAt(entries.size - 1)
            prefs.edit().putString("events", entries.joinToString(";")).apply()
        }
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

    // ----------------------------------------- avisos pendentes (sem conteúdo)

    /**
     * Persiste os avisos que já tiveram a notificação do sistema suprimida.
     * Isto evita perder o único sinal visível se o serviço/processo for recriado.
     */
    fun pendingAlerts(): List<PendingAlertRecord> = synchronized(lock) {
        parsePending(prefs.getString(KEY_PENDING, null))
    }

    fun upsertPending(pkg: String, receivedAt: Long, color: Int?) {
        synchronized(lock) {
            val records = parsePending(prefs.getString(KEY_PENDING, null))
                .filterNot { it.pkg == pkg }
                .toMutableList()
            records.add(PendingAlertRecord(pkg, receivedAt, color))
            writePending(records)
        }
    }

    fun updatePendingColor(pkg: String, color: Int?) {
        synchronized(lock) {
            val records = parsePending(prefs.getString(KEY_PENDING, null))
            if (records.none { it.pkg == pkg }) return
            writePending(records.map { if (it.pkg == pkg) it.copy(color = color) else it })
        }
    }

    fun removePending(pkg: String) {
        synchronized(lock) {
            val records = parsePending(prefs.getString(KEY_PENDING, null))
            val updated = records.filterNot { it.pkg == pkg }
            if (updated.size != records.size) writePending(updated)
        }
    }

    fun clearPending() {
        prefs.edit().remove(KEY_PENDING).commit()
    }

    private fun parsePending(raw: String?): List<PendingAlertRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val pkg = item.optString("pkg")
                    val receivedAt = item.optLong("receivedAt", -1L)
                    if (pkg.isBlank() || receivedAt <= 0L) continue
                    val color = if (item.isNull("color")) null else item.optInt("color")
                    add(PendingAlertRecord(pkg, receivedAt, color))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writePending(records: List<PendingAlertRecord>) {
        if (records.isEmpty()) {
            prefs.edit().remove(KEY_PENDING).commit()
            return
        }

        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject().apply {
                    put("pkg", record.pkg)
                    put("receivedAt", record.receivedAt)
                    if (record.color == null) put("color", JSONObject.NULL)
                    else put("color", record.color)
                }
            )
        }
        prefs.edit().putString(KEY_PENDING, array.toString()).commit()
    }
}
