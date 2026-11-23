package io.github.sacha37420.coffre.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

/** Modèle d'entrée (docs/FORMAT.md §3) — mêmes règles que src/lib/entries.js et src/lib/sites.js. */
object Entries {
    /** « exemple.fr/login » ou « https://exemple.fr/x » → origine exacte. */
    fun normalizeOrigin(input: String): String {
        var text = input.trim()
        if (text.isEmpty()) throw CoffreException("format", "Adresse de site vide.")
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)) text = "https://$text"
        val u = try { URI(text) } catch (e: Exception) { throw CoffreException("format", "Adresse de site invalide.") }
        val scheme = u.scheme?.lowercase()
        val host = u.host?.lowercase()
        if ((scheme != "https" && scheme != "http") || host.isNullOrEmpty()) throw CoffreException("format", "Adresse de site invalide.")
        val default = if (scheme == "https") 443 else 80
        return if (u.port == -1 || u.port == default) "$scheme://$host" else "$scheme://$host:${u.port}"
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString().let {
        // Même forme que JavaScript (toISOString) : millisecondes sur trois chiffres.
        if (it.length == 20) it.dropLast(1) + ".000Z" else it
    }

    private fun nextStamp(prev: String, now: Long): String {
        val t = iso(now)
        return if (t > prev) t else iso(Instant.parse(prev).toEpochMilli() + 1)
    }

    private fun clean(name: String, origins: List<String>, username: String, password: String, notes: String): JSONObject {
        val o = origins.map { normalizeOrigin(it) }.distinct()
        val n = name.trim().ifEmpty { o.firstOrNull()?.let { URI(it).host } ?: "" }
        if (n.isEmpty()) throw CoffreException("format", "Donnez un nom ou une adresse de site.")
        return JSONObject().put("name", n.take(200)).put("origins", JSONArray(o)).put("username", username.take(500))
            .put("password", password.take(2000)).put("notes", notes.take(10_000))
    }

    fun newEntry(name: String, origins: List<String>, username: String, password: String, notes: String = "", now: Long = System.currentTimeMillis()): JSONObject {
        val t = iso(now)
        val c = clean(name, origins, username, password, notes)
        return JSONObject().put("id", UUID.randomUUID().toString()).put("type", "login").put("name", c.getString("name"))
            .put("origins", c.getJSONArray("origins")).put("username", c.getString("username")).put("password", c.getString("password"))
            .put("notes", c.getString("notes")).put("created", t).put("updated", t).put("deleted", false)
    }

    fun withPassword(entry: JSONObject, password: String, now: Long = System.currentTimeMillis()): JSONObject {
        if (entry.optBoolean("deleted")) throw CoffreException("format", "Cette entrée a été supprimée.")
        return JSONObject(entry.toString()).put("password", password.take(2000)).put("updated", nextStamp(entry.getString("updated"), now))
    }

    fun origins(e: JSONObject): List<String> = e.optJSONArray("origins")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

    /** Entrées vivantes de l'origine exacte, triées par nom puis identifiant. */
    fun forOrigin(entries: List<JSONObject>, origin: String): List<JSONObject> = entries
        .filter { !it.optBoolean("deleted") && origin in origins(it) }
        .sortedWith(compareBy({ it.optString("name").lowercase() }, { it.optString("username").lowercase() }))

    /** Après une saisie : null (déjà enregistré tel quel), ou nouveau / mise à jour d'une entrée existante. */
    class Proposal(val kind: String, val entryId: String?)

    fun proposalFor(entries: List<JSONObject>, origin: String, username: String, password: String): Proposal? {
        val same = forOrigin(entries, origin)
        if (same.any { it.optString("username") == username && it.optString("password") == password }) return null
        val existing = same.firstOrNull { it.optString("username") == username }
        return if (existing != null) Proposal("maj", existing.getString("id")) else Proposal("nouveau", null)
    }

    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    /** Recherche sans accents ni casse, plusieurs mots ; pierres tombales masquées. */
    fun search(entries: List<JSONObject>, query: String): List<JSONObject> {
        val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return entries.filter { !it.optBoolean("deleted") }.filter { e ->
            val hay = fold((listOf(e.optString("name"), e.optString("username")) + origins(e)).joinToString(" "))
            words.all { hay.contains(it) }
        }.sortedWith(compareBy({ fold(it.optString("name")) }, { it.optString("id") }))
    }
}
