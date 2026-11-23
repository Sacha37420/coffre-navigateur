package io.github.sacha37420.coffre

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.SaveInfo
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import io.github.sacha37420.coffre.core.CoffreCrypto
import io.github.sacha37420.coffre.core.Entries
import io.github.sacha37420.coffre.core.Forms
import io.github.sacha37420.coffre.core.Generator
import java.net.URI

/**
 * Outils communs au service de saisie automatique et à son écran de déverrouillage : lecture de l'écran
 * demandeur, confiance dans le navigateur, construction des propositions, enregistrement.
 */
object Saisie {
    /** Ce qu'on retient d'une demande : origine (crue), champs à remplir, création de compte. */
    class Cible(val origin: String, val username: AutofillId?, val passwords: List<AutofillId>, val creation: Boolean)

    private fun toNode(v: AssistStructure.ViewNode): Forms.Node {
        val html = v.htmlInfo
        val attrs = html?.attributes?.associate { (it.first ?: "").lowercase() to (it.second ?: "") } ?: emptyMap()
        val variation = v.inputType and InputType.TYPE_MASK_VARIATION
        val pwd = (v.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
            (v.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
            (v.inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return Forms.Node(
            id = v.autofillId?.takeIf { v.autofillType == View.AUTOFILL_TYPE_TEXT },
            hints = v.autofillHints?.toList() ?: emptyList(),
            htmlTag = html?.tag,
            htmlType = attrs["type"],
            htmlAutocomplete = attrs["autocomplete"],
            inputPassword = pwd,
            idEntry = listOfNotNull(v.idEntry, attrs["name"], attrs["id"]).joinToString(" "),
            webDomain = v.webDomain,
            webScheme = if (Build.VERSION.SDK_INT >= 28) v.webScheme else null,
            visible = v.visibility == View.VISIBLE,
            children = (0 until v.childCount).map { toNode(v.getChildAt(it)) },
        )
    }

    fun tree(structure: AssistStructure): Forms.Node =
        Forms.Node(children = (0 until structure.windowNodeCount).map { toNode(structure.getWindowNodeAt(it).rootViewNode) })

    /** Navigateur connu ET authentique (application système, ou installée depuis le Play Store). */
    fun trusted(context: Context, pkg: String): Boolean {
        if (pkg !in Forms.BROWSERS) return false
        val pm = context.packageManager
        val system = try {
            pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
        } catch (e: Exception) {
            return false   // absent
        }
        val installer = if (system) null else try {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        } catch (e: Exception) {
            null           // installateur inconnu : pas cru
        }
        return Forms.trustedBrowser(pkg, system, installer)
    }

    /** Cible de la demande, ou null : rien à proposer (pas de mot de passe, pas https, application non crue). */
    fun cible(context: Context, structure: AssistStructure): Cible? {
        val form = Forms.parse(tree(structure)) ?: return null
        val origin = form.origin ?: return null
        if (!trusted(context, structure.activityComponent.packageName)) return null
        return Cible(origin, form.username as AutofillId?, form.passwords.map { it as AutofillId }, form.creation)
    }

    /** Valeurs saisies dans les champs de la cible (pour l'enregistrement). */
    fun valeurs(structure: AssistStructure, c: Cible): Pair<String, String>? {
        val found = mutableMapOf<AutofillId, String>()
        fun walk(v: AssistStructure.ViewNode) {
            v.autofillId?.let { id -> v.autofillValue?.takeIf { it.isText }?.textValue?.toString()?.let { found[id] = it } }
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode)
        val password = c.passwords.firstNotNullOfOrNull { found[it]?.takeIf { p -> p.isNotEmpty() } } ?: return null
        return (c.username?.let { found[it] } ?: "") to password
    }

    private fun item(context: Context, title: String, subtitle: String?): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, title)
            setTextViewText(R.id.autofill_subtitle, subtitle ?: "")
        }

    fun host(origin: String): String = try { URI(origin).host ?: origin } catch (e: Exception) { origin }

    private fun saveInfo(c: Cible): SaveInfo {
        val type = SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (c.username != null) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
        return SaveInfo.Builder(type, c.passwords.toTypedArray()).apply {
            c.username?.let { setOptionalIds(arrayOf(it)) }
        }.build()
    }

    /** Propositions, coffre ouvert : un choix par compte du site (au toucher), génération sur une création. */
    @Suppress("DEPRECATION")   // API de Dataset compatible Android 8 (le remplacement date d'Android 13)
    fun reponse(context: Context, c: Cible, vault: CoffreCrypto.VaultKey): FillResponse {
        val app = context.coffre
        val accounts = Entries.forOrigin(app.entries.decryptAll(vault).first, c.origin)
        val b = FillResponse.Builder()
        for (e in accounts) {
            val user = e.optString("username")
            val p = item(context, user.ifEmpty { e.optString("name") }, if (user.isEmpty()) e.optString("name") else host(c.origin))
            val d = Dataset.Builder(p)
            c.username?.let { if (user.isNotEmpty()) d.setValue(it, AutofillValue.forText(user)) }
            c.passwords.take(1).forEach { d.setValue(it, AutofillValue.forText(e.optString("password"))) }
            b.addDataset(d.build())
        }
        if (c.creation || accounts.isEmpty()) {
            val generated = Generator.generate()
            val d = Dataset.Builder(item(context, context.getString(R.string.autofill_generate), context.getString(R.string.autofill_generate_sub)))
            c.passwords.forEach { d.setValue(it, AutofillValue.forText(generated)) }
            b.addDataset(d.build())
        }
        return b.setSaveInfo(saveInfo(c)).build()
    }

    /** Coffre verrouillé : une seule proposition, « Déverrouiller », qui ouvre l'écran de déverrouillage. */
    fun reponseVerrouillee(context: Context, c: Cible): FillResponse {
        val ids = (listOfNotNull(c.username) + c.passwords).toTypedArray()
        val intent = Intent(context, DeverrouillageSaisieActivity::class.java)
            .putExtra(DeverrouillageSaisieActivity.EXTRA_MODE, DeverrouillageSaisieActivity.MODE_REMPLIR)
            .putExtra(DeverrouillageSaisieActivity.EXTRA_ORIGIN, c.origin)
            .putExtra(DeverrouillageSaisieActivity.EXTRA_USERNAME_ID, c.username)
            .putExtra(DeverrouillageSaisieActivity.EXTRA_PASSWORD_IDS, c.passwords.toTypedArray())
            .putExtra(DeverrouillageSaisieActivity.EXTRA_CREATION, c.creation)
        // Mutable : le système y ajoute la structure de l'écran ; destination explicite (notre activité).
        val pi = PendingIntent.getActivity(context, c.origin.hashCode(), intent,
            PendingIntent.FLAG_CANCEL_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
        return FillResponse.Builder()
            .setAuthentication(ids, pi.intentSender, item(context, context.getString(R.string.autofill_unlock), host(c.origin)))
            .setSaveInfo(saveInfo(c))
            .build()
    }

    /** Enregistre (nouveau compte ou nouveau mot de passe) ; rend false si rien n'était à faire. */
    fun enregistrer(context: Context, vault: CoffreCrypto.VaultKey, origin: String, username: String, password: String): Boolean {
        val app = context.coffre
        val entries = app.entries.decryptAll(vault).first
        val p = Entries.proposalFor(entries, origin, username, password) ?: return false
        val entry = if (p.kind == "maj") Entries.withPassword(entries.first { it.getString("id") == p.entryId }, password)
        else Entries.newEntry(host(origin), listOf(origin), username, password)
        app.entries.put(vault, entry)
        app.syncNow()
        return true
    }
}
