package io.github.sacha37420.coffre.core

import java.security.SecureRandom
import kotlin.math.floor
import kotlin.math.ln

/** Générateur de mots de passe — même règles que src/lib/generator.js. */
object Generator {
    val CLASSES = linkedMapOf(
        "lower" to "abcdefghijklmnopqrstuvwxyz",
        "upper" to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "digits" to "0123456789",
        "symbols" to "!#$%&()*+,-./:;<=>?@[]^_{|}~",
    )
    private const val AMBIGUOUS = "Il1O0o|`'\""
    private val random = SecureRandom()

    data class Options(
        val length: Int = 20,
        val lower: Boolean = true,
        val upper: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val avoidAmbiguous: Boolean = true,
    )

    private fun sets(o: Options): List<List<Char>> = CLASSES.filterKeys {
        when (it) { "lower" -> o.lower; "upper" -> o.upper; "digits" -> o.digits; else -> o.symbols }
    }.values.map { s -> s.filter { !o.avoidAmbiguous || it !in AMBIGUOUS }.toList() }

    /** SecureRandom.nextInt(n) est sans biais (rejet interne). */
    fun generate(o: Options = Options()): String {
        require(o.length in 8..128) { "La longueur doit être comprise entre 8 et 128." }
        val s = sets(o)
        require(s.isNotEmpty()) { "Choisir au moins une famille de caractères." }
        require(s.size <= o.length) { "Longueur trop courte pour les familles choisies." }
        val all = s.flatten()
        val chars = s.map { it[random.nextInt(it.size)] }.toMutableList()
        while (chars.size < o.length) chars.add(all[random.nextInt(all.size)])
        for (i in chars.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            chars[i] = chars[j].also { chars[j] = chars[i] }
        }
        return chars.joinToString("")
    }

    /** Entropie théorique en bits (indicative). */
    fun entropyBits(o: Options = Options()): Int {
        val n = sets(o).sumOf { it.size }
        return if (n == 0) 0 else floor(o.length * ln(n.toDouble()) / ln(2.0)).toInt()
    }
}
