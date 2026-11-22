package io.github.sacha37420.coffre.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder

/** Ce dont la synchronisation a besoin (interface remplaçable par un faux dans les tests). */
interface RemoteFiles {
    class Item(val path: String, val updated: String?)
    fun list(ns: String, prefix: String): List<Item>
    fun read(ns: String, path: String): String?
    /** Rend la date de dépôt donnée par le serveur. */
    fun write(ns: String, path: String, text: String): String?
}

/**
 * Client de l'API storage du lab (routes inchangées), comme src/lib/storage-client.js. Bearer seulement.
 * storage répond 403 (et non 401) à un jeton expiré ou révoqué : sur l'un comme l'autre, `renew` oublie le
 * jeton d'accès et on retente une fois avant de conclure à un refus.
 */
class StorageClient(
    private val base: String,
    private val http: Http,
    private val token: () -> String,
    private val renew: (() -> Unit)? = null,
) : RemoteFiles {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun encPath(p: String) = p.split("/").joinToString("/") { enc(it) }

    private fun call(method: String, path: String, body: ByteArray? = null, contentType: String? = null,
                     allow404: Boolean = false, retried: Boolean = false): HttpResponse? {
        val headers = mutableMapOf("Authorization" to "Bearer ${token()}", "Cache-Control" to "no-store")
        if (contentType != null) headers["Content-Type"] = contentType
        val res = try { http.request(method, base + path, headers, body) } catch (e: IOException) {
            throw CoffreException("reseau", "Le lab est injoignable.")
        }
        if (res.status == 404 && allow404) return null
        if ((res.status == 401 || res.status == 403) && renew != null && !retried) {
            renew.invoke()
            return call(method, path, body, contentType, allow404, true)
        }
        if (res.status == 401) throw CoffreException("session", "Session expirée : reconnectez-vous au lab.")
        if (res.status == 403) throw CoffreException("acces_refuse", "Le lab refuse l'accès au stockage pour ce compte.")
        if (!res.ok) throw CoffreException("serveur", "Le stockage du lab a répondu une erreur (${res.status}).")
        return res
    }

    /** Nom de l'espace personnel de l'utilisateur connecté. */
    fun me(): String = try {
        JSONObject(call("GET", "/me/")!!.text).getString("username").takeIf { it.isNotEmpty() }
            ?: throw CoffreException("serveur", "Réponse inattendue du lab.")
    } catch (e: JSONException) {
        throw CoffreException("serveur", "Réponse inattendue du lab.")
    }

    override fun list(ns: String, prefix: String): List<RemoteFiles.Item> = try {
        val arr = JSONArray(call("GET", "/files/${enc(ns)}/?prefix=${enc(prefix)}")!!.text)
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            RemoteFiles.Item(it.getString("relative_path"), it.optString("updated_at").takeIf { u -> u.isNotEmpty() })
        }
    } catch (e: JSONException) {
        throw CoffreException("serveur", "Réponse inattendue du lab.")
    }

    override fun read(ns: String, path: String): String? =
        call("GET", "/files/${enc(ns)}/content/${encPath(path)}", allow404 = true)?.text

    override fun write(ns: String, path: String, text: String): String? {
        val boundary = "----coffre" + Oidc.randomToken(12)
        val out = ByteArrayOutputStream()
        fun line(s: String) = out.write((s + "\r\n").toByteArray(Charsets.UTF_8))
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"relative_path\"")
        line("")
        line(path)
        line("--$boundary")
        line("Content-Disposition: form-data; name=\"file\"; filename=\"${path.substringAfterLast('/')}\"")
        line("Content-Type: application/octet-stream")
        line("")
        out.write(text.toByteArray(Charsets.UTF_8))
        line("")
        line("--$boundary--")
        val res = call("POST", "/files/${enc(ns)}/", out.toByteArray(), "multipart/form-data; boundary=$boundary")!!
        return try { JSONObject(res.text).optString("updated_at").takeIf { it.isNotEmpty() } } catch (e: JSONException) { null }
    }
}
