package io.github.sacha37420.coffre.core

import org.json.JSONException
import org.json.JSONObject

/** Où l'application range le cache local : enveloppes chiffrées et suivi (jamais de clair). */
interface EntryFiles {
    fun load(): JSONObject?
    fun save(state: JSONObject)
}

/**
 * Cache local et synchronisation — même algorithme que src/lib/sync.js (docs/FORMAT.md §3-4).
 * État : { "entrees": { id: enveloppe }, "suivi": { id: { "seen": date|null, "dirty": bool } } }.
 */
class EntryStore(private val files: EntryFiles) {
    companion object {
        const val DIR = "coffre/v1"
        const val ENTRIES_PREFIX = "$DIR/entrees/"
        const val DESCRIPTOR_PATH = "$DIR/coffre.json"
        private val PATH_RE = Regex("^coffre/v1/entrees/([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\\.bin$")
        fun entryPath(id: String) = "$ENTRIES_PREFIX$id.bin"
    }

    class Report(val received: Int, val sent: Int, val restored: Int, val ignored: Int)

    private class State(val envelopes: JSONObject, val track: JSONObject)

    @Synchronized
    private fun load(): State {
        val j = files.load() ?: JSONObject()
        return State(j.optJSONObject("entrees") ?: JSONObject(), j.optJSONObject("suivi") ?: JSONObject())
    }

    @Synchronized
    private fun save(s: State) = files.save(JSONObject().put("entrees", s.envelopes).put("suivi", s.track))

    private fun track(seen: String?, dirty: Boolean) = JSONObject().put("seen", seen ?: JSONObject.NULL).put("dirty", dirty)
    private fun JSONObject.seen(): String? = if (isNull("seen")) null else optString("seen")

    /** Chiffre et range une entrée ; elle partira à la prochaine synchronisation. */
    @Synchronized
    fun put(vault: CoffreCrypto.VaultKey, entry: JSONObject) {
        val s = load()
        val id = entry.getString("id")
        s.envelopes.put(id, CoffreCrypto.encryptEntry(vault, entry))
        s.track.put(id, track(s.track.optJSONObject(id)?.seen(), true))
        save(s)
    }

    /** Entrées lisibles (pierres tombales comprises) et nombre d'illisibles (jamais effacées). */
    @Synchronized
    fun decryptAll(vault: CoffreCrypto.VaultKey): Pair<List<JSONObject>, Int> {
        val s = load()
        val out = mutableListOf<JSONObject>()
        var unreadable = 0
        for (id in s.envelopes.keys()) {
            try { out.add(CoffreCrypto.decryptEntry(vault, s.envelopes.getJSONObject(id))) } catch (e: Exception) { unreadable++ }
        }
        return out to unreadable
    }

    /** Tout est à renvoyer (autre lab). */
    @Synchronized
    fun markAllDirty() {
        val s = load()
        for (id in s.envelopes.keys()) s.track.put(id, track(null, true))
        save(s)
    }

    @Synchronized
    fun clear() = files.save(JSONObject())

    private fun readEnvelope(text: String, vault: CoffreCrypto.VaultKey, id: String): Pair<JSONObject, JSONObject> {
        val env = JSONObject(text)
        if (env.optString("id") != id) throw CoffreException("entree_alteree", "Entrée illisible ou altérée.")
        return env to CoffreCrypto.decryptEntry(vault, env)
    }

    /** Un passage de synchronisation. */
    @Synchronized
    fun synchronize(remote: RemoteFiles, ns: String, vault: CoffreCrypto.VaultKey, descriptor: JSONObject): Report {
        var received = 0; var sent = 0; var restored = 0; var ignored = 0

        val remoteDesc = remote.read(ns, DESCRIPTOR_PATH)
        if (remoteDesc == null) {
            remote.write(ns, DESCRIPTOR_PATH, descriptor.toString())
        } else {
            val d = try { JSONObject(remoteDesc) } catch (e: JSONException) { null }
            if (d == null || d.optString("format") != CoffreCrypto.VAULT_FORMAT) {
                throw CoffreException("serveur", "Le dossier coffre/ du lab contient un fichier inattendu.")
            }
            if (d.optString("key_id") != vault.keyId) {
                throw CoffreException("autre_cle", "Le coffre enregistré sur le lab a été créé avec un autre fichier de clé.")
            }
        }

        val remoteMap = linkedMapOf<String, String?>()
        for (f in remote.list(ns, ENTRIES_PREFIX)) PATH_RE.find(f.path)?.let { remoteMap[it.groupValues[1]] = f.updated }

        val s = load()
        try {
            // 1. Ce qui a changé sur le serveur.
            for ((id, rUpdated) in remoteMap) {
                val t = s.track.optJSONObject(id)
                if (t != null && t.seen() == rUpdated && rUpdated != null) continue
                val text = remote.read(ns, entryPath(id)) ?: continue
                val (env, entry) = try { readEnvelope(text, vault, id) } catch (e: Exception) { ignored++; continue }
                val local = s.envelopes.optJSONObject(id)?.let {
                    try { CoffreCrypto.decryptEntry(vault, it) } catch (e: Exception) { null }
                }
                if (local == null || entry.optString("updated") > local.optString("updated")) {
                    s.envelopes.put(id, env)
                    s.track.put(id, track(rUpdated, false))
                    received++
                } else {
                    val dirty = entry.optString("updated") != local.optString("updated") || (t?.optBoolean("dirty") ?: false)
                    s.track.put(id, track(rUpdated, dirty))
                }
            }

            // 2. Disparu du serveur sans pierre tombale : redéposé (jamais une enveloppe d'un autre coffre).
            for (id in s.envelopes.keys()) {
                if (s.envelopes.getJSONObject(id).optString("key_id") != vault.keyId) continue
                if (!remoteMap.containsKey(id) && s.track.optJSONObject(id)?.optBoolean("dirty") != true) {
                    s.track.put(id, track(null, true))
                    restored++
                }
            }
            save(s)

            // 3. Envoi des entrées nouvelles ou modifiées.
            for (id in s.track.keys().asSequence().toList()) {
                val t = s.track.getJSONObject(id)
                val env = s.envelopes.optJSONObject(id) ?: continue
                if (!t.optBoolean("dirty") || env.optString("key_id") != vault.keyId) continue
                val updated = remote.write(ns, entryPath(id), env.toString())
                s.track.put(id, track(updated, false))
                sent++
            }
        } finally {
            save(s)
        }
        return Report(received, sent, restored, ignored)
    }
}
