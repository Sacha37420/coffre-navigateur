package io.github.sacha37420.coffre.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

class LabTest {
    private val root = File(System.getProperty("coffre.racine") ?: "../..")
    private val vectors = JSONObject(File(root, "tests/vecteurs/v1.json").readText())
    private val vault = CoffreCrypto.openKeyFile(vectors.getJSONObject("keyFile"), vectors.getString("passphrase"))

    private fun expectCode(code: String, block: () -> Unit) {
        try { block(); fail("attendu : $code") } catch (e: CoffreException) { assertEquals(code, e.code) }
    }

    private fun params(q: String) = q.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }

    @Test fun `adresse du lab`() {
        assertEquals("https://lab.exemple.fr", Lab.normalize(" https://lab.exemple.fr/storage/x?y "))
        assertEquals("https://lab.exemple.fr", Lab.normalize("https://LAB.exemple.fr:443/"))
        assertEquals("http://127.0.0.1:9000", Lab.normalize("http://127.0.0.1:9000/"))
        expectCode("reglage") { Lab.normalize("http://lab.exemple.fr") }
        expectCode("reglage") { Lab.normalize("ftp://lab.exemple.fr") }
        expectCode("reglage") { Lab.normalize("https://moi:mdp@lab.exemple.fr") }
        expectCode("reglage") { Lab.normalize("pas une adresse") }
        val ep = Lab.Endpoints("https://lab.fr")
        assertEquals("https://lab.fr/auth/realms/ssolab/protocol/openid-connect/token", ep.token)
        assertEquals("https://lab.fr/storage-api/api", ep.storage)
        assertEquals("https://lab.fr/coffre-retour/", ep.redirectUri)
    }

    @Test fun `PKCE et adresse d'autorisation`() {
        val p = Oidc.pkce()
        assertTrue(Regex("^[A-Za-z0-9_-]{43,128}$").matches(p.verifier))
        assertEquals(Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(p.verifier.toByteArray())), p.challenge)
        val u = URI(Oidc.authorizeUrl(Lab.Endpoints("https://lab.fr"), "S", p.challenge))
        assertEquals("/auth/realms/ssolab/protocol/openid-connect/auth", u.path)
        val q = params(u.rawQuery)
        assertEquals(mapOf("response_type" to "code", "client_id" to "coffre-navigateur", "redirect_uri" to "https://lab.fr/coffre-retour/",
            "scope" to "openid", "state" to "S", "code_challenge" to p.challenge, "code_challenge_method" to "S256"), q)
    }

    @Test fun `retour de connexion`() {
        val r = "https://lab.fr/coffre-retour/"
        assertEquals("K", Oidc.parseCallback("$r?state=S&code=K&session_state=x", r, "S"))
        expectCode("connexion") { Oidc.parseCallback("$r?state=T&code=K", r, "S") }
        expectCode("connexion") { Oidc.parseCallback("https://autre.fr/coffre-retour/?state=S&code=K", r, "S") }
        expectCode("connexion") { Oidc.parseCallback("https://lab.fr/autre/?state=S&code=K", r, "S") }
        expectCode("connexion") { Oidc.parseCallback("http://lab.fr/coffre-retour/?state=S&code=K", r, "S") }
        expectCode("acces_refuse") { Oidc.parseCallback("$r?state=S&error=access_denied", r, "S") }
        expectCode("connexion") { Oidc.parseCallback("$r?state=S", r, "S") }
    }

    // ---------- Session, avec un faux HTTP ----------

    private class MemLab : LabStorage {
        var user: String? = null; var sealed: JSONObject? = null; var pending: JSONObject? = null
        override fun readUsername() = user
        override fun writeUsername(username: String?) { user = username }
        override fun readSealedTokens() = sealed
        override fun writeSealedTokens(sealed: JSONObject?) { this.sealed = sealed }
        override fun readPending() = pending
        override fun writePending(pending: JSONObject?) { this.pending = pending }
    }

    private class FakeHttp : Http {
        val calls = mutableListOf<Triple<String, String, String>>()
        var n = 0
        var storageStatus = 200
        var down = false
        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            if (down) throw IOException("injoignable")
            val b = body?.let { String(it) } ?: ""
            calls.add(Triple(method, url, headers["Authorization"] ?: b))
            if (url.endsWith("/token")) {
                n++
                return HttpResponse(200, JSONObject().put("access_token", "A$n").put("refresh_token", "R$n")
                    .put("expires_in", 300).put("refresh_expires_in", 1800).toString().toByteArray())
            }
            if (url.endsWith("/revoke")) return HttpResponse(200, ByteArray(0))
            if (url.endsWith("/me/")) return HttpResponse(storageStatus, """{"username":"moi"}""".toByteArray())
            return HttpResponse(404, ByteArray(0))
        }
    }

    private var t = 1_000_000L
    private var locked = false

    private fun session(storage: MemLab = MemLab(), http: FakeHttp = FakeHttp()) =
        Triple(LabSession(Lab.Endpoints("https://lab.fr"), storage, http, { if (locked) null else vault }) { t }, storage, http)

    private fun login(s: LabSession): String {
        val u = URI(s.beginLogin())
        val state = params(u.rawQuery)["state"]!!
        return s.completeLogin("https://lab.fr/coffre-retour/?state=$state&code=CODE")
    }

    @Test fun `connexion - PKCE verifie, jetons chiffres par la cle du coffre`() {
        val (s, st, http) = session()
        assertFalse(s.connected())
        val u = URI(s.beginLogin())
        val challenge = params(u.rawQuery)["code_challenge"]!!
        assertEquals("moi", s.completeLogin("https://lab.fr/coffre-retour/?state=${params(u.rawQuery)["state"]}&code=CODE"))
        val body = params(http.calls.first { it.second.endsWith("/token") }.third)
        assertEquals("CODE", body["code"])
        assertEquals("https://lab.fr/coffre-retour/", body["redirect_uri"])
        assertEquals(challenge, Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(body["code_verifier"]!!.toByteArray())))
        assertNull("connexion en attente consommée", st.pending)
        assertTrue(s.connected())
        assertEquals("moi", st.user)
        val sealed = st.sealed.toString()
        assertFalse("rien de lisible dans la copie gardée", sealed.contains("A1") || sealed.contains("R1"))
        assertEquals("Bearer A1", http.calls.last().third)
    }

    @Test fun `retour rejoue, expire ou sans attente - refus`() {
        val (s, st, _) = session()
        val u = URI(s.beginLogin())
        val cb = "https://lab.fr/coffre-retour/?state=${params(u.rawQuery)["state"]}&code=CODE"
        s.completeLogin(cb)
        expectCode("connexion") { s.completeLogin(cb) }        // à usage unique
        s.beginLogin()
        t += LabSession.PENDING_MAX_MS + 1
        expectCode("connexion") { s.completeLogin(cb) }
        assertNull(st.pending)
    }

    @Test fun `jeton - cache, rafraichissement, reconnexion requise`() {
        val (s, _, _) = session()
        login(s)
        assertEquals("A1", s.token())
        t += 290_000
        assertEquals("A2", s.token())
        t += 10_000_000
        expectCode("connexion_requise") { s.token() }
        assertFalse(s.connected())
    }

    @Test fun `la session survit a l'arret de l'application, pas au coffre verrouille ni a un autre coffre`() {
        val st = MemLab()
        val (s1, _, http) = session(st)
        login(s1)
        val (s2, _, _) = session(st, http)                     // nouveau processus : rien en mémoire
        assertEquals("A1", s2.token())
        locked = true
        val (s3, _, _) = session(st, http)
        expectCode("connexion_requise") { s3.token() }
        locked = false
        val autre = CoffreCrypto.createVault("une autre phrase bien longue").second
        val s4 = LabSession(Lab.Endpoints("https://lab.fr"), MemLab().apply { user = "moi"; sealed = st.sealed }, http, { autre }) { t }
        expectCode("connexion_requise") { s4.token() }
    }

    @Test fun `storage refuse (403) - renouvellement puis une seule nouvelle tentative`() {
        val (s, _, http) = session()
        login(s)
        http.storageStatus = 403
        val before = http.n
        expectCode("acces_refuse") { s.storage().me() }
        assertEquals("un rafraîchissement", before + 1, http.n)
        assertEquals(2, http.calls.count { it.second.endsWith("/me/") } - 1)
    }

    @Test fun `deconnexion - revoque et oublie`() {
        val (s, st, http) = session()
        login(s)
        s.logout()
        assertTrue(http.calls.last().second.endsWith("/revoke"))
        assertNull(st.sealed)
        assertNull(st.user)
        assertFalse(s.connected())
    }

    @Test fun `lab injoignable - erreur fixe`() {
        val (s, _, http) = session()
        val u = URI(s.beginLogin())
        http.down = true
        expectCode("reseau") { s.completeLogin("https://lab.fr/coffre-retour/?state=${params(u.rawQuery)["state"]}&code=C") }
    }

    @Test fun `deux appels successifs n'ont pas le meme vecteur`() {
        val (s, st, _) = session()
        login(s)
        val first = st.sealed!!.getString("iv")
        t += 290_000
        s.token()
        assertNotEquals(first, st.sealed!!.getString("iv"))
    }
}
