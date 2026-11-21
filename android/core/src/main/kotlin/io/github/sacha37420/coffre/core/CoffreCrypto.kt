package io.github.sacha37420.coffre.core

import org.json.JSONException
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Implémentation Kotlin de docs/FORMAT.md v1, à l'identique de src/lib/crypto.js de l'extension :
 * mêmes champs, mêmes données associées, mêmes bornes, mêmes refus, mêmes codes d'erreur.
 * Toute évolution du format est une version 2, lue à côté de la v1.
 */
object CoffreCrypto {
    const val KEY_FORMAT = "coffre-navigateur/cle"
    const val ENTRY_FORMAT = "coffre-navigateur/entree"
    const val VAULT_FORMAT = "coffre-navigateur/coffre"
    const val VERSION = 1
    const val MIN_ITERATIONS = 600_000
    const val MAX_ITERATIONS = 10_000_000
    const val MIN_PASSPHRASE = 12

    private val random = SecureRandom()
    private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    /** Clé de coffre utilisable, et son identifiant public. */
    class VaultKey(val keyId: String, val key: SecretKey)

    private fun random(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    private fun bytesField(obj: JSONObject, name: String, len: Int? = null): ByteArray {
        val v = try {
            Base64Url.decode(obj.getString(name))
        } catch (e: JSONException) {
            throw CoffreException("format", "Champ « $name » invalide.")
        } catch (e: IllegalArgumentException) {
            throw CoffreException("format", "Champ « $name » invalide.")
        }
        if (len != null && v.size != len) throw CoffreException("format", "Champ « $name » de taille inattendue.")
        return v
    }

    private fun checkHeader(obj: JSONObject, format: String) {
        if (obj.optString("format") != format) throw CoffreException("format", "Ce fichier n'est pas au format attendu.")
        if (obj.opt("version") != VERSION) throw CoffreException("format", "Version de format non prise en charge.")
        bytesField(obj, "key_id", 16)
    }

    private fun gcm(mode: Int, key: SecretKey, iv: ByteArray, aad: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return c.doFinal(data)
    }

    private fun wrappingKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKey {
        val pw = utf8(nfc(passphrase))
        val k = Pbkdf2.hmacSha256(pw, salt, iterations, 32)
        pw.fill(0)
        return SecretKeySpec(k, "AES").also { k.fill(0) }
    }

    private fun keyAad(keyId: String) = utf8("$KEY_FORMAT|$VERSION|$keyId")

    private fun entryAad(keyId: String, id: String) = utf8("$ENTRY_FORMAT|$VERSION|$keyId|$id")

    private fun checkPassphrase(passphrase: String) {
        if (nfc(passphrase).codePointCount(0, nfc(passphrase).length) < MIN_PASSPHRASE) {
            throw CoffreException("phrase_courte", "La phrase de passe doit faire au moins $MIN_PASSPHRASE caractères.")
        }
    }

    /** Importe une clé brute de 32 octets. */
    fun importVaultKey(raw: ByteArray): SecretKey {
        if (raw.size != 32) throw CoffreException("format", "Clé de coffre invalide.")
        return SecretKeySpec(raw, "AES")
    }

    /** Lit un texte comme fichier de clé ; erreur fixe si ce n'en est pas un. */
    fun parseKeyFile(text: String): JSONObject {
        val obj = try { JSONObject(text) } catch (e: JSONException) {
            throw CoffreException("format", "Ce fichier n'est pas un fichier de clé.")
        }
        checkHeader(obj, KEY_FORMAT)
        return obj
    }

    /** Ouvre un fichier de clé ; rend la clé brute (l'appelant l'efface après usage). */
    fun openKeyFileRaw(keyFile: JSONObject, passphrase: String): ByteArray {
        checkHeader(keyFile, KEY_FORMAT)
        val kdf = keyFile.optJSONObject("kdf") ?: throw CoffreException("format", "Paramètres de dérivation refusés.")
        val iterations = kdf.opt("iterations")
        if (kdf.optString("name") != "PBKDF2-SHA256" || iterations !is Int
            || iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS
        ) throw CoffreException("format", "Paramètres de dérivation refusés.")
        val salt = bytesField(kdf, "salt", 16)
        val iv = bytesField(keyFile, "iv", 12)
        val wrapped = bytesField(keyFile, "wrapped", 48)
        val wk = wrappingKey(passphrase, salt, iterations)
        return try {
            gcm(Cipher.DECRYPT_MODE, wk, iv, keyAad(keyFile.getString("key_id")), wrapped)
        } catch (e: GeneralSecurityException) {
            throw CoffreException("phrase_ou_fichier", "Phrase de passe incorrecte, ou fichier de clé abîmé.")
        }
    }

    fun openKeyFile(keyFile: JSONObject, passphrase: String): VaultKey {
        val raw = openKeyFileRaw(keyFile, passphrase)
        try {
            return VaultKey(keyFile.getString("key_id"), importVaultKey(raw.copyOf()))
        } finally {
            raw.fill(0)
        }
    }

    private fun wrap(raw: ByteArray, keyId: String, passphrase: String, iterations: Int, into: JSONObject) {
        val salt = random(16)
        val iv = random(12)
        val wrapped = gcm(Cipher.ENCRYPT_MODE, wrappingKey(passphrase, salt, iterations), iv, keyAad(keyId), raw)
        into.put("kdf", JSONObject().put("name", "PBKDF2-SHA256").put("iterations", iterations).put("salt", Base64Url.encode(salt)))
        into.put("iv", Base64Url.encode(iv))
        into.put("wrapped", Base64Url.encode(wrapped))
    }

    /** Crée un coffre : rend le fichier de clé (à enregistrer sur clé USB) et la clé utilisable. */
    fun createVault(passphrase: String, iterations: Int = MIN_ITERATIONS, now: Instant = Instant.now()): Pair<JSONObject, VaultKey> {
        checkPassphrase(passphrase)
        if (iterations < MIN_ITERATIONS) throw CoffreException("format", "Nombre d'itérations insuffisant.")
        val raw = random(32)
        try {
            val keyId = Base64Url.encode(random(16))
            val kf = JSONObject().put("format", KEY_FORMAT).put("version", VERSION).put("key_id", keyId)
                .put("created", now.toString())
            wrap(raw, keyId, passphrase, iterations, kf)
            return kf to VaultKey(keyId, importVaultKey(raw.copyOf()))
        } finally {
            raw.fill(0)
        }
    }

    /** Même clé de coffre, autre phrase : nouveau fichier de clé. */
    fun changePassphrase(keyFile: JSONObject, oldPassphrase: String, newPassphrase: String): JSONObject {
        checkPassphrase(newPassphrase)
        val raw = openKeyFileRaw(keyFile, oldPassphrase)
        try {
            val keyId = keyFile.getString("key_id")
            val next = JSONObject().put("format", KEY_FORMAT).put("version", VERSION).put("key_id", keyId)
                .put("created", keyFile.optString("created"))
            val it = maxOf(keyFile.getJSONObject("kdf").getInt("iterations"), MIN_ITERATIONS)
            wrap(raw, keyId, newPassphrase, it, next)
            return next
        } finally {
            raw.fill(0)
        }
    }

    /** Chiffre une entrée (objet docs/FORMAT.md §3) en enveloppe. */
    fun encryptEntry(vault: VaultKey, entry: JSONObject): JSONObject {
        val id = entry.optString("id")
        if (!UUID_RE.matches(id)) throw CoffreException("format", "Identifiant d'entrée invalide.")
        val iv = random(12)
        val ct = gcm(Cipher.ENCRYPT_MODE, vault.key, iv, entryAad(vault.keyId, id), utf8(entry.toString()))
        return JSONObject().put("format", ENTRY_FORMAT).put("version", VERSION).put("key_id", vault.keyId)
            .put("id", id).put("iv", Base64Url.encode(iv)).put("ct", Base64Url.encode(ct))
    }

    /** Déchiffre une enveloppe ; refuse une autre clé, un autre id ou une altération. */
    fun decryptEntry(vault: VaultKey, envelope: JSONObject): JSONObject {
        checkHeader(envelope, ENTRY_FORMAT)
        val id = envelope.optString("id")
        if (!UUID_RE.matches(id)) throw CoffreException("format", "Identifiant d'entrée invalide.")
        if (envelope.getString("key_id") != vault.keyId) {
            throw CoffreException("autre_cle", "Cette entrée appartient à un autre coffre.")
        }
        val iv = bytesField(envelope, "iv", 12)
        val ct = bytesField(envelope, "ct")
        val entry = try {
            JSONObject(String(gcm(Cipher.DECRYPT_MODE, vault.key, iv, entryAad(vault.keyId, id), ct), Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            throw CoffreException("entree_alteree", "Entrée illisible ou altérée.")
        } catch (e: JSONException) {
            throw CoffreException("entree_alteree", "Entrée illisible ou altérée.")
        }
        if (entry.optString("id") != id) throw CoffreException("entree_alteree", "Entrée illisible ou altérée.")
        return entry
    }

    /** Descripteur public déposé à côté des entrées (docs/FORMAT.md §4). */
    fun vaultDescriptor(keyFile: JSONObject): JSONObject = JSONObject().put("format", VAULT_FORMAT)
        .put("version", VERSION).put("key_id", keyFile.getString("key_id")).put("created", keyFile.optString("created"))
}
