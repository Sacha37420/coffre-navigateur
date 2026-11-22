package io.github.sacha37420.coffre.core

import org.json.JSONObject

/** Où l'application range le fichier de clé (chiffré) et ses réglages. */
interface VaultStorage {
    fun readKeyFile(): JSONObject?
    fun writeKeyFile(keyFile: JSONObject)
    fun deleteKeyFile()
    fun readAutoLockMinutes(): Int?
    fun writeAutoLockMinutes(minutes: Int)
}

/**
 * État du coffre sur le téléphone : aucun / verrouillé / déverrouillé — mêmes règles que
 * src/lib/vault-state.js de l'extension. La clé déverrouillée ne vit qu'en mémoire du processus :
 * elle n'est jamais écrite ; la mort du processus vaut verrouillage.
 */
class VaultState(private val storage: VaultStorage, private val now: () -> Long = System::currentTimeMillis) {
    enum class State { AUCUN, VERROUILLE, DEVERROUILLE }

    private class Session(val vault: CoffreCrypto.VaultKey, var expires: Long, var authAt: Long)

    private var session: Session? = null

    companion object {
        const val DEFAULT_AUTO_LOCK = 15
        val AUTO_LOCK_CHOICES = listOf(1, 5, 15, 30, 60, 240)
        /** Afficher un mot de passe demande une phrase (ou une empreinte) saisie depuis moins de 5 min. */
        const val REAUTH_MS = 5 * 60_000L
    }

    val autoLockMinutes: Int
        get() = storage.readAutoLockMinutes()?.takeIf { it in AUTO_LOCK_CHOICES } ?: DEFAULT_AUTO_LOCK

    @Synchronized
    fun setAutoLockMinutes(minutes: Int) {
        if (minutes !in AUTO_LOCK_CHOICES) throw CoffreException("reglage", "Délai de verrouillage non pris en charge.")
        storage.writeAutoLockMinutes(minutes)
        session?.let { it.expires = now() + minutes * 60_000L }
    }

    /** Lit la session ; la supprime si le délai est passé. */
    @Synchronized
    private fun live(): Session? {
        val s = session ?: return null
        val kf = storage.readKeyFile()
        if (now() >= s.expires || kf == null || kf.optString("key_id") != s.vault.keyId) {
            session = null
            return null
        }
        return s
    }

    @Synchronized
    fun state(): State = when {
        storage.readKeyFile() == null -> State.AUCUN
        live() == null -> State.VERROUILLE
        else -> State.DEVERROUILLE
    }

    fun keyId(): String? = storage.readKeyFile()?.optString("key_id")

    private fun start(keyId: String, raw: ByteArray) {
        try {
            val t = now()
            session = Session(CoffreCrypto.VaultKey(keyId, CoffreCrypto.importVaultKey(raw.copyOf())), t + autoLockMinutes * 60_000L, t)
        } finally {
            raw.fill(0)
        }
    }

    /** Installe un fichier de clé (import) et déverrouille. Rien n'est écrit si la phrase est fausse. */
    @Synchronized
    fun install(keyFile: JSONObject, passphrase: String, replace: Boolean = false) {
        val current = storage.readKeyFile()
        if (current != null && current.optString("key_id") != keyFile.optString("key_id") && !replace) {
            throw CoffreException("autre_coffre_installe", "Un autre coffre est déjà installé sur ce téléphone.")
        }
        val raw = CoffreCrypto.openKeyFileRaw(keyFile, passphrase)
        storage.writeKeyFile(keyFile)
        start(keyFile.getString("key_id"), raw)
    }

    @Synchronized
    fun unlock(passphrase: String) {
        val kf = storage.readKeyFile() ?: throw CoffreException("aucun_coffre", "Aucun coffre sur ce téléphone.")
        start(kf.getString("key_id"), CoffreCrypto.openKeyFileRaw(kf, passphrase))
    }

    /** Déverrouillage par empreinte : la clé brute vient du coffre matériel, pour ce coffre-ci seulement. */
    @Synchronized
    fun unlockWithRaw(keyId: String, raw: ByteArray) {
        val kf = storage.readKeyFile()
        if (kf == null || kf.optString("key_id") != keyId || raw.size != 32) {
            raw.fill(0)
            throw CoffreException("autre_cle", "L'empreinte enregistrée ne correspond pas à ce coffre : utilisez la phrase de passe.")
        }
        start(keyId, raw)
    }

    @Synchronized
    fun lock() {
        session = null
    }

    /** Clé utilisable, ou null si verrouillé. Chaque appel repousse le verrouillage. */
    @Synchronized
    fun key(): CoffreCrypto.VaultKey? {
        val s = live() ?: return null
        s.expires = now() + autoLockMinutes * 60_000L
        return s.vault
    }

    /** Clé utilisable sans repousser le verrouillage (synchronisation de fond). */
    @Synchronized
    fun peekKey(): CoffreCrypto.VaultKey? = live()?.vault

    /** Le fichier de clé (chiffré) de ce téléphone. */
    fun keyFile(): JSONObject? = storage.readKeyFile()

    /** Copie de la clé brute du coffre ouvert (pour activer l'empreinte) ; l'appelant l'efface. */
    @Synchronized
    fun rawKeyCopy(): ByteArray? = live()?.vault?.key?.encoded

    @Synchronized
    fun recentlyAuthenticated(): Boolean = live()?.let { now() - it.authAt < REAUTH_MS } ?: false

    /** Revérifie la phrase sur un coffre ouvert (avant d'afficher un secret). */
    @Synchronized
    fun confirm(passphrase: String) {
        val kf = storage.readKeyFile() ?: throw CoffreException("aucun_coffre", "Aucun coffre sur ce téléphone.")
        CoffreCrypto.openKeyFileRaw(kf, passphrase).fill(0)
        val s = live() ?: throw CoffreException("aucun_coffre", "Le coffre s'est verrouillé : déverrouillez-le.")
        s.authAt = now()
    }

    /** Une empreinte réussie vaut confirmation. */
    @Synchronized
    fun confirmedByBiometric() {
        live()?.authAt = now()
    }

    /** Retire le coffre du téléphone (le fichier sur clé USB n'est pas touché). */
    @Synchronized
    fun forget() {
        session = null
        storage.deleteKeyFile()
    }
}
