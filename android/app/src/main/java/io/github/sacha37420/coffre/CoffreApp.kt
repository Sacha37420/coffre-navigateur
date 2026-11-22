package io.github.sacha37420.coffre

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import io.github.sacha37420.coffre.core.CoffreException
import io.github.sacha37420.coffre.core.EntryFiles
import io.github.sacha37420.coffre.core.EntryStore
import io.github.sacha37420.coffre.core.Http
import io.github.sacha37420.coffre.core.Lab
import io.github.sacha37420.coffre.core.LabSession
import io.github.sacha37420.coffre.core.LabStorage
import io.github.sacha37420.coffre.core.UrlConnectionHttp
import io.github.sacha37420.coffre.core.CoffreCrypto
import io.github.sacha37420.coffre.core.VaultState
import io.github.sacha37420.coffre.core.VaultStorage
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Un seul état par processus : coffre, cache des entrées, session du lab. */
class CoffreApp : Application() {
    lateinit var vault: VaultState
        private set
    lateinit var biometrie: Biometrie
        private set
    lateinit var entries: EntryStore
        private set
    /** Remplaçable par les tests. */
    var http: Http = UrlConnectionHttp()
    /** null si l'APK a été construite sans adresse de lab. */
    var lab: LabSession? = null
        private set
    private val syncExecutor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("lab", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        vault = VaultState(FileVaultStorage(this))
        biometrie = Biometrie(this)
        entries = EntryStore(AtomicJsonFile(File(filesDir, "entrees.json")))
        setupLab(getString(R.string.lab_origin))
    }

    /** Appelé au démarrage ; les tests le rappellent avec l'adresse de leur faux lab. */
    fun setupLab(origin: String) {
        lab = origin.takeIf { it.isNotEmpty() }?.let {
            // peekKey : une synchronisation ne repousse pas le verrouillage automatique.
            LabSession(Lab.Endpoints(Lab.normalize(it)), FileLabStorage(this), HttpProxy(this), { vault.peekKey() })
        }
    }

    /** Résultat du dernier passage (nombres seulement, ou message fixe). */
    class SyncStatus(val at: Long, val ok: Boolean, val text: String)

    fun lastSync(): SyncStatus? = prefs.getLong("synchro_at", 0).takeIf { it > 0 }?.let {
        SyncStatus(it, prefs.getBoolean("synchro_ok", false), prefs.getString("synchro_texte", "").orEmpty())
    }

    /** Lance un passage de synchronisation en arrière-plan (un seul à la fois) ; `done` sur le fil appelant de l'exécuteur. */
    fun syncNow(done: (SyncStatus) -> Unit = {}) {
        syncExecutor.execute {
            val l = lab
            val v = vault.peekKey()
            val user = l?.username
            if (l == null || v == null || user == null) return@execute
            val status = try {
                val r = entries.synchronize(l.storage(), user, v, CoffreCrypto.vaultDescriptor(vault.keyFile()!!))
                SyncStatus(System.currentTimeMillis(), true, getString(R.string.sync_report, r.received, r.sent) +
                    (if (r.restored > 0) getString(R.string.sync_restored, r.restored) else "") +
                    (if (r.ignored > 0) getString(R.string.sync_ignored, r.ignored) else ""))
            } catch (e: CoffreException) {
                SyncStatus(System.currentTimeMillis(), false, e.message.orEmpty())
            } catch (e: Exception) {
                SyncStatus(System.currentTimeMillis(), false, getString(R.string.unexpected))
            }
            prefs.edit().putLong("synchro_at", status.at).putBoolean("synchro_ok", status.ok).putString("synchro_texte", status.text).apply()
            done(status)
        }
    }

    /** Retrait du coffre : son cache, sa session et son dernier passage n'ont plus rien à faire ici. */
    fun forgetAll() {
        biometrie.disable()
        vault.forget()
        entries.clear()
        lab?.logout()
        prefs.edit().clear().apply()
    }

    fun onLocked() {
        lab?.forgetMemory()
    }
}

/** Relaie vers `CoffreApp.http`, pour qu'un test puisse le remplacer après la création de la session. */
private class HttpProxy(private val app: CoffreApp) : Http {
    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?) =
        app.http.request(method, url, headers, body)
}

val Context.coffre: CoffreApp get() = applicationContext as CoffreApp

/** Fichier JSON écrit de façon atomique dans le stockage privé. */
class AtomicJsonFile(file: File) : EntryFiles {
    private val f = AtomicFile(file)

    override fun load(): JSONObject? = try {
        JSONObject(String(f.readFully(), Charsets.UTF_8))
    } catch (e: java.io.FileNotFoundException) {
        null
    }

    override fun save(state: JSONObject) = write(state)

    fun write(j: JSONObject?) {
        if (j == null) { f.delete(); return }
        val out = f.startWrite()
        try {
            out.write(j.toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }
}

/** Session du lab : nom d'espace (préférences), jetons chiffrés par la clé du coffre, connexion en attente. */
class FileLabStorage(context: Context) : LabStorage {
    private val prefs = context.getSharedPreferences("lab", Context.MODE_PRIVATE)
    private val sealed = AtomicJsonFile(File(context.filesDir, "jetons.json"))
    private val pending = AtomicJsonFile(File(context.filesDir, "connexion-en-attente.json"))

    override fun readUsername(): String? = prefs.getString("espace", null)
    override fun writeUsername(username: String?) { prefs.edit().putString("espace", username).apply() }
    override fun readSealedTokens() = sealed.load()
    override fun writeSealedTokens(sealed: JSONObject?) = this.sealed.write(sealed)
    override fun readPending() = pending.load()
    override fun writePending(pending: JSONObject?) = this.pending.write(pending)
}

/**
 * Fichier de clé (déjà chiffré par la phrase) dans le stockage privé, écrit de façon atomique ; délai de
 * verrouillage dans les préférences. La clé déverrouillée reste en mémoire (VaultState).
 */
class FileVaultStorage(context: Context) : VaultStorage {
    private val file = AtomicJsonFile(File(context.filesDir, "cle.coffre-cle"))
    private val prefs = context.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    override fun readKeyFile(): JSONObject? = file.load()
    override fun writeKeyFile(keyFile: JSONObject) = file.write(keyFile)
    override fun deleteKeyFile() = file.write(null)
    override fun readAutoLockMinutes(): Int? = prefs.getInt("verrouillage_min", -1).takeIf { it > 0 }
    override fun writeAutoLockMinutes(minutes: Int) { prefs.edit().putInt("verrouillage_min", minutes).apply() }
}
