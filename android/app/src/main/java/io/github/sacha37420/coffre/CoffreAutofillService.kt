package io.github.sacha37420.coffre

import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest

/**
 * Service de saisie automatique d'Android (« Coffre du lab » choisi dans les réglages, et « Saisie automatique
 * avec un autre service » dans Chrome). Ne propose que sur des sites https annoncés par un navigateur fiable ;
 * ne remplit rien sans un toucher de l'utilisateur sur une proposition.
 */
class CoffreAutofillService : AutofillService() {
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val c = try { Saisie.cible(this, structure) } catch (e: Exception) { null } ?: return callback.onSuccess(null)
        val vault = coffre.vault.key()
        try {
            callback.onSuccess(if (vault == null) Saisie.reponseVerrouillee(this, c) else Saisie.reponse(this, c, vault))
        } catch (e: Exception) {
            callback.onSuccess(null)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess()
        val c = try { Saisie.cible(this, structure) } catch (e: Exception) { null } ?: return callback.onSuccess()
        val (username, password) = Saisie.valeurs(structure, c) ?: return callback.onSuccess()
        val vault = coffre.vault.key()
        if (vault != null) {
            try { Saisie.enregistrer(this, vault, c.origin, username, password) } catch (e: Exception) { /* rien n'est perdu côté site */ }
            return callback.onSuccess()
        }
        // Coffre verrouillé : écran de déverrouillage, qui enregistre ensuite (Android 9+).
        if (Build.VERSION.SDK_INT >= 28) {
            val intent = Intent(this, DeverrouillageSaisieActivity::class.java)
                .putExtra(DeverrouillageSaisieActivity.EXTRA_MODE, DeverrouillageSaisieActivity.MODE_ENREGISTRER)
                .putExtra(DeverrouillageSaisieActivity.EXTRA_ORIGIN, c.origin)
                .putExtra(DeverrouillageSaisieActivity.EXTRA_USERNAME, username)
                .putExtra(DeverrouillageSaisieActivity.EXTRA_PASSWORD, password)
            val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            callback.onSuccess(pi.intentSender)
        } else {
            callback.onFailure(getString(R.string.autofill_save_locked))
        }
    }
}
