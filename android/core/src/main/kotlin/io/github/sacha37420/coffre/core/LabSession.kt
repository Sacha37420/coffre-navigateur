package io.github.sacha37420.coffre.core

import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Stockage privé de la session de connexion (rien n'y est en clair qui ouvre le lab). */
interface LabStorage {
    fun readUsername(): String?
    fun writeUsername(username: String?)
    /** Jetons chiffrés par la clé du coffre. */
    fun readSealedTokens(): JSONObject?
    fun writeSealedTokens(sealed: JSONObject?)
    /** Connexion en attente pendant le passage par le navigateur. */
    fun readPending(): JSONObject?
    fun writePending(pending: JSONObject?)
}

/**
 * Connexion au lab depuis l'application. L'adresse du lab est fixée à la construction (l'APK ne revendique
 * que l'adresse de retour de ce lab). Jetons : en mémoire ; copie chiffrée par la clé du coffre (AES-GCM,
 * données associées liées au coffre) pour survivre à l'arrêt de l'application — inutilisable coffre verrouillé.
 */
class LabSession(
    val endpoints: Lab.Endpoints,
    private val storage: LabStorage,
    private val http: Http,
    private val vaultKey: () -> CoffreCrypto.VaultKey?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val PENDING_MAX_MS = 10 * 60_000L
        private const val SEAL_FORMAT = "coffre-android/jetons"
    }

    private var tokens: Oidc.Tokens? = null
    private val random = SecureRandom()

    val username: String? get() = storage.readUsername()

    private fun aad(keyId: String) = "$SEAL_FORMAT|1|$keyId".toByteArray(Charsets.UTF_8)

    private fun seal(t: Oidc.Tokens) {
        val v = vaultKey() ?: return
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, v.key, GCMParameterSpec(128, iv)); updateAAD(aad(v.keyId)) }
        val ct = c.doFinal(t.toJson().toString().toByteArray(Charsets.UTF_8))
        storage.writeSealedTokens(JSONObject().put("key_id", v.keyId).put("iv", Base64Url.encode(iv)).put("ct", Base64Url.encode(ct)))
    }

    private fun unseal(): Oidc.Tokens? {
        val s = storage.readSealedTokens() ?: return null
        val v = vaultKey() ?: return null
        if (s.optString("key_id") != v.keyId) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, v.key, GCMParameterSpec(128, Base64Url.decode(s.getString("iv")))); updateAAD(aad(v.keyId))
            }
            Oidc.Tokens.fromJson(JSONObject(String(c.doFinal(Base64Url.decode(s.getString("ct"))), Charsets.UTF_8)))
        } catch (e: GeneralSecurityException) { null } catch (e: Exception) { null }
    }

    private fun store(t: Oidc.Tokens) {
        tokens = t
        seal(t)
    }

    @Synchronized
    fun connected(): Boolean {
        val t = tokens ?: unseal()?.also { tokens = it } ?: return false
        return username != null && (t.expires > now() || t.refreshExpires > now())
    }

    /** Prépare la connexion : rend l'adresse à ouvrir dans le navigateur. */
    @Synchronized
    fun beginLogin(): String {
        val p = Oidc.pkce()
        val state = Oidc.randomToken(16)
        storage.writePending(JSONObject().put("state", state).put("verifier", p.verifier).put("at", now()))
        return Oidc.authorizeUrl(endpoints, state, p.challenge)
    }

    /** Termine la connexion à partir de l'adresse de retour ; rend le nom de l'espace storage. */
    fun completeLogin(callbackUrl: String): String {
        val pending = synchronized(this) {
            val p = storage.readPending()
            storage.writePending(null)   // à usage unique, quoi qu'il arrive
            p
        }
        if (pending == null || now() - pending.optLong("at") > PENDING_MAX_MS) {
            throw CoffreException("connexion", "Connexion expirée ou inattendue : recommencez depuis l'application.")
        }
        val code = Oidc.parseCallback(callbackUrl, endpoints.redirectUri, pending.getString("state"))
        val t = Oidc.exchangeCode(http, endpoints, code, pending.getString("verifier"), now())
        synchronized(this) { store(t) }
        val user = storage().me()
        storage.writeUsername(user)
        return user
    }

    /** Jeton d'accès valable : en cache, sinon rafraîchi ; sinon il faut se reconnecter. */
    @Synchronized
    fun token(): String {
        val t = tokens ?: unseal()
        if (t != null && t.expires - 30_000 > now()) { tokens = t; return t.access }
        if (t?.refresh != null && t.refreshExpires - 5_000 > now()) {
            try {
                val next = Oidc.refresh(http, endpoints, t.refresh, now())
                store(next)
                return next.access
            } catch (e: CoffreException) {
                if (e.code == "reseau") throw e
            }
        }
        tokens = null
        storage.writeSealedTokens(null)
        throw CoffreException("connexion_requise", "Connectez-vous au lab.")
    }

    /** Le jeton d'accès en cache est refusé : le prochain token() rafraîchira. */
    @Synchronized
    fun invalidate() {
        val t = tokens ?: unseal() ?: return
        tokens = Oidc.Tokens(t.access, t.refresh, 0, t.refreshExpires)
    }

    fun storage(): StorageClient = StorageClient(endpoints.storage, http, { token() }, { invalidate() })

    /** Oublie la session (révocation du jeton de rafraîchissement au mieux). */
    fun logout() {
        val t = synchronized(this) {
            val cur = tokens ?: unseal()
            tokens = null
            storage.writeSealedTokens(null)
            storage.writeUsername(null)
            cur
        }
        t?.refresh?.let {
            try {
                http.request("POST", endpoints.revoke, mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    Oidc.form(mapOf("client_id" to endpoints.clientId, "token" to it, "token_type_hint" to "refresh_token")).toByteArray())
            } catch (e: Exception) { /* au mieux */ }
        }
    }

    /** Coffre verrouillé : le jeton en mémoire est oublié (sa copie chiffrée reste, illisible sans la clé). */
    @Synchronized
    fun forgetMemory() {
        tokens = null
    }
}
