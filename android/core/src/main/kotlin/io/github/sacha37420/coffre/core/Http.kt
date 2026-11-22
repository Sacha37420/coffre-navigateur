package io.github.sacha37420.coffre.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Réponse HTTP brute. */
class HttpResponse(val status: Int, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
    val ok: Boolean get() = status in 200..299
}

/** Transport HTTP, remplaçable dans les tests. */
fun interface Http {
    /** @throws IOException si le serveur est injoignable. */
    fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse
}

/**
 * HTTP de la plateforme (HttpURLConnection, présent sur la JVM comme sur Android) : aucune bibliothèque.
 * Pas de redirection suivie (aucune route utilisée n'en fait), pas de cache, pas de cookie.
 */
class UrlConnectionHttp(private val timeoutMs: Int = 20_000) : Http {
    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val out = ByteArrayOutputStream()
            stream?.use { it.copyTo(out) }
            return HttpResponse(status, out.toByteArray())
        } finally {
            c.disconnect()
        }
    }
}
