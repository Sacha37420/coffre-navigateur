package io.github.sacha37420.coffre.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Mêmes cas que tests/sync.test.mjs, puis la même chose en vrai HTTP contre le faux lab de l'extension. */
class SyncTest {
    companion object {
        private val root = File(System.getProperty("coffre.racine") ?: "../..")
        private val vectors = JSONObject(File(root, "tests/vecteurs/v1.json").readText())
        val keyFile: JSONObject = vectors.getJSONObject("keyFile")
        val vault = CoffreCrypto.openKeyFile(keyFile, vectors.getString("passphrase"))
        val descriptor: JSONObject = CoffreCrypto.vaultDescriptor(keyFile)

        private var lab: Process? = null
        var labUrl: String? = null

        @BeforeClass @JvmStatic fun startLab() {
            labUrl = try {
                val p = ProcessBuilder("node", "scripts/faux-lab-serveur.cjs").directory(root).redirectErrorStream(true).start()
                lab = p
                p.inputStream.bufferedReader().readLine()?.takeIf { it.startsWith("http://127.0.0.1:") }
            } catch (e: Exception) { null }
        }

        @AfterClass @JvmStatic fun stopLab() {
            lab?.outputStream?.close()
            lab?.waitFor(10, TimeUnit.SECONDS)
            lab?.destroy()
        }
    }

    private class MemFiles : EntryFiles {
        var j: String? = null
        override fun load() = j?.let { JSONObject(it) }
        override fun save(state: JSONObject) { j = state.toString() }
    }

    private class FakeRemote : RemoteFiles {
        val files = linkedMapOf<String, Pair<String, String>>()
        var clock = 0
        var failWriteAt = -1
        var writes = 0
        override fun list(ns: String, prefix: String) = files.filterKeys { it.startsWith(prefix) }.map { RemoteFiles.Item(it.key, it.value.second) }
        override fun read(ns: String, path: String) = files[path]?.first
        override fun write(ns: String, path: String, text: String): String {
            if (++writes == failWriteAt) throw CoffreException("reseau", "x")
            val u = Instant.ofEpochSecond(1_800_000_000L + ++clock).toString()
            files[path] = text to u
            return u
        }
    }

    private fun entry(name: String, password: String = "p", updated: String = "2026-11-22T10:00:00.000Z", id: String = UUID.randomUUID().toString()) =
        JSONObject().put("id", id).put("type", "login").put("name", name).put("origins", JSONArray()).put("username", "")
            .put("password", password).put("notes", "").put("created", "2026-11-22T10:00:00.000Z").put("updated", updated).put("deleted", false)

    private fun EntryStore.all() = decryptAll(vault).first.sortedBy { it.getString("name") }

    @Test fun `une entree d'un appareil arrive sur l'autre, sans clair cote serveur`() {
        val server = FakeRemote()
        val a = EntryStore(MemFiles()); val b = EntryStore(MemFiles())
        val e = entry("Banque", "TrèsSecret")
        a.put(vault, e)
        val r = a.synchronize(server, "moi", vault, descriptor)
        assertEquals(1, r.sent)
        assertTrue(server.files.values.none { it.first.contains("TrèsSecret") || it.first.contains("Banque") })
        assertEquals(1, b.synchronize(server, "moi", vault, descriptor).received)
        assertTrue(b.all()[0].similar(e))
        val again = b.synchronize(server, "moi", vault, descriptor)
        assertEquals(0, again.received + again.sent)
    }

    @Test fun `conflit - la plus recente gagne des deux cotes`() {
        val server = FakeRemote()
        val a = EntryStore(MemFiles()); val b = EntryStore(MemFiles())
        val e = entry("Site", "v1", "2026-11-22T10:00:00.000Z")
        a.put(vault, e); a.synchronize(server, "moi", vault, descriptor); b.synchronize(server, "moi", vault, descriptor)
        a.put(vault, JSONObject(e.toString()).put("password", "vA").put("updated", "2026-11-22T11:00:00.000Z"))
        b.put(vault, JSONObject(e.toString()).put("password", "vB").put("updated", "2026-11-22T12:00:00.000Z"))
        a.synchronize(server, "moi", vault, descriptor); b.synchronize(server, "moi", vault, descriptor); a.synchronize(server, "moi", vault, descriptor)
        assertEquals("vB", a.all()[0].getString("password"))
        assertEquals("vB", b.all()[0].getString("password"))
    }

