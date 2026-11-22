package io.github.sacha37420.coffre.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Mêmes cas que tests/vault-state.test.mjs de l'extension. */
class VaultStateTest {
    private val root = File(System.getProperty("coffre.racine") ?: "../..")
    private val vectors = JSONObject(File(root, "tests/vecteurs/v1.json").readText())
    private val phrase = vectors.getString("passphrase")
    private val keyFile = vectors.getJSONObject("keyFile")

    private class MemStorage : VaultStorage {
        var kf: String? = null
        var minutes: Int? = null
        override fun readKeyFile() = kf?.let { JSONObject(it) }
        override fun writeKeyFile(keyFile: JSONObject) { kf = keyFile.toString() }
        override fun deleteKeyFile() { kf = null }
        override fun readAutoLockMinutes() = minutes
        override fun writeAutoLockMinutes(minutes: Int) { this.minutes = minutes }
    }

    private var t = 1_000_000L
    private val storage = MemStorage()
    private val vault = VaultState(storage) { t }

    private fun expectCode(code: String, block: () -> Unit) {
        try { block(); fail("attendu : $code") } catch (e: CoffreException) { assertEquals(code, e.code) }
    }

    companion object {
        private val other by lazy { CoffreCrypto.createVault("une autre phrase bien longue").first }
    }

    @Test fun `aucun coffre au depart`() {
        assertEquals(VaultState.State.AUCUN, vault.state())
        assertNull(vault.key())
        expectCode("aucun_coffre") { vault.unlock(phrase) }
    }

    @Test fun `import d'un fichier de l'extension, mauvaise phrase sans effet`() {
        expectCode("phrase_ou_fichier") { vault.install(keyFile, "mauvaise phrase de passe") }
        assertNull(storage.kf)
        vault.install(keyFile, phrase)
        assertEquals(VaultState.State.DEVERROUILLE, vault.state())
        val entry = CoffreCrypto.decryptEntry(vault.key()!!, vectors.getJSONObject("envelope"))
        assertEquals("Banque Épargne", entry.getString("name"))
        assertFalse("la clé brute n'est jamais écrite", storage.kf!!.contains(Base64Url.encode(vault.rawKeyCopy()!!)))
    }

    @Test fun `verrouiller, mauvaise phrase, deverrouiller`() {
        vault.install(keyFile, phrase)
        vault.lock()
        assertEquals(VaultState.State.VERROUILLE, vault.state())
        assertNull(vault.key())
        expectCode("phrase_ou_fichier") { vault.unlock("mauvaise phrase de passe") }
        vault.unlock(phrase)
        assertEquals(VaultState.State.DEVERROUILLE, vault.state())
    }

    @Test fun `verrouillage apres inactivite, chaque usage repousse l'echeance`() {
        vault.install(keyFile, phrase)
        t += 10 * 60_000
        assertNotNull(vault.key())
        t += 10 * 60_000
        assertEquals(VaultState.State.DEVERROUILLE, vault.state())
        t += 6 * 60_000
        assertNull(vault.key())
        assertEquals(VaultState.State.VERROUILLE, vault.state())
        assertNull(vault.rawKeyCopy())
    }

    @Test fun `delai reglable, valeurs fermees`() {
        vault.setAutoLockMinutes(5)
        vault.install(keyFile, phrase)
        t += 5 * 60_000
        assertEquals(VaultState.State.VERROUILLE, vault.state())
        expectCode("reglage") { vault.setAutoLockMinutes(7) }
        storage.minutes = 7   // valeur inattendue sur le disque : défaut
        assertEquals(15, vault.autoLockMinutes)
    }

    @Test fun `un autre coffre ne remplace pas l'installe sans le demander`() {
        vault.install(keyFile, phrase)
        expectCode("autre_coffre_installe") { vault.install(other, "une autre phrase bien longue") }
        vault.install(other, "une autre phrase bien longue", replace = true)
        assertEquals(other.getString("key_id"), vault.keyId())
    }

    @Test fun `empreinte - cle brute du bon coffre seulement`() {
        vault.install(keyFile, phrase)
        val raw = vault.rawKeyCopy()!!
        vault.lock()
        expectCode("autre_cle") { vault.unlockWithRaw(other.getString("key_id"), raw.copyOf()) }
        expectCode("autre_cle") { vault.unlockWithRaw(keyFile.getString("key_id"), ByteArray(16)) }
        vault.unlockWithRaw(keyFile.getString("key_id"), raw)
        assertTrue("la copie passée est effacée", raw.all { it == 0.toByte() })
        assertEquals("Banque Épargne", CoffreCrypto.decryptEntry(vault.key()!!, vectors.getJSONObject("envelope")).getString("name"))
    }

    @Test fun `confirmation datee de 5 min`() {
        vault.install(keyFile, phrase)
        assertTrue(vault.recentlyAuthenticated())
        t += 4 * 60_000
        vault.key()
        t += 2 * 60_000
        assertFalse(vault.recentlyAuthenticated())
        expectCode("phrase_ou_fichier") { vault.confirm("mauvaise phrase de passe") }
        vault.confirm(phrase)
        assertTrue(vault.recentlyAuthenticated())
        t += 6 * 60_000
        vault.key()
        vault.confirmedByBiometric()
        assertTrue(vault.recentlyAuthenticated())
        vault.lock()
        assertFalse(vault.recentlyAuthenticated())
        expectCode("aucun_coffre") { vault.confirm(phrase) }
    }

    @Test fun `retirer le coffre`() {
        vault.install(keyFile, phrase)
        vault.forget()
        assertNull(storage.kf)
        assertEquals(VaultState.State.AUCUN, vault.state())
    }

    @Test fun `fichier de cle remplace sur le disque - la session de l'ancien ne vaut plus`() {
        vault.install(keyFile, phrase)
        storage.kf = other.toString()
        assertEquals(VaultState.State.VERROUILLE, vault.state())
    }
}
