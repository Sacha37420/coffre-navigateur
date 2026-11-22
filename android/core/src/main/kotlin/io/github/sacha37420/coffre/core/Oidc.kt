package io.github.sacha37420.coffre.core

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** OpenID Connect, code d'autorisation + PKCE S256, client public — comme src/lib/oidc.js. */
object Oidc {
    private val random = SecureRandom()

    class Pkce(val verifier: String, val challenge: String)

    class Tokens(val access: String, val refresh: String?, val expires: Long, val refreshExpires: Long) {
        fun toJson(): JSONObject = JSONObject().put("access", access).put("refresh", refresh ?: JSONObject.NULL)
            .put("expires", expires).put("refreshExpires", refreshExpires)

        companion object {
            fun fromJson(j: JSONObject) = Tokens(j.getString("access"), j.optString("refresh").takeIf { !j.isNull("refresh") && it.isNotEmpty() },
                j.getLong("expires"), j.getLong("refreshExpires"))
        }
    }

    fun randomToken(bytes: Int = 32): String = Base64Url.encode(ByteArray(bytes).also { random.nextBytes(it) })

    fun pkce(): Pkce {
        val verifier = randomToken(48)
        val challenge = Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return Pkce(verifier, challenge)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun form(params: Map<String, String>): String = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }

    fun authorizeUrl(ep: Lab.Endpoints, state: String, challenge: String): String = ep.authorize + "?" + form(linkedMapOf(
        "response_type" to "code", "client_id" to ep.clientId, "redirect_uri" to ep.redirectUri, "scope" to "openid",
        "state" to state, "code_challenge" to challenge, "code_challenge_method" to "S256",
    ))

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split("&").filter { it.isNotEmpty() }.associate {
        val i = it.indexOf('=')
        val k = URLDecoder.decode(if (i < 0) it else it.substring(0, i), "UTF-8")
        k to URLDecoder.decode(if (i < 0) "" else it.substring(i + 1), "UTF-8")
    }

    /** Lit l'adresse de retour ; rend le code ou lève une erreur fixe. */
    fun parseCallback(url: String, redirectUri: String, state: String): String {
        val u = try { URI(url) } catch (e: Exception) { throw CoffreException("connexion", "Retour de connexion invalide.") }
        val base = try { URI(redirectUri) } catch (e: Exception) { throw CoffreException("connexion", "Retour de connexion invalide.") }
        if (u.scheme != base.scheme || u.host != base.host || u.port != base.port || u.rawPath != base.rawPath) {
            throw CoffreException("connexion", "Retour de connexion invalide.")
        }
        val p = query(u.rawQuery ?: u.rawFragment)
        if (p["state"] != state) throw CoffreException("connexion", "Retour de connexion refusé (état inattendu).")
        p["error"]?.let {
            if (it == "access_denied") throw CoffreException("acces_refuse", "Accès refusé : votre compte n'est pas autorisé à utiliser le coffre.")
            throw CoffreException("connexion", "La connexion a échoué.")
        }
        return p["code"]?.takeIf { it.isNotEmpty() } ?: throw CoffreException("connexion", "Retour de connexion invalide.")
    }

    private fun postToken(http: Http, ep: Lab.Endpoints, params: Map<String, String>, now: Long): Tokens {
        val res = try {
            http.request("POST", ep.token, mapOf("Content-Type" to "application/x-www-form-urlencoded"), form(params).toByteArray())
        } catch (e: IOException) {
            throw CoffreException("reseau", "Le lab est injoignable.")
        }
        if (!res.ok) throw CoffreException("connexion", "La connexion a été refusée par le lab.")
        try {
            val j = JSONObject(res.text)
            val refresh = j.optString("refresh_token").takeIf { it.isNotEmpty() }
            return Tokens(j.getString("access_token"), refresh, now + j.optLong("expires_in", 0) * 1000,
                if (refresh != null) now + j.optLong("refresh_expires_in", 0) * 1000 else 0)
        } catch (e: JSONException) {
            throw CoffreException("connexion", "Réponse de connexion invalide.")
        }
    }

    fun exchangeCode(http: Http, ep: Lab.Endpoints, code: String, verifier: String, now: Long): Tokens = postToken(http, ep, linkedMapOf(
        "grant_type" to "authorization_code", "client_id" to ep.clientId, "code" to code,
        "code_verifier" to verifier, "redirect_uri" to ep.redirectUri,
    ), now)

    fun refresh(http: Http, ep: Lab.Endpoints, refreshToken: String, now: Long): Tokens = postToken(http, ep, linkedMapOf(
        "grant_type" to "refresh_token", "client_id" to ep.clientId, "refresh_token" to refreshToken,
    ), now)
}
