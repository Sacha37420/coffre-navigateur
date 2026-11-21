package io.github.sacha37420.coffre.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class CoffreCryptoTest {
    private val root = File(System.getProperty("coffre.racine") ?: "../..")
    private val vectors = JSONObject(File(root, "tests/vecteurs/v1.json").readText())
    private val phrase = vectors.getString("passphrase")

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun expectCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("attendu : $code")
        } catch (e: CoffreException) {
            assertEquals(code, e.code)
        }
    }

    // ---------- PBKDF2 ----------

    @Test fun `PBKDF2-HMAC-SHA256 - vecteurs de la RFC 7914`() {
        assertEquals(
            Base64Url.encode(hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783")),
            Base64Url.encode(Pbkdf2.hmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)),
        )
        assertEquals(
            Base64Url.encode(hex("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d")),
            Base64Url.encode(Pbkdf2.hmacSha256("Password".toByteArray(), "NaCl".toByteArray(), 80000, 64)),
        )
    }

    @Test fun `PBKDF2 - identique a celui du JDK, accents compris (le JDK encode en UTF-8)`() {
        val salt = ByteArray(16) { it.toByte() }
        for (p in listOf("simple", "Été à Noël ✓ 🔐")) {
            val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(p.toCharArray(), salt, 1000, 256)).encoded
            assertEquals(Base64Url.encode(jdk), Base64Url.encode(Pbkdf2.hmacSha256(p.toByteArray(Charsets.UTF_8), salt, 1000, 32)))
        }
    }

    // ---------- Vecteurs de l'extension ----------

    @Test fun `fichier de cle et enveloppe produits par l'extension`() {
        val kf = CoffreCrypto.parseKeyFile(vectors.getJSONObject("keyFile").toString())
        val vault = CoffreCrypto.openKeyFile(kf, phrase)
        val entry = CoffreCrypto.decryptEntry(vault, vectors.getJSONObject("envelope"))
        assertTrue(entry.similar(vectors.getJSONObject("entry")))
        assertEquals("x7!kQ2#pLm9\$wR4z — é", entry.getString("password"))
        assertTrue(CoffreCrypto.vaultDescriptor(kf).similar(vectors.getJSONObject("descriptor")))
    }

    @Test fun `phrase non normalisee (NFD) - meme cle, comme l'extension`() {
        val nfd = java.text.Normalizer.normalize(phrase, java.text.Normalizer.Form.NFD)
        assertNotEquals(phrase, nfd)
        CoffreCrypto.openKeyFile(vectors.getJSONObject("keyFile"), nfd)
    }

    @Test fun `enveloppe produite par Kotlin ouverte par l'extension (Node)`() {
        val node = listOf("node", "--version").let {
            try { ProcessBuilder(it).start().waitFor(10, TimeUnit.SECONDS) } catch (e: Exception) { false }
        }
        assumeTrue("node absent : vérification croisée sautée", node)
        val kf = vectors.getJSONObject("keyFile")
        val vault = CoffreCrypto.openKeyFile(kf, phrase)
        val entry = JSONObject().put("id", "9f1c2d3e-4b5a-4c6d-8e7f-0a1b2c3d4e5f").put("type", "login")
            .put("name", "Créée sur Android").put("origins", org.json.JSONArray().put("https://exemple.fr"))
            .put("username", "moi").put("password", "mdp — ✓").put("notes", "").put("created", "2026-11-21T11:00:00.000Z")
            .put("updated", "2026-11-21T11:00:00.000Z").put("deleted", false)
        val out = File.createTempFile("vecteur-kotlin", ".json")
        try {
            out.writeText(JSONObject().put("passphrase", phrase).put("keyFile", kf)
                .put("envelope", CoffreCrypto.encryptEntry(vault, entry)).put("entry", entry).toString())
            val p = ProcessBuilder("node", "scripts/verifier-vecteur.mjs", out.absolutePath).directory(root)
                .redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(text, 0, p.exitValue())
            assertTrue(text, text.contains("ok Créée sur Android"))
        } finally {
            out.delete()
        }
    }

    // ---------- Aller-retour et refus ----------

    private val created by lazy { CoffreCrypto.createVault("cheval agrafe batterie correcte") }

    private fun entry(id: String = java.util.UUID.randomUUID().toString()) = JSONObject().put("id", id).put("password", "p")

    @Test fun `creation conforme au format v1, aller-retour`() {
        val (kf, vault) = created
        assertEquals("coffre-navigateur/cle", kf.getString("format"))
        assertEquals(1, kf.getInt("version"))
        assertEquals(600_000, kf.getJSONObject("kdf").getInt("iterations"))
        assertEquals(16, Base64Url.decode(kf.getString("key_id")).size)
        assertEquals(48, Base64Url.decode(kf.getString("wrapped")).size)
        val e = entry()
        val env = CoffreCrypto.encryptEntry(vault, e)
        assertTrue(CoffreCrypto.decryptEntry(vault, env).similar(e))
        assertNotEquals(env.getString("iv"), CoffreCrypto.encryptEntry(vault, e).getString("iv"))
        val reopened = CoffreCrypto.openKeyFile(CoffreCrypto.parseKeyFile(kf.toString()), "cheval agrafe batterie correcte")
        assertTrue(CoffreCrypto.decryptEntry(reopened, env).similar(e))
    }

    @Test fun refus() {
        val (kf, vault) = created
        expectCode("phrase_ou_fichier") { CoffreCrypto.openKeyFile(kf, "pas la bonne phrase") }
        val w = Base64Url.decode(kf.getString("wrapped")).also { it[0] = (it[0].toInt() xor 1).toByte() }
        expectCode("phrase_ou_fichier") { CoffreCrypto.openKeyFile(JSONObject(kf.toString()).put("wrapped", Base64Url.encode(w)), "cheval agrafe batterie correcte") }
        val low = JSONObject(kf.toString()).apply { getJSONObject("kdf").put("iterations", 1000) }
        expectCode("format") { CoffreCrypto.openKeyFile(low, "cheval agrafe batterie correcte") }
        expectCode("format") { CoffreCrypto.openKeyFile(JSONObject(kf.toString()).put("version", 2), "x") }
        expectCode("format") { CoffreCrypto.parseKeyFile("pas du json") }
        expectCode("phrase_courte") { CoffreCrypto.createVault("court") }
        expectCode("format") { CoffreCrypto.encryptEntry(vault, entry("../x")) }

        val env = CoffreCrypto.encryptEntry(vault, entry())
        expectCode("autre_cle") { CoffreCrypto.decryptEntry(CoffreCrypto.VaultKey(Base64Url.encode(ByteArray(16)), vault.key), env) }
        expectCode("entree_alteree") { CoffreCrypto.decryptEntry(vault, JSONObject(env.toString()).put("id", java.util.UUID.randomUUID().toString())) }
        val ct = Base64Url.decode(env.getString("ct")).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x80).toByte() }
        expectCode("entree_alteree") { CoffreCrypto.decryptEntry(vault, JSONObject(env.toString()).put("ct", Base64Url.encode(ct))) }
    }

    @Test fun `changer de phrase garde la meme cle de coffre`() {
        val (kf, vault) = created
        val env = CoffreCrypto.encryptEntry(vault, entry())
        val next = CoffreCrypto.changePassphrase(kf, "cheval agrafe batterie correcte", "une toute autre phrase longue")
        assertEquals(kf.getString("key_id"), next.getString("key_id"))
        expectCode("phrase_ou_fichier") { CoffreCrypto.openKeyFile(next, "cheval agrafe batterie correcte") }
        CoffreCrypto.decryptEntry(CoffreCrypto.openKeyFile(next, "une toute autre phrase longue"), env)
    }

    @Test fun base64url() {
        for (n in listOf(0, 1, 2, 3, 16, 32, 1000)) {
            val b = ByteArray(n) { (it * 7).toByte() }
            val t = Base64Url.encode(b)
            assertTrue(!t.contains('=') && !t.contains('+') && !t.contains('/'))
            assertTrue(b.contentEquals(Base64Url.decode(t)))
        }
        try { Base64Url.decode("ab=c"); fail() } catch (e: IllegalArgumentException) { }
    }

    // ---------- Générateur ----------

    @Test fun generateur() {
        repeat(200) {
            val p = Generator.generate(Generator.Options(length = 12))
            assertEquals(12, p.length)
            for (set in Generator.CLASSES.values) assertTrue(p, p.any { it in set })
        }
        val many = (1..200).joinToString("") { Generator.generate(Generator.Options(length = 64)) }
        assertTrue(many.none { it in "Il1O0o|`'\"" })
        assertTrue(Regex("^[a-z0-9]{40}$").matches(Generator.generate(Generator.Options(40, upper = false, symbols = false, avoidAmbiguous = false))))
        assertTrue(Generator.entropyBits() >= 120)
        for (bad in listOf(Generator.Options(length = 4), Generator.Options(length = 500), Generator.Options(lower = false, upper = false, digits = false, symbols = false))) {
            try { Generator.generate(bad); fail() } catch (e: IllegalArgumentException) { }
        }
    }
}
