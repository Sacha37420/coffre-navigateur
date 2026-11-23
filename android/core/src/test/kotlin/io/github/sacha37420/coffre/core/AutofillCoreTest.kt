package io.github.sacha37420.coffre.core

import io.github.sacha37420.coffre.core.Forms.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import org.json.JSONObject

/** Lot 11 : modèle d'entrée, correspondance de sites, analyse des formulaires, confiance dans le navigateur. */
class AutofillCoreTest {
    private fun web(domain: String, vararg children: Node, scheme: String = "https") =
        Node(webDomain = domain, webScheme = scheme, children = children.toList())

    @Test fun `connexion dans Chrome - identifiant et mot de passe, origine https`() {
        val f = Forms.parse(web("banque.fr",
            Node(id = "titre", htmlTag = "h1"),
            Node(id = "u", htmlTag = "input", htmlType = "text", idEntry = "identifiant"),
            Node(id = "p", htmlTag = "input", htmlType = "password"),
        ))!!
        assertEquals("https://banque.fr", f.origin)
        assertEquals("u", f.username)
        assertEquals(listOf<Any?>("p"), f.passwords)
        assertFalse(f.creation)
    }

    @Test fun `creation de compte - deux mots de passe ou new-password`() {
        val f = Forms.parse(web("site.fr",
            Node(id = "mail", htmlTag = "input", htmlType = "email"),
            Node(id = "p1", htmlTag = "input", htmlType = "password", htmlAutocomplete = "new-password"),
            Node(id = "p2", htmlTag = "input", htmlType = "password", htmlAutocomplete = "new-password"),
        ))!!
        assertTrue(f.creation)
        assertEquals(listOf<Any?>("p1", "p2"), f.passwords)
        assertEquals("mail", f.username)
        assertTrue(Forms.parse(web("site.fr", Node(id = "p", htmlTag = "input", htmlType = "password", htmlAutocomplete = "new-password")))!!.creation)
    }

    @Test fun `identifiant prefere par indice, sinon dernier champ texte avant le mot de passe`() {
        val f = Forms.parse(web("site.fr",
            Node(id = "recherche", htmlTag = "input", htmlType = "text"),
            Node(id = "login", htmlTag = "input", htmlType = "text", hints = listOf("username")),
            Node(id = "autre", htmlTag = "input", htmlType = "text"),
            Node(id = "p", htmlTag = "input", htmlType = "password"),
        ))!!
        assertEquals("login", f.username)
        val g = Forms.parse(web("site.fr", Node(id = "a", htmlTag = "input"), Node(id = "p", htmlTag = "input", htmlType = "password")))!!
        assertEquals("a", g.username)
    }

    @Test fun `cadre d'une autre origine ignore, http refuse, rien sans mot de passe, champs caches ignores`() {
        val f = Forms.parse(web("banque.fr",
            Node(id = "u", htmlTag = "input", htmlType = "text"),
            web("pub.example", Node(id = "piege", htmlTag = "input", htmlType = "password")),
        ))
        assertNull("le seul mot de passe est dans un cadre étranger", f)
        assertNull(Forms.parse(web("banque.fr", Node(id = "p", htmlType = "password"), scheme = "http"))!!.origin)
        assertNull(Forms.parse(web("banque.fr", Node(id = "u", htmlTag = "input", htmlType = "text"))))
        assertNull(Forms.parse(web("banque.fr", Node(id = "p", htmlType = "password", visible = false))))
    }

    @Test fun `champs natifs d'une application (sans domaine)`() {
        val f = Forms.parse(Node(children = listOf(Node(id = "u", hints = listOf("username")), Node(id = "p", inputPassword = true))))!!
        assertNull(f.origin)
        assertEquals("u", f.username)
    }

    @Test fun `navigateur fiable - connu ET systeme ou Play Store`() {
        assertTrue(Forms.trustedBrowser("com.android.chrome", true, null))
        assertTrue(Forms.trustedBrowser("com.android.chrome", false, "com.android.vending"))
        assertFalse("Chrome installé à la main", Forms.trustedBrowser("com.android.chrome", false, "com.google.android.packageinstaller"))
        assertFalse("appli quelconque, même du Play Store", Forms.trustedBrowser("com.exemple.jeu", false, "com.android.vending"))
        assertFalse(Forms.trustedBrowser("com.exemple.jeu", true, null))
    }

    @Test fun `correspondance exacte et decision d'enregistrement`() {
        val a = Entries.newEntry("B", listOf("banque.fr"), "moi", "ancien")
        val b = Entries.newEntry("A", listOf("https://banque.fr", "m.banque.fr"), "autre", "p")
        val c = Entries.newEntry("C", listOf("https://banque.fr:8443"), "x", "p")
        assertEquals(listOf("A", "B"), Entries.forOrigin(listOf(a, b, c), "https://banque.fr").map { it.getString("name") })
        assertEquals(listOf("A"), Entries.forOrigin(listOf(a, b, c), "https://m.banque.fr").map { it.getString("name") })
        assertTrue(Entries.forOrigin(listOf(a, b, c), "https://www.banque.fr").isEmpty())
        assertNull(Entries.proposalFor(listOf(a), "https://banque.fr", "moi", "ancien"))
        assertEquals("maj", Entries.proposalFor(listOf(a), "https://banque.fr", "moi", "nouveau")!!.kind)
        assertEquals("nouveau", Entries.proposalFor(listOf(a), "https://banque.fr", "toi", "x")!!.kind)
    }

    @Test fun `modele d'entree - origines normalisees, horodatage croissant, compatible avec l'extension`() {
        assertEquals("https://exemple.fr", Entries.normalizeOrigin("exemple.fr/login"))
        assertEquals("http://exemple.fr:8080", Entries.normalizeOrigin("http://exemple.fr:8080/a"))
        try { Entries.normalizeOrigin("javascript:alert(1)"); fail() } catch (e: CoffreException) { assertEquals("format", e.code) }
        val e = Entries.newEntry("", listOf("exemple.fr", "https://exemple.fr/x"), "u", "p", now = 1_000)
        assertEquals("exemple.fr", e.getString("name"))
        assertEquals(1, e.getJSONArray("origins").length())
        assertEquals("1970-01-01T00:00:01.000Z", e.getString("created"))
        val e2 = Entries.withPassword(e, "q", now = 500)
        assertTrue(e2.getString("updated") > e.getString("updated"))
        // Une entrée Kotlin chiffrée s'ouvre avec le même format que l'extension (vecteur).
        val root = File(System.getProperty("coffre.racine") ?: "../..")
        val v = JSONObject(File(root, "tests/vecteurs/v1.json").readText())
        val vault = CoffreCrypto.openKeyFile(v.getJSONObject("keyFile"), v.getString("passphrase"))
        assertTrue(CoffreCrypto.decryptEntry(vault, CoffreCrypto.encryptEntry(vault, e2)).similar(e2))
    }

    @Test fun recherche() {
        val a = Entries.newEntry("Électricité", listOf("edf.fr"), "moi@ex.fr", "p")
        val b = Entries.newEntry("banque", listOf("https://banque.fr"), "compte42", "p")
        assertEquals(listOf("banque", "Électricité"), Entries.search(listOf(b, a), "").map { it.getString("name") })
        assertEquals(listOf("Électricité"), Entries.search(listOf(a, b), "electri").map { it.getString("name") })
        assertEquals(listOf("banque"), Entries.search(listOf(a, b), "BANQUE.FR 42").map { it.getString("name") })
    }
}