    @Test fun `disparu du serveur redepose, illisible ignore, autre coffre refuse`() {
        val server = FakeRemote()
        val a = EntryStore(MemFiles())
        val e = entry("Garde")
        a.put(vault, e); a.synchronize(server, "moi", vault, descriptor)
        server.files.remove(EntryStore.entryPath(e.getString("id")))
        assertEquals(1, a.synchronize(server, "moi", vault, descriptor).restored)
        server.files[EntryStore.entryPath(UUID.randomUUID().toString())] = "pas du json" to "x"
        assertEquals(1, a.synchronize(server, "moi", vault, descriptor).ignored)
        assertEquals(1, a.all().size)

        val (otherKf, other) = CoffreCrypto.createVault("une autre phrase bien longue")
        val c = EntryStore(MemFiles())
        c.put(other, entry("X"))
        try { c.synchronize(server, "moi", other, CoffreCrypto.vaultDescriptor(otherKf)); throw AssertionError() } catch (ex: CoffreException) {
            assertEquals("autre_cle", ex.code)
        }
    }

    @Test fun `panne pendant l'envoi - ce qui reste repart`() {
        val server = FakeRemote()
        val a = EntryStore(MemFiles())
        a.put(vault, entry("Un")); a.put(vault, entry("Deux"))
        server.failWriteAt = 2       // le descripteur passe, la première entrée échoue
        try { a.synchronize(server, "moi", vault, descriptor) } catch (e: CoffreException) { }
        server.failWriteAt = -1
        assertEquals(2, a.synchronize(server, "moi", vault, descriptor).sent)
    }

    @Test fun `lecture d'une entree deposee par l'extension (vecteur)`() {
        val server = FakeRemote()
        val env = vectors.getJSONObject("envelope")
        server.files[EntryStore.DESCRIPTOR_PATH] = descriptor.toString() to "d"
        server.files[EntryStore.entryPath(env.getString("id"))] = env.toString() to "e"
        val a = EntryStore(MemFiles())
        assertEquals(1, a.synchronize(server, "moi", vault, descriptor).received)
        assertEquals("Banque Épargne", a.all()[0].getString("name"))
    }

    // ---------- Vrai HTTP contre le faux lab de l'extension ----------

    private class MemLab : LabStorage {
        var user: String? = null; var sealed: JSONObject? = null; var pending: JSONObject? = null
        override fun readUsername() = user
        override fun writeUsername(username: String?) { user = username }
        override fun readSealedTokens() = sealed
        override fun writeSealedTokens(sealed: JSONObject?) { this.sealed = sealed }
        override fun readPending() = pending
        override fun writePending(pending: JSONObject?) { this.pending = pending }
    }

    /** Joue le passage par le navigateur : le faux Keycloak répond par une redirection vers l'adresse de retour. */
    private fun browserLogin(s: LabSession): String {
        val c = URL(s.beginLogin()).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        val location = c.getHeaderField("Location")
        assertEquals(302, c.responseCode)
        return s.completeLogin(location)
    }

    @Test fun `connexion et synchronisation en vrai HTTP, deux appareils`() {
        assumeTrue("node absent : essai contre le faux lab sauté", labUrl != null)
        val ep = Lab.Endpoints(Lab.normalize(labUrl!!))
        val http = UrlConnectionHttp()
        val a = LabSession(ep, MemLab(), http, { vault })
        assertEquals("moi", browserLogin(a))
        val storeA = EntryStore(MemFiles())
        val e = entry("Créée sur le téléphone", "mdp — ✓")
        storeA.put(vault, e)
        val r = storeA.synchronize(a.storage(), a.username!!, vault, descriptor)
        assertEquals(1, r.sent)

        val b = LabSession(ep, MemLab(), http, { vault })
        browserLogin(b)
        val storeB = EntryStore(MemFiles())
        assertEquals(1, storeB.synchronize(b.storage(), "moi", vault, descriptor).received)
        assertTrue(storeB.all()[0].similar(e))

        // Modification sur B, reçue sur A.
        storeB.put(vault, JSONObject(e.toString()).put("password", "nouveau").put("updated", "2026-11-22T12:00:00.000Z"))
        assertEquals(1, storeB.synchronize(b.storage(), "moi", vault, descriptor).sent)
        assertEquals(1, storeA.synchronize(a.storage(), "moi", vault, descriptor).received)
        assertEquals("nouveau", storeA.all()[0].getString("password"))
        assertFalse(a.username.isNullOrEmpty())
    }

    @Test fun `adresse de retour d'un autre domaine refusee par le faux Keycloak`() {
        assumeTrue("node absent", labUrl != null)
        val ep = Lab.Endpoints(Lab.normalize(labUrl!!))
        val s = LabSession(ep, MemLab(), UrlConnectionHttp(), { vault })
        val evil = s.beginLogin().replace(URLDecoder.decode(ep.redirectUri, "UTF-8").let { java.net.URLEncoder.encode(it, "UTF-8") },
            java.net.URLEncoder.encode("https://ailleurs.fr/coffre-retour/", "UTF-8"))
        val c = URI(evil).toURL().openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        assertEquals(400, c.responseCode)
    }
}
