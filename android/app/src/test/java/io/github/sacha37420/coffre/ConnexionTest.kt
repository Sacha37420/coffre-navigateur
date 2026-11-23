package io.github.sacha37420.coffre

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import io.github.sacha37420.coffre.core.EntryStore
import io.github.sacha37420.coffre.core.Http
import io.github.sacha37420.coffre.core.HttpResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.URLDecoder

/** Lot 9 sur la JVM : « Se connecter » → navigateur (PKCE) → retour App Link → connecté et synchronisé. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnexionTest {
    private val root = File(System.getProperty("coffre.racine") ?: "../..")
    private val vectors = JSONObject(File(root, "tests/vecteurs/v1.json").readText())
    private val phrase = vectors.getString("passphrase")

    /** Faux Keycloak + faux storage, mêmes réponses que les vrais (403 sans jeton valable). */
    private class FakeLab : Http {
        val files = linkedMapOf<String, Pair<String, String>>()
        var issued = mutableSetOf<String>()
        var lastTokenBody: Map<String, String> = emptyMap()
        var n = 0
        override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            val path = url.substringAfter("https://lab.essai.fr").substringBefore("?")
            if (path.endsWith("/openid-connect/token")) {
                lastTokenBody = String(body!!).split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
                val a = "A${++n}"; issued.add(a)
                return HttpResponse(200, JSONObject().put("access_token", a).put("refresh_token", "R$n").put("expires_in", 300).put("refresh_expires_in", 1800).toString().toByteArray())
            }
            if (path.endsWith("/revoke")) return HttpResponse(200, ByteArray(0))
            if (headers["Authorization"]?.removePrefix("Bearer ") !in issued) return HttpResponse(403, ByteArray(0))
            if (path == "/storage-api/api/me/") return HttpResponse(200, """{"username":"moi"}""".toByteArray())
            if (path == "/storage-api/api/files/moi/" && method == "GET") {
                val prefix = URLDecoder.decode(url.substringAfter("prefix="), "UTF-8")
                val arr = JSONArray()
                files.filterKeys { it.startsWith(prefix) }.forEach { (k, v) -> arr.put(JSONObject().put("relative_path", k).put("updated_at", v.second)) }
                return HttpResponse(200, arr.toString().toByteArray())
            }
            if (path == "/storage-api/api/files/moi/" && method == "POST") {
                val text = String(body!!, Charsets.UTF_8)
                val parts = text.split("\r\n")
                val rel = parts[parts.indexOfFirst { it.contains("name=\"relative_path\"") } + 2]
                val start = parts.indexOfFirst { it.startsWith("Content-Type: application/octet-stream") } + 2
                val content = parts[start]
                val u = "2026-11-22T12:00:${(10 + files.size)}.000Z"
                files[rel] = content to u
                return HttpResponse(201, JSONObject().put("updated_at", u).toString().toByteArray())
            }
            if (path.startsWith("/storage-api/api/files/moi/content/") && method == "GET") {
                val rel = path.removePrefix("/storage-api/api/files/moi/content/").split("/").joinToString("/") { URLDecoder.decode(it, "UTF-8") }
                return files[rel]?.let { HttpResponse(200, it.first.toByteArray()) } ?: HttpResponse(404, ByteArray(0))
            }
            return HttpResponse(404, ByteArray(0))
        }
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("délai dépassé")
    }

    @Test fun `se connecter, revenir par l'App Link, synchroniser, se deconnecter`() {
        val a0 = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val app = a0.coffre
        val fake = FakeLab()
        app.http = fake
        app.setupLab("https://lab.essai.fr")
        app.vault.install(vectors.getJSONObject("keyFile"), phrase)
        // Une entrée existe déjà sur le téléphone (déposée ici par le cache, comme le fera le lot 10).
        app.entries.put(app.vault.key()!!, JSONObject(vectors.getJSONObject("entry").toString()))

        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = main.get()
        assertTrue(a.findViewById<TextView>(R.id.lab_state).text.contains("Non connecté"))
        assertEquals(View.GONE, a.findViewById<Button>(R.id.lab_sync).visibility)

        // « Se connecter » ouvre le navigateur sur Keycloak, avec PKCE et l'adresse de retour du lab.
        a.findViewById<Button>(R.id.lab_login).performClick()
        val browser = shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, browser.action)
        val auth = browser.data!!
        assertEquals("/auth/realms/ssolab/protocol/openid-connect/auth", auth.path)
        assertEquals("https://lab.essai.fr/coffre-retour/", auth.getQueryParameter("redirect_uri"))
        assertEquals("S256", auth.getQueryParameter("code_challenge_method"))
        val state = auth.getQueryParameter("state")!!

        // Un retour avec un autre state est refusé, sans rien enregistrer.
        val bad = Robolectric.buildActivity(RetourActivity::class.java,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://lab.essai.fr/coffre-retour/?state=autre&code=X"))).setup()
        waitFor { bad.get().isFinishing }
        assertFalse(app.lab!!.connected())

        // Le vrai retour (connexion à refaire : le précédent a consommé l'attente, à usage unique).
        a.findViewById<Button>(R.id.lab_login).performClick()
        val state2 = shadowOf(a).nextStartedActivity.data!!.getQueryParameter("state")!!
        assertTrue(state != state2)
        val retour = Robolectric.buildActivity(RetourActivity::class.java,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://lab.essai.fr/coffre-retour/?state=$state2&code=CODE&session_state=s"))).setup()
        waitFor { retour.get().isFinishing && app.lastSync() != null }
        assertEquals("CODE", fake.lastTokenBody["code"])
        assertTrue(app.lab!!.connected())
        assertEquals("moi", app.lab!!.username)
        assertTrue(app.lastSync()!!.ok)
        assertNotNull(fake.files[EntryStore.DESCRIPTOR_PATH])
        assertNotNull(fake.files[EntryStore.entryPath(vectors.getJSONObject("entry").getString("id"))])
        assertTrue("rien de clair sur le lab", fake.files.values.none { it.first.contains("Banque") || it.first.contains("client42") })
        assertFalse("jetons chiffrés sur le disque", File(a.filesDir, "jetons.json").readText().contains("A1"))

        // L'accueil affiche l'état connecté.
        main.pause().resume()
        val b = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        waitFor { b.findViewById<TextView>(R.id.lab_state).text.contains("« moi »") }
        assertEquals(View.VISIBLE, b.findViewById<Button>(R.id.lab_sync).visibility)
        assertTrue(b.findViewById<Button>(R.id.home_entrees).text.contains("(1)"))

        // Déconnexion.
        b.findViewById<Button>(R.id.lab_logout).performClick()
        waitFor { !app.lab!!.connected() }
        assertFalse(File(a.filesDir, "jetons.json").exists())
    }
}
