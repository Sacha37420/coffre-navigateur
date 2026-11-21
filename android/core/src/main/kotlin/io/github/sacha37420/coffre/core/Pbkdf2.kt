package io.github.sacha37420.coffre.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2-HMAC-SHA256 (RFC 8018 §5.2) sur le HMAC de la plateforme, à partir d'**octets**.
 *
 * Pourquoi pas `SecretKeyFactory("PBKDF2WithHmacSHA256")` : il reçoit la phrase en `char[]` et la
 * convertit lui-même en octets, avec un encodage qui n'est pas garanti identique d'une version
 * d'Android à l'autre. L'extension dérive à partir de l'UTF-8 de la phrase normalisée NFC : ici on
 * passe exactement ces octets. Vérifié par les vecteurs de la RFC 7914 §11 et ceux de l'extension.
 */
object Pbkdf2 {
    fun hmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        require(iterations >= 1 && length >= 1)
        val mac = Mac.getInstance("HmacSHA256")
        // Une clé HMAC vide est légale (RFC 2104) ; SecretKeySpec la refuse : on la remplace par un
        // octet nul, équivalent pour HMAC (la clé est complétée par des zéros jusqu'à la taille du bloc).
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (length + hLen - 1) / hLen
        val out = ByteArray(length)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            mac.doFinal(u, 0)
            u.copyInto(t)
            for (n in 2..iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val offset = (i - 1) * hLen
            t.copyInto(out, offset, 0, minOf(hLen, length - offset))
        }
        u.fill(0)
        t.fill(0)
        return out
    }
}
