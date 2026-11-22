package io.github.sacha37420.coffre.core

import java.net.URI
import java.net.URLEncoder

/** Adresses du lab — mêmes conventions que src/lib/lab-config.js (Keycloak sous /auth, storage sous /storage-api). */
object Lab {
    const val DEFAULT_REALM = "ssolab"
    const val DEFAULT_CLIENT_ID = "coffre-navigateur"
    /** Chemin de retour revendiqué par l'application (Android App Links). */
    const val RETURN_PATH = "/coffre-retour/"
    private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]")

    /** Origine du lab ; https seulement, sauf boucle locale (essais). */
    fun normalize(input: String): String {
        val u = try { URI(input.trim()) } catch (e: Exception) {
            throw CoffreException("reglage", "Adresse du lab invalide (exemple : https://mon-domaine.fr).")
        }
        val scheme = u.scheme?.lowercase()
        val host = u.host?.lowercase()
        if (host.isNullOrEmpty() || u.userInfo != null || (scheme != "https" && scheme != "http")) {
            throw CoffreException("reglage", "Adresse du lab invalide (exemple : https://mon-domaine.fr).")
        }
        if (scheme == "http" && host !in LOOPBACK) throw CoffreException("reglage", "Le lab doit être joint en https.")
        val defaultPort = if (scheme == "https") 443 else 80
        return if (u.port == -1 || u.port == defaultPort) "$scheme://$host" else "$scheme://$host:${u.port}"
    }

    class Endpoints(val origin: String, val clientId: String = DEFAULT_CLIENT_ID, realm: String = DEFAULT_REALM) {
        val issuer = "$origin/auth/realms/${URLEncoder.encode(realm, "UTF-8")}"
        val authorize = "$issuer/protocol/openid-connect/auth"
        val token = "$issuer/protocol/openid-connect/token"
        val revoke = "$issuer/protocol/openid-connect/revoke"
        val storage = "$origin/storage-api/api"
        val redirectUri = "$origin$RETURN_PATH"
    }
}
