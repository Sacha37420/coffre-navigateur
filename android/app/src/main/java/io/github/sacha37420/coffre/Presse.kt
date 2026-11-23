package io.github.sacha37420.coffre

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/** Presse-papiers : contenu marqué « sensible » (masqué dans l'aperçu, Android 13+), vidé au bout de 30 s. */
object Presse {
    const val CLEAR_MS = 30_000L
    private val handler = Handler(Looper.getMainLooper())

    fun copy(context: Context, value: String) {
        if (value.isEmpty()) return
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(context.getString(R.string.app_name), value)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)
        // Vidé s'il contient toujours cette valeur (Android 10+ ne laisse lire le presse-papiers qu'au premier plan :
        // sinon on ne peut rien vérifier, et on n'efface pas à l'aveugle ce que l'utilisateur a copié depuis).
        handler.postDelayed({
            val current = try { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() } catch (e: Exception) { null }
            if (current == value) {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, CLEAR_MS)
    }
}
