package io.github.sacha37420.coffre.core

import java.util.Base64

/** base64url sans remplissage (RFC 4648 §5) — docs/FORMAT.md. */
object Base64Url {
    private val ALPHABET = Regex("^[A-Za-z0-9_-]*$")

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(text: String): ByteArray {
        if (!ALPHABET.matches(text) || text.length % 4 == 1) throw IllegalArgumentException("base64url invalide")
        return Base64.getUrlDecoder().decode(text)
    }
}
