package io.github.sacha37420.coffre

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import io.github.sacha37420.coffre.core.VaultState
import io.github.sacha37420.coffre.core.VaultStorage
import org.json.JSONObject
import java.io.File

/** Un seul état de coffre par processus : l'activité, et plus tard le service de saisie automatique. */
class CoffreApp : Application() {
    lateinit var vault: VaultState
        private set
    lateinit var biometrie: Biometrie
        private set

    override fun onCreate() {
        super.onCreate()
        vault = VaultState(FileVaultStorage(this))
        biometrie = Biometrie(this)
    }
}

val Context.coffre: CoffreApp get() = applicationContext as CoffreApp

/**
 * Fichier de clé (déjà chiffré par la phrase) dans le stockage privé de l'application, écrit de façon
 * atomique ; délai de verrouillage dans les préférences. Rien d'autre n'est écrit : la clé
 * déverrouillée reste en mémoire (VaultState).
 */
class FileVaultStorage(context: Context) : VaultStorage {
    private val file = AtomicFile(File(context.filesDir, "cle.coffre-cle"))
    private val prefs = context.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    override fun readKeyFile(): JSONObject? = try {
        JSONObject(String(file.readFully(), Charsets.UTF_8))
    } catch (e: java.io.FileNotFoundException) {
        null
    }

    override fun writeKeyFile(keyFile: JSONObject) {
        val out = file.startWrite()
        try {
            out.write(keyFile.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    override fun deleteKeyFile() = file.delete()

    override fun readAutoLockMinutes(): Int? = prefs.getInt("verrouillage_min", -1).takeIf { it > 0 }

    override fun writeAutoLockMinutes(minutes: Int) {
        prefs.edit().putInt("verrouillage_min", minutes).apply()
    }
}
